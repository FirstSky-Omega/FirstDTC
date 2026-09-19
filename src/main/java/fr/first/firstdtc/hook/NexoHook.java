package fr.first.firstdtc.hook;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.ArgumentQueue;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.logging.Level;

/**
 * Optional integration with Nexo (custom items / glyphs plugin).
 *
 * <p>Nexo is written in Kotlin, so any {@code object AdventureUtils { val MINI_MESSAGE = ... }}
 * declaration compiles down to a Java class with:
 * <ul>
 *   <li>a public static {@code INSTANCE} field of the same type as the class, and</li>
 *   <li>an instance method {@code getMINI_MESSAGE()} (Kotlin property getter).</li>
 * </ul>
 * A plain {@code AdventureUtils.MINI_MESSAGE} static-field lookup only works when
 * the property is marked {@code @JvmField}, which Nexo does not do for this one.
 *
 * <p>We therefore try each candidate access path in order: the Kotlin
 * {@code INSTANCE + getMINI_MESSAGE()} pair first, plain static field as a
 * fallback, across a couple of candidate class paths that Nexo has used
 * across releases.
 *
 * <p>If we ultimately can't reach it, we fall back to a plain MiniMessage.
 * PAPI-side glyph placeholders ({@code %nexo_glyph_<name>%}) still resolve
 * because PAPI runs before MiniMessage in the pipeline.
 */
public final class NexoHook {

    /** {@code <fqcn>#<field-or-property-name>}. */
    private static final String[] CANDIDATE_MM_PROPERTIES = {
            "com.nexomc.nexo.utils.AdventureUtils#MINI_MESSAGE",
            "com.nexomc.nexo.utils.MiniMessageUtils#MINI_MESSAGE",
            "com.nexomc.nexo.utils.AdventureUtils#MINIMESSAGE",
    };

    private static final String[] CANDIDATE_GLYPH_METHODS = {
            "com.nexomc.nexo.api.NexoGlyphs#glyphFromName",
            "com.nexomc.nexo.api.NexoGlyphs#glyphByName",
    };

    private final Plugin plugin;
    private final boolean enabled;
    private final MiniMessage miniMessage;

