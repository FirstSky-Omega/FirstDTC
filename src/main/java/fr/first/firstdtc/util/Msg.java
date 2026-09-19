package fr.first.firstdtc.util;

import fr.first.firstdtc.hook.NexoHook;
import fr.first.firstdtc.hook.PapiHook;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.OfflinePlayer;

import java.util.Map;

/**
 * Text pipeline used everywhere the plugin renders a message.
 *
 * <p>Order of operations, for every {@code parse} call:
 * <ol>
 *   <li>Substitute our own {@code %placeholder%} tokens (the ones the caller
 *       passed in the map, e.g. {@code %hp%}, {@code %damage%}, ...).</li>
 *   <li>Run PlaceholderAPI on the resulting string (only if PAPI is loaded).
 *       This is what makes {@code %player_name%}, {@code %vault_eco_balance%},
 *       {@code %nexo_glyph_arrow%} etc. work inside any of our config messages.</li>
 *   <li>Feed the string to MiniMessage. When Nexo is loaded, we use Nexo's own
 *       MiniMessage instance so its native tags like {@code <glyph:arrow>} and
 *       {@code <shift:-5>} also render.</li>
 * </ol>
 */
public final class Msg {

    private static PapiHook papi;
    private static NexoHook nexo;

    private Msg() {}

    /**
     * Wire up the shared hooks once at startup. Must be called before anything
     * else calls {@link #parse}.
     */
    public static void init(PapiHook papi, NexoHook nexo) {
        Msg.papi = papi;
        Msg.nexo = nexo;
    }

    // ------------------------------------------------------------------
    // public entry points
    // ------------------------------------------------------------------

    /** Global (audience-less) parse: no per-player PAPI expansion. */
    public static Component parse(String raw) {
        return parse(null, raw, Map.of());
    }

    /** Global parse with local {@code %placeholder%} substitution. */
    public static Component parse(String raw, Map<String, String> placeholders) {
        return parse(null, raw, placeholders);
    }

    /** Personalised parse: PAPI can then resolve {@code %player_*%} tokens. */
    public static Component parse(OfflinePlayer audience, String raw, Map<String, String> placeholders) {
        if (raw == null || raw.isEmpty()) return Component.empty();

        String s = applyLocal(raw, placeholders);
        if (papi != null) s = papi.apply(audience, s);
        return miniMessage().deserialize(s);
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static String applyLocal(String raw, Map<String, String> placeholders) {
        if (placeholders == null || placeholders.isEmpty()) return raw;
        String out = raw;
        for (Map.Entry<String, String> e : placeholders.entrySet()) {
            out = out.replace("%" + e.getKey() + "%", e.getValue());
        }
        return out;
    }

    private static MiniMessage miniMessage() {
        // nexo may be null before init() (e.g. tests) - degrade to plain MiniMessage.
        return nexo != null ? nexo.miniMessage() : MiniMessage.miniMessage();
    }

    public static String formatDuration(long seconds) {
        if (seconds < 0) seconds = 0;
        long m = seconds / 60L;
        long s = seconds % 60L;
        return String.format("%02d:%02d", m, s);
    }
}
