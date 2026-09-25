package fr.first.firstdtc.command;

import com.bgsoftware.superiorskyblock.api.SuperiorSkyblockAPI;
import com.bgsoftware.superiorskyblock.api.island.Island;
import com.bgsoftware.superiorskyblock.api.wrappers.SuperiorPlayer;
import fr.first.firstdtc.FirstDtcPlugin;
import fr.first.firstdtc.config.PluginConfig;
import fr.first.firstdtc.game.CoreGame;
import fr.first.firstdtc.game.GameManager;
import fr.first.firstdtc.game.RewardService;
import fr.first.firstdtc.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Handler de la commande admin. Chaque message affiché passe par
 * {@code cfg.messages().get("command.…", <fallback FR>)} — 100% localisable
 * via messages.yml sans recompilation, avec un fallback FR "safe" côté code
 * pour qu'une clé manquante n'affiche jamais du vide.
 */
public final class FirstDtcCommand implements CommandExecutor, TabCompleter {

    /**
     * Descripteur d'une sous-commande.
     * @param usage    forme littérale (structure de la commande, sert au help + tab)
     * @param descKey  clé sous {@code command.help.desc.<key>} dans messages.yml
     * @param descFr   texte français par défaut si la clé n'est pas définie
     */
    private record HelpEntry(String usage, String descKey, String descFr) {}

    private static final List<HelpEntry> HELP = List.of(
            new HelpEntry("help",                             "help",           "Affiche cette aide"),
            new HelpEntry("start",                            "start",          "Lance une nouvelle partie Destroy the Core"),
            new HelpEntry("finish",                           "finish",         "Termine la partie maintenant EN distribuant les récompenses"),
            new HelpEntry("stop",                             "stop",           "Annule la partie en cours (sans récompenses)"),
            new HelpEntry("status",                           "status",         "Affiche PV / temps / îles participantes"),
            new HelpEntry("reload",                           "reload",         "Recharge config.yml + messages.yml"),
            new HelpEntry("reward general <joueur>",          "reward-general", "Teste rewards.general pour l'île du joueur"),
            new HelpEntry("reward rank <N> <joueur>",         "reward-rank",    "Teste rewards.ranks.<N> pour l'île du joueur"),
            new HelpEntry("reward all <joueur>",              "reward-all",     "Teste général + tous les rangs configurés")
    );

    private static final List<String> TOP_SUBS =
            List.of("help", "start", "finish", "stop", "status", "reload", "reward");
    private static final List<String> REWARD_SUBS =
            List.of("general", "rank", "all");

    private final FirstDtcPlugin plugin;

    public FirstDtcCommand(FirstDtcPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sendHelp(sender);
            return true;
        }

