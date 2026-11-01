package fr.first.firstdtc.game;

import com.bgsoftware.superiorskyblock.api.island.Island;
import com.bgsoftware.superiorskyblock.api.wrappers.SuperiorPlayer;
import fr.first.firstdtc.FirstDtcPlugin;
import fr.first.firstdtc.config.PluginConfig;
import fr.first.firstdtc.util.Msg;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One running "Destroy the Core" event.
 *
 * The class holds:
 *  - the {@link BossBar} shown to every online player,
 *  - per-island damage stats (thread-safe),
 *  - a global-region ticker that updates the bar and enforces the timer.
 *
 * All mutation of health / stats happens through {@link #recordBreak}, which
 * synchronises on {@code this} for the tiny critical section that decides how
 * much damage actually lands (so late hits after the boss dies don't over-drain).
 */
public final class CoreGame {

    private final FirstDtcPlugin plugin;
    private final PluginConfig cfg;
    private final long startedAtMillis = System.currentTimeMillis();
    private final long durationMillis;

    private final BossBar bossBar;

    private volatile double maxHealth;
    private volatile double health;

    private final Map<UUID, IslandStats> statsByIsland = new ConcurrentHashMap<>();

    private final AtomicBoolean ended = new AtomicBoolean(false);
    private ScheduledTask tickTask;

    /**
     * Cached ranked snapshot for read-heavy consumers (PAPI, /dtc status).
     * Written under the ranking build itself, invalidated on every recorded
     * hit. A hot scoreboard that queries {@code %dtc_top_1_damage%} every
     * tick for every player therefore triggers at most one re-sort per tick
     * instead of one per placeholder resolution.
     */
    private volatile List<IslandStats> rankingCache = null;
    private volatile Map<UUID, Integer> rankIndexCache = null;

    /**
     * Cached bossbar title, keyed by (health rounded, seconds left). Adventure
     * sends a name packet on every {@code bossBar.name(...)} even when the
     * Component is equal, so avoiding the MiniMessage re-parse plus the packet
     * matters at 1 tick/s across many players.
     */
    private volatile long lastTitleKey = Long.MIN_VALUE;
    private volatile float lastBossProgress = -1f;

    public CoreGame(FirstDtcPlugin plugin) {
        this.plugin = plugin;
        this.cfg = plugin.getPluginConfig();
        this.durationMillis = cfg.getDurationSeconds() * 1000L;
        this.maxHealth = cfg.getHealth();
        this.health = this.maxHealth;
        this.bossBar = BossBar.bossBar(
                renderTitle(),
                1.0f,
                cfg.getBossColor(),
                cfg.getBossOverlay());
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    public void start() {
        // Late-bind SSBOneBlock in case plugin load order left it unresolved
        // on our onEnable (some hot-reload setups enable modules after us).
        plugin.getOneBlockHook().tryResolve();

        // Boss bar (top of screen) - opt-in via config.
        if (cfg.isBossbarEnabled()) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                p.showBossBar(bossBar);
            }
        }

        Bukkit.getServer().sendMessage(Msg.parse(
                cfg.messages().get("event.start",
                        "%prefix%<white>Le Core est apparu ! <gray>(<white>%duration%<gray>s)"),
                Map.of("duration", Integer.toString(cfg.getDurationSeconds()))));

        // Log console non conditionnel pour qu'un "rien ne se passe" nous dise
        // au moins si la partie a effectivement démarré.
        plugin.getLogger().info("Partie démarrée : " + Math.round(maxHealth) + " HP, "
                + cfg.getDurationSeconds() + "s, dégâts/bloc=" + cfg.getDamagePerBlock()
                + ", only-oneblock=" + cfg.isOnlyOneBlock() + ".");

        this.tickTask = plugin.getScheduler().runGlobalRepeating(t -> tick(), 20L, 20L);

        runHookCommands(cfg.getHookOnStart());
    }

    private void tick() {
        if (ended.get()) return;
        long remainingSeconds = remainingSeconds();
        if (remainingSeconds <= 0) {
            end(EndReason.TIMEOUT);
            return;
        }

        // Only re-render the title (MiniMessage parse + name packet) when the
        // visible state actually changed: rounded HP OR remaining second. This
        // cuts ~1 parse+packet per player per tick to ~1 per second.
        long displayedHp = Math.round(Math.max(0.0, health));
        long titleKey = (remainingSeconds << 32) | (displayedHp & 0xFFFFFFFFL);
        if (titleKey != lastTitleKey) {
            lastTitleKey = titleKey;
            bossBar.name(renderTitle());
        }
        float progress = (float) Math.max(0.0, Math.min(1.0, health / maxHealth));
        if (progress != lastBossProgress) {
            lastBossProgress = progress;
            bossBar.progress(progress);
        }
    }

    /**
     * Force-cancel the game with no rewards. Used on plugin disable / /dtc stop.
     * Still fires the on-end hooks so any textured boss / world state gets
     * cleaned up.
     */
    public void cancel() {
        if (!ended.compareAndSet(false, true)) return;
        if (tickTask != null) tickTask.cancel();
        hideBossBar();
        runHookCommands(cfg.getHookOnEnd(), Map.of("winner", "?", "island", "?"));
    }

    /**
     * Termine la partie EN COURS mais distribue quand même les récompenses
     * et les récaps sur la base des dégâts actuels. Utilisé par la commande
     * admin {@code /firstdtc finish} pour couper court sans pénaliser les
     * joueurs qui ont participé.
     */
    public void finish() {
        end(EndReason.MANUAL_FINISH);
    }

    /**
     * Called when a member of any island breaks a qualifying block. Thread-safe.
     */
    public void recordBreak(Player player, Island island) {
        if (ended.get()) return;

        double dmg;
        synchronized (this) {
            if (health <= 0) return;
            dmg = Math.min(cfg.getDamagePerBlock(), health);
            health -= dmg;
        }

        // HP total est fixe (game.health). Une nouvelle île n'agrandit plus le pool.
        IslandStats stats = statsByIsland.computeIfAbsent(island.getUniqueId(),
                k -> new IslandStats(island));
        stats.add(player.getUniqueId(), player.getName(), dmg);
        rankingCache = null;  // invalidate; next reader rebuilds lazily
        rankIndexCache = null;

        if (health <= 0) {
            end(EndReason.VICTORY);
        }
    }

    // ------------------------------------------------------------------
    // Ending / rewards
    // ------------------------------------------------------------------

    private void end(EndReason reason) {
        if (!ended.compareAndSet(false, true)) return;
        if (tickTask != null) tickTask.cancel();

        // Boss-bar teardown and payout run on the global region so we don't touch
        // Bukkit state from a random block region.
        plugin.getScheduler().runGlobal(() -> {
            hideBossBar();
            List<Map.Entry<UUID, IslandStats>> ranking = ranking();
            broadcastEnd(reason);

            if (ranking.isEmpty()) {
                Bukkit.getServer().sendMessage(Msg.parse(
                        cfg.messages().get("event.end-empty",
                                "%prefix%<gray>Aucune île n'a participé à l'événement.")));
            } else {
                distributeRewards(ranking);
                sendSummaries(ranking);
            }

            // Passe le top 3 complet dans les hooks on-end.
            // "?" comme défaut : une valeur vide serait ignorée par Bukkit au split
            // et laisserait {N} non substitués dans le template du bot Discord.
            Map<String, String> hookExtras = new HashMap<>();
            for (int t = 1; t <= 3; t++) {
                if (t <= ranking.size()) {
                    Island ti = ranking.get(t - 1).getValue().island();
                    SuperiorPlayer to = ti.getOwner();
                    hookExtras.put("winner" + t, to != null && to.getName() != null ? to.getName() : "?");
                    hookExtras.put("island" + t, islandName(ti));
                } else {
                    hookExtras.put("winner" + t, "?");
                    hookExtras.put("island" + t, "?");
                }
            }
            // Alias rétrocompat : %winner% / %island% = top 1
            hookExtras.put("winner", hookExtras.get("winner1"));
            hookExtras.put("island", hookExtras.get("island1"));
            runHookCommands(cfg.getHookOnEnd(), hookExtras);
            plugin.getGameManager().onGameEnded();
        });
    }

    /**
     * Fires each command line through the console with standard placeholders.
     */
    private void runHookCommands(List<String> commands) {
        runHookCommands(commands, Map.of());
    }

    /**
     * Fires each command line through the console with {@code %world%}, {@code %x%},
     * {@code %y%}, {@code %z%}, {@code %hp%}, {@code %maxhp%}, {@code %duration%},
     * and any extra placeholders (e.g. {@code %winner%}, {@code %island%}) substituted.
     * Runs on the global region because {@code Bukkit.dispatchCommand} for a
     * ConsoleSender requires it on Folia.
     */
    private void runHookCommands(List<String> commands, Map<String, String> extras) {
        if (commands == null || commands.isEmpty()) return;
        java.util.Map<String, String> ph = new java.util.HashMap<>();
        ph.put("world",    cfg.getHookSpawnWorld());
        ph.put("x",        Integer.toString(cfg.getHookSpawnX()));
        ph.put("y",        Integer.toString(cfg.getHookSpawnY()));
        ph.put("z",        Integer.toString(cfg.getHookSpawnZ()));
        ph.put("hp",       roundedString(Math.max(0.0, health)));
        ph.put("maxhp",    roundedString(maxHealth));
        ph.put("duration", Integer.toString(cfg.getDurationSeconds()));
        ph.putAll(extras);
        plugin.getRewardService().dispatchAll(commands, ph);
    }

    private void broadcastEnd(EndReason reason) {
        String key;
        String fallback;
        switch (reason) {
            case VICTORY -> {
                key = "event.end-victory";
                fallback = "%prefix%<white><b>Le Core a été détruit !";
            }
            case MANUAL_FINISH -> {
                key = "event.end-finish";
                fallback = "%prefix%<white>Événement terminé par un administrateur "
                        + "<gray>(<white>%hp%<gray> PV restants)<white>. Récompenses distribuées.";
            }
            default -> { // TIMEOUT
                key = "event.end-timeout";
                fallback = "%prefix%<red>Temps écoulé — le Core a survécu avec <white>%hp%<red> PV.";
            }
        }
        Bukkit.getServer().sendMessage(Msg.parse(cfg.messages().get(key, fallback), Map.of(
                "hp", roundedString(Math.max(0.0, health)),
                "maxhp", roundedString(maxHealth))));
    }

    private void distributeRewards(List<Map.Entry<UUID, IslandStats>> ranking) {
        RewardService rewards = plugin.getRewardService();
        for (int i = 0; i < ranking.size(); i++) {
            int rank = i + 1;
            IslandStats stats = ranking.get(i).getValue();
            Island island = stats.island();
            if (island.getOwner() == null) continue;

            // Every participating island gets the general block plus the rank
            // block that matches its position (may be absent -> no-op).
            rewards.giveGeneral(island, rank, stats.total(), true);
            rewards.giveRank(island, rank, stats.total(), true);
        }
    }

    private void sendSummaries(List<Map.Entry<UUID, IslandStats>> ranking) {
        if (ranking.isEmpty()) return;
        int topSize = Math.min(3, ranking.size());

        for (int i = 0; i < ranking.size(); i++) {
            int rank = i + 1;
            IslandStats stats = ranking.get(i).getValue();
            Island island = stats.island();
            Map<String, String> myPh = Map.of(
                    "island", islandName(island),
                    "damage", roundedString(stats.total()),
                    "rank",   Integer.toString(rank));

            String displayGeneral = cfg.messages().get("recap.reward-display.general", "");
            String displayRank    = cfg.messages().get("recap.reward-display.rank-" + rank, "");

            for (SuperiorPlayer member : island.getIslandMembers(true)) {
                Player online = member.asPlayer();
                if (online == null || !online.isOnline()) continue;

                // En-tête
                online.sendMessage(Msg.parse(online, cfg.messages().get("recap.header",
                        "%prefix%<white><b>Destroy the Core — Résultats</b>"), Map.of()));

                // Top 3
                for (int t = 0; t < topSize; t++) {
                    IslandStats ts = ranking.get(t).getValue();
                    Map<String, String> tPh = Map.of(
                            "island", islandName(ts.island()),
                            "damage", roundedString(ts.total()));
                    online.sendMessage(Msg.parse(online,
                            cfg.messages().get("recap.top-" + (t + 1), defaultTopLine(t + 1)), tPh));
                }

                // Séparateur
                online.sendMessage(Msg.parse(
                        cfg.messages().get("recap.separator",
                                "<dark_gray>──────────────────────────────────")));

                // Position personnelle
                online.sendMessage(Msg.parse(online,
                        cfg.messages().get("recap.your-rank",
                                " <dark_gray>┃ <gray>Votre île <dark_gray>·</dark_gray>"
                                + " <white>#%rank% <dark_gray>»</dark_gray> <red>%damage% <gray>dégâts"),
                        myPh));

                // Récompense participation
                if (!displayGeneral.isEmpty()) {
                    online.sendMessage(Msg.parse(online,
                            cfg.messages().get("recap.reward-general",
                                    " <dark_gray>┃ <gray>Participation <dark_gray>»</dark_gray> <red>%reward%"),
                            Map.of("reward", displayGeneral)));
                }

                // Bonus de rang (seulement si un display est configuré pour ce rang)
                if (!displayRank.isEmpty()) {
                    online.sendMessage(Msg.parse(online,
                            cfg.messages().get("recap.reward-ranked",
                                    " <dark_gray>┃ <gray>Bonus rang <white>#%rank%"
                                    + " <dark_gray>»</dark_gray> <red>%reward%"),
                            Map.of("rank", Integer.toString(rank), "reward", displayRank)));
                }
            }
        }
    }

    private static String defaultTopLine(int rank) {
        return switch (rank) {
            case 1 -> " <dark_gray>┃ <gradient:#FFD700:#FFA500>🥇 <b>#1</b></gradient>"
                    + " <white>%island% <dark_gray>»</dark_gray> <red>%damage% <gray>dégâts";
            case 2 -> " <dark_gray>┃ <gray>🥈 <b>#2</b></gray>"
                    + " <white>%island% <dark_gray>»</dark_gray> <red>%damage% <gray>dégâts";
            case 3 -> " <dark_gray>┃ <color:#CD7F32>🥉 <b>#3</b></color>"
                    + " <white>%island% <dark_gray>»</dark_gray> <red>%damage% <gray>dégâts";
            default -> " <dark_gray>┃ <white>#%rank% %island% <dark_gray>»</dark_gray> <red>%damage% <gray>dégâts";
        };
    }


    private List<Map.Entry<UUID, IslandStats>> ranking() {
        List<Map.Entry<UUID, IslandStats>> list = new ArrayList<>(statsByIsland.entrySet());
        list.sort((a, b) -> Double.compare(b.getValue().total(), a.getValue().total()));
        return list;
    }

    // ------------------------------------------------------------------
    // Boss bar / helpers
    // ------------------------------------------------------------------

    public void showTo(Player player) {
        if (ended.get() || !cfg.isBossbarEnabled()) return;
        player.showBossBar(bossBar);
    }

    private void hideBossBar() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.hideBossBar(bossBar);
        }
    }

    private Component renderTitle() {
        Map<String, String> ph = Map.of(
                "time", Msg.formatDuration(remainingSeconds()),
                "hp", roundedString(Math.max(0.0, health)),
                "maxhp", roundedString(maxHealth));
        return Msg.parse(cfg.messages().get("bossbar.title",
                "<gradient:#07B9FB:#0F68FF><b>Le Core</b></gradient> <dark_gray>|</dark_gray> "
                + "<white>%hp%<gray>/<white>%maxhp% <dark_gray>|</dark_gray> <white>%time%"), ph);
    }

    private long remainingSeconds() {
        long elapsed = System.currentTimeMillis() - startedAtMillis;
        long left = (durationMillis - elapsed) / 1000L;
        return Math.max(0L, left);
    }

    private static String roundedString(double d) {
        return Long.toString(Math.round(d));
    }

    private static String islandName(Island island) {
        String name = island.getName();
        if (name != null && !name.isBlank()) return name;
        SuperiorPlayer owner = island.getOwner();
        return owner != null && owner.getName() != null ? owner.getName() : "Island";
    }

    // ------------------------------------------------------------------
    // Public getters (for /dtc status)
    // ------------------------------------------------------------------

    public double getHealth() { return health; }
    public double getMaxHealth() { return maxHealth; }
    public long getRemainingSeconds() { return remainingSeconds(); }
    public int getParticipatingIslands() { return statsByIsland.size(); }
    public boolean isEnded() { return ended.get(); }

    /** Read-heavy sorted snapshot (PAPI, /dtc status). Cached and re-sorted only when damage lands. */
    public List<IslandStats> snapshotByRank() {
        List<IslandStats> cached = rankingCache;
        if (cached != null) return cached;
        List<IslandStats> list = new ArrayList<>(statsByIsland.values());
        list.sort(Comparator.comparingDouble(IslandStats::total).reversed());
        Map<UUID, Integer> index = new java.util.HashMap<>(list.size() * 2);
        for (int i = 0; i < list.size(); i++) index.put(list.get(i).island().getUniqueId(), i + 1);
        rankIndexCache = index; // benign race - worst case is a duplicate build
        rankingCache = list;
        return list;
    }

    /** O(1) rank lookup. Returns -1 if the island hasn't dealt damage. */
    public int rankOf(Island island) {
        if (island == null) return -1;
        Map<UUID, Integer> index = rankIndexCache;
        if (index == null) { snapshotByRank(); index = rankIndexCache; }
        Integer r = index == null ? null : index.get(island.getUniqueId());
        return r == null ? -1 : r;
    }

    /** @return the stats bucket for {@code island}, or null if the island hasn't dealt damage yet. */
    public IslandStats statsFor(Island island) {
        if (island == null) return null;
        return statsByIsland.get(island.getUniqueId());
    }

    private enum EndReason { VICTORY, TIMEOUT, MANUAL_FINISH }
}
