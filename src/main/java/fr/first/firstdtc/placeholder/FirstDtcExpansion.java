package fr.first.firstdtc.placeholder;

import com.bgsoftware.superiorskyblock.api.SuperiorSkyblockAPI;
import com.bgsoftware.superiorskyblock.api.island.Island;
import com.bgsoftware.superiorskyblock.api.wrappers.SuperiorPlayer;
import fr.first.firstdtc.FirstDtcPlugin;
import fr.first.firstdtc.game.CoreGame;
import fr.first.firstdtc.game.IslandStats;
import fr.first.firstdtc.util.Msg;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;

/**
 * PlaceholderAPI expansion for firstdtc.
 *
 * <p>All placeholders start with {@code %firstdtc_} (alias {@code %dtc_}).
 *
 * <h3>Global (no player context needed)</h3>
 * <ul>
 *   <li>{@code %dtc_running%}          - {@code true} / {@code false}</li>
 *   <li>{@code %dtc_hp%}               - current core HP (rounded)</li>
 *   <li>{@code %dtc_maxhp%}            - starting core HP (rounded)</li>
 *   <li>{@code %dtc_hp_percent%}       - remaining HP as {@code NN%}</li>
 *   <li>{@code %dtc_time%}             - remaining time formatted {@code mm:ss}</li>
 *   <li>{@code %dtc_time_seconds%}     - remaining time in seconds</li>
 *   <li>{@code %dtc_islands%}          - participating islands count</li>
 *   <li>{@code %dtc_top_<N>_island%}   - name of the Nth top island</li>
 *   <li>{@code %dtc_top_<N>_damage%}   - Nth top island damage (rounded)</li>
 *   <li>{@code %dtc_top_<N>_leader%}   - Nth top island leader name</li>
 * </ul>
 *
 * <h3>Per-player (requires a player context)</h3>
 * <ul>
 *   <li>{@code %dtc_my_damage%}        - this player's personal damage</li>
 *   <li>{@code %dtc_my_island%}        - this player's island name</li>
 *   <li>{@code %dtc_my_island_damage%} - this player's island total damage</li>
 *   <li>{@code %dtc_my_island_rank%}   - this player's island rank (or {@code -})</li>
 * </ul>
 */
public final class FirstDtcExpansion extends PlaceholderExpansion {

    private final FirstDtcPlugin plugin;
    private final String identifier;

    public FirstDtcExpansion(FirstDtcPlugin plugin) {
        this(plugin, "firstdtc");
    }

    /**
     * @param identifier the PAPI identifier ({@code firstdtc} or the
     *                   short alias {@code dtc}) - two instances are registered
     *                   so both forms of the placeholder resolve.
     */
    public FirstDtcExpansion(FirstDtcPlugin plugin, String identifier) {
        this.plugin = plugin;
        this.identifier = identifier;
    }

    @Override public @NotNull String getIdentifier() { return identifier; }
    @Override public @NotNull String getAuthor()     { return "First"; }
    @Override public @NotNull String getVersion()    { return plugin.getDescription().getVersion(); }
    @Override public boolean persist()               { return true; }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        String key = params.toLowerCase(Locale.ROOT);
        CoreGame game = plugin.getGameManager().current().orElse(null);

        // Constants that never depend on game state.
        if (key.equals("running")) return Boolean.toString(game != null && !game.isEnded());

        // Static placeholders that should still return sensible values when
        // no run is active - callers can decorate them however they like.
        if (game == null) return switch (key) {
            case "hp", "maxhp", "time_seconds", "islands" -> "0";
            case "time"                                    -> "00:00";
            case "hp_percent"                              -> "0%";
            default                                        -> handleOfflineMy(key, player);
        };

        return switch (key) {
            case "hp"           -> Long.toString(Math.round(Math.max(0.0, game.getHealth())));
            case "maxhp"        -> Long.toString(Math.round(game.getMaxHealth()));
            case "hp_percent"   -> pct(game.getHealth(), game.getMaxHealth());
            case "time"         -> Msg.formatDuration(game.getRemainingSeconds());
            case "time_seconds" -> Long.toString(game.getRemainingSeconds());
            case "islands"      -> Integer.toString(game.getParticipatingIslands());
            default             -> lookupComposite(key, game, player);
        };
    }

    // ------------------------------------------------------------------
    // composite placeholders
    // ------------------------------------------------------------------

    private @Nullable String lookupComposite(String key, CoreGame game, OfflinePlayer player) {
        // %firstdtc_top_<N>_(island|damage|leader)%
        if (key.startsWith("top_")) return handleTop(key, game);
        if (key.startsWith("my_"))  return handleMy(key, game, player);
        return null;
    }

    private String handleTop(String key, CoreGame game) {
        // Format: top_<n>_<field>
        String[] parts = key.split("_", 3);
        if (parts.length < 3) return null;
        int n;
        try { n = Integer.parseInt(parts[1]); } catch (NumberFormatException e) { return null; }
        if (n <= 0) return null;

        List<IslandStats> ranked = game.snapshotByRank();
        if (n > ranked.size()) return "";
        IslandStats stats = ranked.get(n - 1);
        Island island = stats.island();
        return switch (parts[2]) {
            case "island" -> islandName(island);
            case "damage" -> Long.toString(Math.round(stats.total()));
            case "leader" -> {
                SuperiorPlayer owner = island.getOwner();
                yield owner != null && owner.getName() != null ? owner.getName() : "?";
            }
            default -> null;
        };
    }

    private @Nullable String handleMy(String key, CoreGame game, OfflinePlayer player) {
        if (player == null) return "";
        SuperiorPlayer sp = SuperiorSkyblockAPI.getPlayer(player.getUniqueId());
        if (sp == null) return "";
        Island island = sp.getIsland();
        if (island == null) return "";

        return switch (key) {
            case "my_island"        -> islandName(island);
            case "my_island_damage" -> {
                IslandStats s = game.statsFor(island);
                yield s == null ? "0" : Long.toString(Math.round(s.total()));
            }
            case "my_damage" -> {
                IslandStats s = game.statsFor(island);
                yield s == null ? "0" : Long.toString(Math.round(s.damageFor(player.getUniqueId())));
            }
            case "my_island_rank" -> {
                int rank = rankOf(game, island);
                yield rank <= 0 ? "-" : Integer.toString(rank);
            }
            default -> null;
        };
    }

    /**
     * When there's no active game we still want per-player placeholders to
     * degrade gracefully rather than returning null (PAPI then shows the raw
     * placeholder text, which looks broken in a scoreboard/hologram).
     */
    private @Nullable String handleOfflineMy(String key, OfflinePlayer player) {
        if (!key.startsWith("my_")) return null;
        return switch (key) {
            case "my_island", "my_island_rank" -> "-";
            case "my_damage", "my_island_damage" -> "0";
            default -> null;
        };
    }

    private static int rankOf(CoreGame game, Island island) {
        List<IslandStats> ranked = game.snapshotByRank();
        for (int i = 0; i < ranked.size(); i++) {
            if (ranked.get(i).island().getUniqueId().equals(island.getUniqueId())) return i + 1;
        }
        return -1;
    }

    private static String islandName(Island island) {
        String name = island.getName();
        if (name != null && !name.isBlank()) return name;
        SuperiorPlayer owner = island.getOwner();
        return owner != null && owner.getName() != null ? owner.getName() : "Island";
    }

    private static String pct(double v, double max) {
        if (max <= 0) return "0%";
        return Math.round(Math.max(0.0, Math.min(1.0, v / max)) * 100.0) + "%";
    }
}