        PluginConfig cfg = plugin.getPluginConfig();
        GameManager gm = plugin.getGameManager();
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "start"  -> handleStart(sender, cfg, gm);
            case "finish" -> handleFinish(sender, cfg, gm);
            case "stop"   -> handleStop(sender, cfg, gm);
            case "status" -> handleStatus(sender, cfg, gm);
            case "reload" -> handleReload(sender, cfg);
            case "reward" -> handleReward(sender, cfg, Arrays.copyOfRange(args, 1, args.length));
            default       -> sendHelp(sender);
        }
        return true;
    }

    // ------------------------------------------------------------------
    // subcommand handlers
    // ------------------------------------------------------------------

    private void handleStart(CommandSender sender, PluginConfig cfg, GameManager gm) {
        if (!gm.start()) {
            sender.sendMessage(Msg.parse(cfg.messages().get("command.already-running",
                    "%prefix%<red>Un événement est déjà en cours.")));
        }
    }

    private void handleStop(CommandSender sender, PluginConfig cfg, GameManager gm) {
        if (!gm.stop()) {
            sender.sendMessage(Msg.parse(cfg.messages().get("command.not-running",
                    "%prefix%<red>Aucun événement en cours.")));
        }
    }

    private void handleFinish(CommandSender sender, PluginConfig cfg, GameManager gm) {
        if (!gm.finish()) {
            sender.sendMessage(Msg.parse(cfg.messages().get("command.not-running",
                    "%prefix%<red>Aucun événement en cours.")));
        }
    }

    private void handleStatus(CommandSender sender, PluginConfig cfg, GameManager gm) {
        CoreGame g = gm.current().orElse(null);
        if (g == null) {
            sender.sendMessage(Msg.parse(cfg.messages().get("command.status-idle",
                    "%prefix%<gray>Aucun événement en cours.")));
        } else {
            sender.sendMessage(Msg.parse(cfg.messages().get("command.status-running",
                    "%prefix%<white>%hp%<gray>/<white>%maxhp% <gray>PV - <white>%time% <gray>restants - <white>%islands% <gray>îles"),
                    Map.of(
                            "hp",      Long.toString(Math.round(Math.max(0.0, g.getHealth()))),
                            "maxhp",   Long.toString(Math.round(g.getMaxHealth())),
                            "time",    Msg.formatDuration(g.getRemainingSeconds()),
                            "islands", Integer.toString(g.getParticipatingIslands()))));
        }
    }

    private void handleReload(CommandSender sender, PluginConfig cfg) {
        cfg.reload();
        sender.sendMessage(Msg.parse(cfg.messages().get("command.reload-ok",
                "%prefix%<white>Config & messages rechargés.")));
    }

    private void handleReward(CommandSender sender, PluginConfig cfg, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Msg.parse(cfg.messages().get("command.reward.usage",
                    "%prefix%<red>Usage <gray>:</gray> <white>/firstdtc reward <general|rank <N>|all> <joueur>")));
            return;
        }
        String mode = args[0].toLowerCase(Locale.ROOT);
        RewardService rewards = plugin.getRewardService();

        switch (mode) {
            case "general" -> {
                Island island = resolveIsland(sender, cfg, args[1]);
                if (island == null) return;
                rewards.giveGeneral(island, 0, 0, true);
                confirm(sender, cfg, "command.reward.fired-general",
                        "%prefix%<white>rewards.general <gray>déclenché pour l'île <white>%island%<gray> (chef <white>%leader%<gray>).",
                        island, -1);
            }
            case "all" -> {
                Island island = resolveIsland(sender, cfg, args[1]);
                if (island == null) return;
                rewards.giveAll(island);
                confirm(sender, cfg, "command.reward.fired-all",
                        "%prefix%<white>rewards.general + tous les rangs <gray>déclenchés pour l'île <white>%island%<gray> (chef <white>%leader%<gray>).",
                        island, -1);
            }
            case "rank" -> {
                if (args.length < 3) {
                    sender.sendMessage(Msg.parse(cfg.messages().get("command.reward.usage-rank",
                            "%prefix%<red>Usage <gray>:</gray> <white>/firstdtc reward rank <N> <joueur>")));
                    return;
                }
                Integer rank = parseInt(args[1]);
                if (rank == null || rank <= 0) {
                    sender.sendMessage(Msg.parse(cfg.messages().get("command.reward.invalid-rank",
                            "%prefix%<red>Le rang doit être un entier positif, reçu <white>%rank%"),
                            Map.of("rank", args[1])));
                    return;
                }
                if (!cfg.getRewardsByRank().containsKey(rank)) {
                    sender.sendMessage(Msg.parse(cfg.messages().get("command.reward.no-rank-configured",
                            "%prefix%<red>Aucun <white>rewards.ranks.%rank%<red> configuré — rien à exécuter."),
                            Map.of("rank", Integer.toString(rank))));
                    return;
                }
                Island island = resolveIsland(sender, cfg, args[2]);
                if (island == null) return;
                rewards.giveRank(island, rank, 0, true);
                confirm(sender, cfg, "command.reward.fired-rank",
                        "%prefix%<white>rewards.ranks.%rank% <gray>déclenché pour l'île <white>%island%<gray> (chef <white>%leader%<gray>).",
                        island, rank);
            }
            default -> sender.sendMessage(Msg.parse(cfg.messages().get("command.reward.unknown-mode",
                    "%prefix%<red>Mode inconnu <white>%mode%<red>. Utilise <white>general<red>, <white>rank<red> ou <white>all<red>."),
                    Map.of("mode", mode)));
        }
    }

    // ------------------------------------------------------------------
    // help / helpers
    // ------------------------------------------------------------------

    private void sendHelp(CommandSender sender) {
        PluginConfig cfg = plugin.getPluginConfig();
        sender.sendMessage(Msg.parse(cfg.messages().get("command.help.header",
                "<gradient:#07B9FB:#0F68FF><b>=== Commandes firstdtc ===</b></gradient>")));
        String lineFormat = cfg.messages().get("command.help.line-format",
                "<white>/firstdtc %usage% <dark_gray>-</dark_gray> <gray>%desc%");
        for (HelpEntry e : HELP) {
            String desc = cfg.messages().get("command.help.desc." + e.descKey(), e.descFr());
            sender.sendMessage(Msg.parse(lineFormat, Map.of("usage", e.usage(), "desc", desc)));
        }
        sender.sendMessage(Msg.parse(cfg.messages().get("command.help.footer",
                "<dark_gray>Alias : <gray>/dtc, /destroythecore")));
    }

    private void confirm(CommandSender sender, PluginConfig cfg, String key, String fallback,
                         Island island, int rank) {
        Map<String, String> ph = new java.util.HashMap<>();
        ph.put("island", RewardService.islandName(island));
        ph.put("leader", leaderName(island));
        if (rank > 0) ph.put("rank", Integer.toString(rank));
        sender.sendMessage(Msg.parse(cfg.messages().get(key, fallback), ph));
    }

    /** Résout une île SSB depuis un pseudo, retourne null et affiche un message si échec. */
    private Island resolveIsland(CommandSender sender, PluginConfig cfg, String playerName) {
        // getOfflinePlayer(name) peut faire une requête HTTP bloquante vers l'API
        // Mojang si le joueur n'est pas en cache — on refuse plutôt.
        OfflinePlayer op = Bukkit.getOfflinePlayerIfCached(playerName);
        Map<String, String> ph = Map.of("player", playerName);
        if (op == null) {
            sender.sendMessage(Msg.parse(cfg.messages().get("command.player.unknown",
                    "%prefix%<red>Joueur inconnu ou jamais connecté <white>%player%"), ph));
            return null;
        }
        SuperiorPlayer sp = SuperiorSkyblockAPI.getPlayer(op.getUniqueId());
        if (sp == null) {
            sender.sendMessage(Msg.parse(cfg.messages().get("command.player.not-in-ssb",
                    "%prefix%<red>Le joueur <white>%player%<red> n'a jamais été vu par SuperiorSkyblock2."), ph));
            return null;
        }
        Island island = sp.getIsland();
        if (island == null) {
            sender.sendMessage(Msg.parse(cfg.messages().get("command.player.no-island",
                    "%prefix%<red>Le joueur <white>%player%<red> n'a pas d'île."), ph));
            return null;
        }
        return island;
    }

    private static String leaderName(Island island) {
        SuperiorPlayer owner = island.getOwner();
        return owner != null && owner.getName() != null ? owner.getName() : "?";
    }

    private static Integer parseInt(String s) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return null; }
    }

    // ------------------------------------------------------------------
    // tab completion
    // ------------------------------------------------------------------

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            return prefix(TOP_SUBS, args[0]);
        }
        if (args[0].equalsIgnoreCase("reward")) {
            if (args.length == 2) return prefix(REWARD_SUBS, args[1]);
            if (args.length == 3) {
                if (args[1].equalsIgnoreCase("rank")) {
                    List<String> ranks = plugin.getPluginConfig().configuredRanks().stream()
                            .map(String::valueOf).toList();
                    return prefix(ranks, args[2]);
                }
                return onlinePlayerNames(args[2]);
            }
            if (args.length == 4 && args[1].equalsIgnoreCase("rank")) {
                return onlinePlayerNames(args[3]);
            }
        }
        return Collections.emptyList();
    }

    private static List<String> prefix(List<String> pool, String typed) {
        String p = typed.toLowerCase(Locale.ROOT);
        return pool.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(p))
                .collect(Collectors.toList());
    }

    private static List<String> onlinePlayerNames(String typed) {
        String p = typed.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (org.bukkit.entity.Player pl : Bukkit.getOnlinePlayers()) {
            if (pl.getName().toLowerCase(Locale.ROOT).startsWith(p)) out.add(pl.getName());
        }
        return out;
    }
}
