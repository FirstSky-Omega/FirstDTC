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
        runHookCommands(cfg.getHookOnEnd());
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

            runHookCommands(cfg.getHookOnEnd());
            plugin.getGameManager().onGameEnded();
        });
    }

    /**
     * Fires each command line through the console with {@code %world%}, {@code %x%},
     * {@code %y%}, {@code %z%}, {@code %hp%}, {@code %maxhp%} and {@code %duration%}
     * substituted from the current config + game state. Runs on the global region
     * because {@code Bukkit.dispatchCommand} for a ConsoleSender requires it on Folia.
     */
    private void runHookCommands(List<String> commands) {
        if (commands == null || commands.isEmpty()) return;
        Map<String, String> ph = Map.of(
                "world",    cfg.getHookSpawnWorld(),
                "x",        Integer.toString(cfg.getHookSpawnX()),
                "y",        Integer.toString(cfg.getHookSpawnY()),
                "z",        Integer.toString(cfg.getHookSpawnZ()),
                "hp",       roundedString(Math.max(0.0, health)),
                "maxhp",    roundedString(maxHealth),
                "duration", Integer.toString(cfg.getDurationSeconds()));
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
        for (int i = 0; i < ranking.size(); i++) {
            int rank = i + 1;
            IslandStats stats = ranking.get(i).getValue();
            Island island = stats.island();
            Map<String, String> islandPh = Map.of(
                    "island", islandName(island),
                    "damage", roundedString(stats.total()),
                    "rank", Integer.toString(rank));

            List<IslandStats.MemberDamage> members = stats.topMembers();

            // Parse per recipient so PAPI can resolve player-specific placeholders
            // (%player_name%, %nexo_glyph_x%, %vault_eco_balance%, etc.) with the
            // right audience. It's a bit more work than caching one Component set
            // but keeps the message truly personal.
            for (SuperiorPlayer member : island.getIslandMembers(true)) {
                Player online = member.asPlayer();
                if (online == null || !online.isOnline()) continue;

                online.sendMessage(Msg.parse(online,
                        cfg.messages().get("summary.header",
                                "%prefix%<white>Résumé des dégâts <gray>-</gray> <white>%island%"),
                        islandPh));
                for (IslandStats.MemberDamage md : members) {
                    online.sendMessage(Msg.parse(online,
                            cfg.messages().get("summary.line",
                                    "<gray> - <white>%player%<dark_gray>:</dark_gray> <white>%damage% <gray>dégâts"),
                            Map.of("player", md.name(), "damage", roundedString(md.damage()))));
                }
                online.sendMessage(Msg.parse(online,
                        cfg.messages().get("summary.total",
                                "%prefix%<gray>Total île <dark_gray>|</dark_gray> <white>%damage% <gray>dégâts "
                                + "<dark_gray>|</dark_gray> <gray>classement <white>#%rank%"),
                        islandPh));
            }
        }
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