    public NexoHook(Plugin plugin) {
        this.plugin = plugin;
        this.enabled = plugin.getServer().getPluginManager().getPlugin("Nexo") != null;

        MiniMessage mm = null;
        if (enabled) {
            mm = tryGrabNexoMiniMessage();
            if (mm == null) mm = buildFallbackMiniMessage();
            if (mm == null) {
                plugin.getLogger().warning(
                        "Nexo détecté mais ses classes API sont inaccessibles. Repli sur MiniMessage "
                        + "standard. Les placeholders PAPI type %nexo_glyph_<name>% fonctionnent quand même.");
            } else {
                plugin.getLogger().info("Hook Nexo actif — les tags glyph sont résolus via Nexo.");
            }
        }
        this.miniMessage = mm != null ? mm : MiniMessage.miniMessage();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public MiniMessage miniMessage() {
        return miniMessage;
    }

    // ------------------------------------------------------------------
    // reflection helpers
    // ------------------------------------------------------------------

    private MiniMessage tryGrabNexoMiniMessage() {
        for (String path : CANDIDATE_MM_PROPERTIES) {
            String[] parts = path.split("#", 2);
            Class<?> clazz;
            try {
                clazz = Class.forName(parts[0]);
            } catch (ClassNotFoundException e) {
                continue;
            }

            // Kotlin `object`: INSTANCE + getXxx()
            MiniMessage viaKotlin = tryKotlinObjectAccess(clazz, parts[1]);
            if (viaKotlin != null) return viaKotlin;

            // Plain @JvmField static.
            MiniMessage viaStatic = tryStaticFieldAccess(clazz, parts[1]);
            if (viaStatic != null) return viaStatic;
        }
        return null;
    }

    private MiniMessage tryKotlinObjectAccess(Class<?> clazz, String propertyName) {
        try {
            Field instanceField = clazz.getDeclaredField("INSTANCE");
            instanceField.setAccessible(true);
            Object instance = instanceField.get(null);
            if (instance == null) return null;

            // Kotlin auto-generates get<Property>() with the first char upper-cased.
            String getterName = "get" + propertyName.substring(0, 1).toUpperCase() + propertyName.substring(1);
            Method getter = clazz.getMethod(getterName);
            Object value = getter.invoke(instance);
            return value instanceof MiniMessage m ? m : null;
        } catch (NoSuchFieldException | NoSuchMethodException e) {
            return null;
        } catch (Throwable t) {
            plugin.getLogger().log(Level.FINE, "Nexo Kotlin-object access failed for "
                    + clazz.getName() + "#" + propertyName, t);
            return null;
        }
    }

    private MiniMessage tryStaticFieldAccess(Class<?> clazz, String fieldName) {
        try {
            Field f = clazz.getDeclaredField(fieldName);
            f.setAccessible(true);
            Object value = f.get(null);
            return value instanceof MiniMessage m ? m : null;
        } catch (NoSuchFieldException e) {
            return null;
        } catch (Throwable t) {
            plugin.getLogger().log(Level.FINE, "Nexo static-field access failed for "
                    + clazz.getName() + "#" + fieldName, t);
            return null;
        }
    }

    /**
     * When we can't reach Nexo's ready-made MiniMessage, we try to build a
     * minimal {@code <glyph:name>} tag resolver against whatever glyph-lookup
     * static method Nexo exposes. Only rendered characters are supported this
     * way — advanced tags (shift, item, …) require the module's real
     * MiniMessage instance.
     */
    private MiniMessage buildFallbackMiniMessage() {
        for (String path : CANDIDATE_GLYPH_METHODS) {
            String[] parts = path.split("#", 2);
            try {
                Class<?> clazz = Class.forName(parts[0]);
                Method method = clazz.getMethod(parts[1], String.class);
                TagResolver glyph = TagResolver.resolver("glyph",
                        (ArgumentQueue args, net.kyori.adventure.text.minimessage.Context ctx) -> {
                            String name = args.popOr("glyph tag requires a name").value();
                            try {
                                Object glyphObj = method.invoke(null, name);
                                if (glyphObj == null) return Tag.selfClosingInserting(
                                        net.kyori.adventure.text.Component.empty());
                                String character = extractCharacter(glyphObj);
                                return Tag.selfClosingInserting(
                                        net.kyori.adventure.text.Component.text(character == null ? "" : character));
                            } catch (Throwable t) {
                                return Tag.selfClosingInserting(net.kyori.adventure.text.Component.empty());
                            }
                        });
                return MiniMessage.builder()
                        .tags(TagResolver.builder()
                                .resolver(TagResolver.standard())
                                .resolver(glyph)
                                .build())
                        .build();
            } catch (ClassNotFoundException | NoSuchMethodException ignored) {
                // Try next candidate.
            } catch (Throwable t) {
                plugin.getLogger().log(Level.FINE, "Nexo glyph lookup at " + path + " failed", t);
            }
        }
        return null;
    }

    /**
     * A Nexo Glyph object typically has a {@code character()} accessor (or
     * {@code getCharacter()} / {@code character} field) returning the private
     * unicode code point mapped to its texture. Try the common shapes.
     */
    private static String extractCharacter(Object glyph) throws Exception {
        for (String getter : new String[] { "character", "getCharacter" }) {
            try {
                Method m = glyph.getClass().getMethod(getter);
                Object v = m.invoke(glyph);
                if (v != null) return v.toString();
            } catch (NoSuchMethodException ignored) {
            }
        }
        try {
            Field f = glyph.getClass().getField("character");
            Object v = f.get(glyph);
            return v == null ? null : v.toString();
        } catch (NoSuchFieldException ignored) {
            return null;
        }
    }
}
