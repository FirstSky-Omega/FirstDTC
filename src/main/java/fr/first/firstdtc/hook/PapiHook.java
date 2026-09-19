package fr.first.firstdtc.hook;

import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;

/**
 * Safe wrapper around PlaceholderAPI. Calls become no-ops if PAPI isn't loaded,
 * so callers can invoke {@link #apply} unconditionally without null checks.
 *
 * PAPI is where Nexo exposes its {@code %nexo_glyph_<name>%}-style placeholders,
 * so running text through PAPI before MiniMessage is what gives us Nexo glyphs
 * in every message the plugin sends, without a direct Nexo dependency.
 */
public final class PapiHook {

    private final boolean enabled;

    public PapiHook(Plugin plugin) {
        this.enabled = plugin.getServer().getPluginManager().getPlugin("PlaceholderAPI") != null;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String apply(OfflinePlayer audience, String raw) {
        if (!enabled || raw == null || raw.isEmpty()) return raw;
        return PlaceholderAPI.setPlaceholders(audience, raw);
    }
}
