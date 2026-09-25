package fr.first.firstdtc.game;

import com.bgsoftware.superiorskyblock.api.island.Island;
import com.bgsoftware.superiorskyblock.api.wrappers.SuperiorPlayer;
import fr.first.firstdtc.FirstDtcPlugin;
import fr.first.firstdtc.config.PluginConfig;
import fr.first.firstdtc.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Central reward payout. Owns two responsibilities:
 * <ul>
 *   <li>Building the placeholder map used by every reward template
 *       ({@code %leader%}, {@code %island%}, {@code %rank%}, {@code %damage%}).</li>
 *   <li>Dispatching each command from the global region so Folia's
 *       "console commands must run on the global region" invariant is respected.</li>
 * </ul>
 *
 * Both the end-of-game payout in {@link CoreGame} and the admin
 * {@code /firstdtc reward ...} test commands go through here, so there's
 * exactly one code path shared by production runs and admin testing.
 */
public final class RewardService {

    private final FirstDtcPlugin plugin;

    public RewardService(FirstDtcPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------
    // High-level payouts
    // ------------------------------------------------------------------

    /** Runs the {@code rewards.general} block for one island. */
    public void giveGeneral(Island island, int rank, double damage, boolean notifyLeader) {
        Map<String, String> ph = placeholders(island, rank, damage);
        dispatchAll(plugin.getPluginConfig().getRewardsGeneral(), ph);
        if (notifyLeader) notify(island, "rewards.general",
                "%prefix%<white>Votre île a reçu la récompense de participation.", ph);
    }

    /** Runs the {@code rewards.ranks.<rank>} block. No-op if none is configured for {@code rank}. */
    public void giveRank(Island island, int rank, double damage, boolean notifyLeader) {
        List<String> templates = plugin.getPluginConfig().getRewardsByRank().get(rank);
        if (templates == null || templates.isEmpty()) return;
        Map<String, String> ph = placeholders(island, rank, damage);
        dispatchAll(templates, ph);
        if (notifyLeader) notify(island, "rewards.ranked",
                "%prefix%<white>Votre île termine <b>#%rank%</b> et reçoit un bonus.", ph);
    }

    /**
     * Runs the general block AND every configured rank for {@code island}.
     * Used only by the admin {@code reward all} test command - real games
     * distribute exactly one rank per island via {@link #giveRank}.
     */
    public void giveAll(Island island) {
        giveGeneral(island, 0, 0, true);
        for (int rank : plugin.getPluginConfig().configuredRanks()) {
            giveRank(island, rank, 0, true);
        }
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    public Map<String, String> placeholders(Island island, int rank, double damage) {
        Map<String, String> ph = new HashMap<>();
        SuperiorPlayer owner = island.getOwner();
        ph.put("leader", owner != null && owner.getName() != null ? owner.getName() : "?");
        ph.put("island", islandName(island));
        ph.put("damage", Long.toString(Math.round(Math.max(0.0d, damage))));
        ph.put("rank", Integer.toString(rank));
        return ph;
    }

    private void notify(Island island, String key, String fallback, Map<String, String> ph) {
        SuperiorPlayer owner = island.getOwner();
        Player leaderOnline = owner == null ? null : owner.asPlayer();
        if (leaderOnline == null || !leaderOnline.isOnline()) return;
        PluginConfig cfg = plugin.getPluginConfig();
        leaderOnline.sendMessage(Msg.parse(leaderOnline, cfg.messages().get(key, fallback), ph));
    }

    private void dispatch(String template, Map<String, String> ph) {
        dispatchAll(List.of(template), ph);
    }

    void dispatchAll(List<String> templates, Map<String, String> ph) {
        if (templates.isEmpty()) return;
        List<String> cmds = new ArrayList<>(templates.size());
        for (String t : templates) {
            String cmd = t;
            for (Map.Entry<String, String> e : ph.entrySet()) {
                cmd = cmd.replace("%" + e.getKey() + "%", e.getValue());
            }
            cmds.add(cmd);
        }
        // Une seule tâche globale pour toute la liste — évite N allocations Folia.
        plugin.getScheduler().runGlobal(() -> {
            for (String cmd : cmds) {
                try {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
                } catch (Throwable t) {
                    plugin.getLogger().warning("Commande de récompense échouée : '" + cmd + "' - " + t.getMessage());
                }
            }
        });
    }

    public static String islandName(Island island) {
        String name = island.getName();
        if (name != null && !name.isBlank()) return name;
        SuperiorPlayer owner = island.getOwner();
        return owner != null && owner.getName() != null ? owner.getName() : "Island";
    }
}
