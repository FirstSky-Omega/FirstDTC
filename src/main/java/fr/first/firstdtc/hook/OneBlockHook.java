package fr.first.firstdtc.hook;

import com.bgsoftware.superiorskyblock.api.SuperiorSkyblockAPI;
import com.bgsoftware.superiorskyblock.api.island.Island;
import com.bgsoftware.superiorskyblock.api.modules.PluginModule;
import org.bukkit.Location;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Optional bridge to SSBOneBlock (the SuperiorSkyblock2 module that
 * implements the actual OneBlock generator).
 *
 * <p>Important classloader nuance: SSBOneBlock is a SuperiorSkyblock2 <em>module</em>,
 * not a standalone Bukkit plugin. Its classes are loaded by SSB2's module
 * classloader, which is isolated from other plugins' classloaders. That means
 * a plain {@code Class.forName("com.bgsoftware.ssboneblock.utils.WorldUtils")}
 * from our plugin fails with {@code ClassNotFoundException} even when the
 * module is loaded and running.
 *
 * <p>The right route is to fetch the module instance through SSB2's
 * {@link com.bgsoftware.superiorskyblock.api.handlers.ModulesManager} and
 * borrow its classloader before resolving the class.
 *
 * <p>Once the class is in hand we cache a handle to its public static helper:
 *
 * <pre>public static Location getOneBlock(Island island)</pre>
 *
 * which returns exactly the location the module itself considers as the
 * OneBlock for {@code island}. Zero drift with the module's own math.
 */
public final class OneBlockHook {

    private static final String[] MODULE_NAMES = { "OneBlock", "SSBOneBlock" };
    private static final String WORLD_UTILS_CLASS = "com.bgsoftware.ssboneblock.utils.WorldUtils";
    private static final String GET_ONE_BLOCK_METHOD = "getOneBlock";

    private final Plugin plugin;
    private volatile boolean enabled;
    private volatile Method getOneBlockMethod;

    /**
     * Per-island cache of the resolved OneBlock location. Skipping the reflection
     * hop on every BlockBreakEvent saves ~microseconds per break but really adds
     * up on a server where dozens of players are mining. Never invalidated - the
     * OneBlock offset is fixed by the module config, position is stable for the
     * lifetime of the island.
     */
    private final ConcurrentHashMap<UUID, Location> positionCache = new ConcurrentHashMap<>();

    public OneBlockHook(Plugin plugin) {
        this.plugin = plugin;
        tryResolve();
    }

    /** Public retry: the module may enable slightly after us on some hot reloads. */
    public boolean tryResolve() {
        if (enabled) return true;
        try {
            PluginModule module = findModule();
            if (module == null) {
                plugin.getLogger().info("Module SSBOneBlock non détecté via ModulesManager — "
                        + "repli sur le calcul d'offset configuré.");
                return false;
            }
            ClassLoader moduleCl = module.getClass().getClassLoader();
            Class<?> worldUtils = Class.forName(WORLD_UTILS_CLASS, true, moduleCl);
            this.getOneBlockMethod = worldUtils.getMethod(GET_ONE_BLOCK_METHOD, Island.class);
            this.enabled = true;
            plugin.getLogger().info("Module SSBOneBlock détecté (" + module.getName()
                    + ") — positions OneBlock lues directement depuis le module.");
            return true;
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING,
                    "SSBOneBlock atteignable via ModulesManager mais la réflexion a échoué — "
                    + "repli sur le calcul d'offset configuré.", t);
            return false;
        }
    }

    private PluginModule findModule() {
        var mm = SuperiorSkyblockAPI.getModules();
        if (mm == null) return null;
        for (String name : MODULE_NAMES) {
            PluginModule m = mm.getModule(name);
            if (m != null) return m;
        }
        // Fallback: scan all modules and match by class package prefix, in case
        // the admin renamed the module folder.
        for (PluginModule m : mm.getModules()) {
            if (m.getClass().getName().startsWith("com.bgsoftware.ssboneblock")) return m;
        }
        return null;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * @return the OneBlock location for {@code island} exactly as the module
     *         computes it, or null if the hook is inactive or the call failed.
     *         Never throws.
     */
    public Location getOneBlock(Island island) {
        if (!enabled || getOneBlockMethod == null || island == null) return null;
        Location cached = positionCache.get(island.getUniqueId());
        if (cached != null) return cached;
        try {
            Object result = getOneBlockMethod.invoke(null, island);
            if (result instanceof Location loc) {
                positionCache.put(island.getUniqueId(), loc);
                return loc;
            }
            return null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Drop the position cache - use if an admin knowingly moved a OneBlock. */
    public void clearCache() {
        positionCache.clear();
    }
}
