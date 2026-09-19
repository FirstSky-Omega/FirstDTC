package fr.first.firstdtc.config;

import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.logging.Level;

/**
 * Snapshot of config.yml + messages.yml. Immutable after {@link #reload}, so
 * event threads can read from it without synchronising.
 *
 * <p>Layout mirrors the YAML sections:
 * <ul>
 *   <li>{@code game.*}     -> duration, damage-per-block, health</li>
 *   <li>{@code filters.*}  -> worlds, require-own-island, allowed-blocks</li>
 *   <li>{@code oneblock.*} -> only-oneblock, offset-x/y/z</li>
 *   <li>{@code bossbar.*}  -> enabled, color, overlay (title in messages.yml)</li>
 *   <li>{@code hooks.*}    -> spawn, on-start, on-end</li>
 *   <li>{@code rewards.*}  -> general, ranks</li>
 * </ul>
 * Messages come from messages.yml and are accessed via {@link Messages#get}.
 */
public final class PluginConfig {

    private final JavaPlugin plugin;
    private final Messages messages = new Messages();

    // game
    private int durationSeconds;
    private double damagePerBlock;
    private double health;

    // filters
    private Set<String> worlds;
    private boolean requireOwnIsland;
    private Set<Material> allowedBlocks;

    // oneblock
    private boolean onlyOneBlock;
    private int oneBlockOffsetX;
    private int oneBlockOffsetY;
    private int oneBlockOffsetZ;

    // bossbar (title is in messages.yml)
    private boolean bossbarEnabled;
    private BossBar.Color bossColor;
    private BossBar.Overlay bossOverlay;

    // rewards
    private List<String> rewardsGeneral;
    private Map<Integer, List<String>> rewardsByRank;

    // debug
    private boolean debug;

    // hooks
    private String hookSpawnWorld;
    private int hookSpawnX;
    private int hookSpawnY;
    private int hookSpawnZ;
    private List<String> hookOnStart;
    private List<String> hookOnEnd;

    public PluginConfig(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        // Fusion récursive : chaque clé packagée manquante côté user est ajoutée
        // au fichier sur disque avant qu'on lise. Les valeurs éditées par l'user
        // sont respectées, seules les clés absentes bougent.
        mergeMissingDefaults(plugin, "config.yml");
        plugin.reloadConfig();
        FileConfiguration cfg = plugin.getConfig();

        // ---- game ----
        this.durationSeconds = Math.max(10, cfg.getInt("game.duration-seconds", 600));
        this.damagePerBlock = Math.max(0.0d, cfg.getDouble("game.damage-per-block", 1.0d));
        this.health = Math.max(1.0d, cfg.getDouble("game.health", 5000.0d));

        // ---- filters ----
        Set<String> worlds = new HashSet<>();
        for (String w : cfg.getStringList("filters.worlds")) {
            if (w != null && !w.isBlank()) worlds.add(w);
        }
        this.worlds = Collections.unmodifiableSet(worlds);

        this.requireOwnIsland = cfg.getBoolean("filters.require-own-island", true);

        Set<Material> mats = EnumSet.noneOf(Material.class);
        for (String raw : cfg.getStringList("filters.allowed-blocks")) {
            if (raw == null || raw.isBlank()) continue;
            Material m = Material.matchMaterial(raw);
            if (m == null || !m.isBlock()) {
                plugin.getLogger().warning("Matériau inconnu dans filters.allowed-blocks : " + raw);
                continue;
            }
            mats.add(m);
        }
        this.allowedBlocks = Collections.unmodifiableSet(mats);

        // ---- oneblock ----
        this.onlyOneBlock = cfg.getBoolean("oneblock.only-oneblock", true);
        this.oneBlockOffsetX = cfg.getInt("oneblock.offset-x", 0);
        this.oneBlockOffsetY = cfg.getInt("oneblock.offset-y", -1);
        this.oneBlockOffsetZ = cfg.getInt("oneblock.offset-z", 0);

        // ---- bossbar ----
        this.bossbarEnabled = cfg.getBoolean("bossbar.enabled", true);
        this.bossColor = parseEnum(BossBar.Color.class, cfg.getString("bossbar.color", "RED"),
                BossBar.Color.RED);
        this.bossOverlay = parseEnum(BossBar.Overlay.class, cfg.getString("bossbar.overlay", "PROGRESS"),
                BossBar.Overlay.PROGRESS);

        // ---- rewards ----
        this.rewardsGeneral = List.copyOf(cfg.getStringList("rewards.general"));
        Map<Integer, List<String>> ranks = new TreeMap<>();
        ConfigurationSection ranksSec = cfg.getConfigurationSection("rewards.ranks");
        if (ranksSec != null) {
            for (String key : ranksSec.getKeys(false)) {
                Integer rank = parseInt(key);
                if (rank == null || rank <= 0) {
                    plugin.getLogger().warning("Clé de rang invalide dans rewards.ranks : " + key);
                    continue;
                }
                List<String> cmds = ranksSec.getStringList(key);
                if (!cmds.isEmpty()) ranks.put(rank, List.copyOf(cmds));
            }
        }
        this.rewardsByRank = Collections.unmodifiableMap(ranks);

        // ---- debug ----
        this.debug = cfg.getBoolean("debug", false);

        // ---- hooks ----
        this.hookSpawnWorld = cfg.getString("hooks.spawn.world", "");
        this.hookSpawnX = cfg.getInt("hooks.spawn.x", 0);
        this.hookSpawnY = cfg.getInt("hooks.spawn.y", 100);
        this.hookSpawnZ = cfg.getInt("hooks.spawn.z", 0);
        this.hookOnStart = List.copyOf(cfg.getStringList("hooks.on-start"));
        this.hookOnEnd = List.copyOf(cfg.getStringList("hooks.on-end"));

        // ---- messages.yml (separate file, keys are dot-paths) ----
        messages.reload(plugin);
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /**
     * Fusion récursive : lit le fichier utilisateur + les valeurs packagées
     * dans le JAR (mêmes {@code fileName}), et ajoute UNIQUEMENT les clés
     * absentes côté user, sans toucher aux valeurs qu'il a déjà éditées ni
     * réordonner ses commentaires. Sauvegarde le fichier seulement si au
     * moins une clé a été ajoutée, pour éviter les I/O inutiles.
     *
     * <p>Détails :
     * <ul>
     *   <li>{@code defaults.getKeys(true)} donne TOUS les chemins (feuilles +
     *       sections). On filtre les sections avec {@code isConfigurationSection}
     *       pour ne pas écraser des sous-arbres user en les remplaçant par la
     *       section défaut vide.</li>
     *   <li>{@code contains(path, true)} : le {@code true} force Bukkit à ne
     *       regarder QUE les valeurs posées sur ce config, pas ses defaults
     *       (qui sont vides ici mais c'est plus sûr).</li>
     *   <li>Sur Paper récent, on recopie aussi les commentaires attachés à
     *       chaque nouvelle clé pour que le fichier final reste lisible. Sur
     *       du Spigot pur ou une vieille Paper, on ignore silencieusement.</li>
     * </ul>
     *
     * @return true si le fichier a été modifié
     */
    static boolean mergeMissingDefaults(JavaPlugin plugin, String fileName) {
        File file = new File(plugin.getDataFolder(), fileName);
        if (!file.exists()) {
            // Première install : on écrit le fichier packagé tel quel et on
            // s'arrête là - aucune fusion à faire, rien n'est user-édité.
            try {
                plugin.saveResource(fileName, false);
            } catch (IllegalArgumentException e) {
                // Ressource pas dans le JAR - laisse tomber.
                return false;
            }
            return true;
        }

        YamlConfiguration user = YamlConfiguration.loadConfiguration(file);
        YamlConfiguration defaults;
        try (InputStream in = plugin.getResource(fileName)) {
            if (in == null) return false;
            defaults = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING,
                    "Impossible de lire les defaults packagés de " + fileName, e);
            return false;
        }

        boolean modified = false;
        for (String path : defaults.getKeys(true)) {
            if (defaults.isConfigurationSection(path)) continue; // on ne touche qu'aux feuilles
            if (user.contains(path, true)) continue;             // user a déjà une valeur

            user.set(path, defaults.get(path));
            copyComments(defaults, user, path, plugin);
            modified = true;
        }

        if (modified) {
            try {
                user.save(file);
                plugin.getLogger().info("Clés manquantes ajoutées à " + fileName + ".");
            } catch (IOException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Impossible de sauvegarder " + fileName + " après fusion.", e);
            }
        }
        return modified;
    }

    /** Best-effort copie des commentaires attachés à {@code path} (Paper récent uniquement). */
    private static void copyComments(YamlConfiguration from, YamlConfiguration to,
                                     String path, JavaPlugin plugin) {
        try {
            List<String> block = from.getComments(path);
            if (block != null && !block.isEmpty()) to.setComments(path, block);
            List<String> inline = from.getInlineComments(path);
            if (inline != null && !inline.isEmpty()) to.setInlineComments(path, inline);
        } catch (NoSuchMethodError | UnsupportedOperationException ignored) {
            // Vieille API sans support commentaires - tant pis, on n'échoue pas.
        } catch (Throwable t) {
            plugin.getLogger().log(Level.FINE, "Copie de commentaires échouée pour " + path, t);
        }
    }

    private static Integer parseInt(String s) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return null; }
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value, E fallback) {
        if (value == null) return fallback;
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    // ------------------------------------------------------------------
    // getters
    // ------------------------------------------------------------------

    public int getDurationSeconds()   { return durationSeconds; }
    public double getDamagePerBlock() { return damagePerBlock; }
    public double getHealth()         { return health; }

    public Set<String> getWorlds()            { return worlds; }
    public boolean isRequireOwnIsland()       { return requireOwnIsland; }
    public Set<Material> getAllowedBlocks()   { return allowedBlocks; }

    public boolean isOnlyOneBlock()   { return onlyOneBlock; }
    public int getOneBlockOffsetX()   { return oneBlockOffsetX; }
    public int getOneBlockOffsetY()   { return oneBlockOffsetY; }
    public int getOneBlockOffsetZ()   { return oneBlockOffsetZ; }

    public boolean isBossbarEnabled()       { return bossbarEnabled; }
    public BossBar.Color getBossColor()     { return bossColor; }
    public BossBar.Overlay getBossOverlay() { return bossOverlay; }

    public List<String> getRewardsGeneral()          { return rewardsGeneral; }
    public Map<Integer, List<String>> getRewardsByRank() { return rewardsByRank; }

    public boolean isDebug() { return debug; }

    public String getHookSpawnWorld() { return hookSpawnWorld; }
    public int getHookSpawnX() { return hookSpawnX; }
    public int getHookSpawnY() { return hookSpawnY; }
    public int getHookSpawnZ() { return hookSpawnZ; }
    public List<String> getHookOnStart() { return hookOnStart; }
    public List<String> getHookOnEnd() { return hookOnEnd; }

    public Messages messages() { return messages; }

    /** Ordered list of rank thresholds that have configured rewards, ascending. */
    public List<Integer> configuredRanks() {
        return new ArrayList<>(rewardsByRank.keySet());
    }

    // ------------------------------------------------------------------
    // Nested: messages.yml handling
    // ------------------------------------------------------------------

    /**
     * Flat lookup over messages.yml with dot-path keys, e.g.
     * {@code messages.get("event.start", "<fallback>")}.
     */
    public static final class Messages {

        private final Map<String, String> map = new LinkedHashMap<>();

        void reload(JavaPlugin plugin) {
            map.clear();
            // Fusion récursive : ajoute au fichier utilisateur les clés packagées
            // qui manquent, sans écraser ce qu'il a édité, puis relit. Après ça
            // le user file contient toutes les clés — plus besoin de layer
            // "defaults en mémoire" (setDefaults) : chaque clé demandée existe.
            mergeMissingDefaults(plugin, "messages.yml");
            File file = new File(plugin.getDataFolder(), "messages.yml");
            YamlConfiguration user = YamlConfiguration.loadConfiguration(file);

            // Filet de sécurité : si l'user a supprimé une clé APRÈS la fusion
            // (ex: entre notre save et sa relecture manuelle), on garde quand
            // même les defaults du JAR comme couche de secours au runtime.
            try (InputStream in = plugin.getResource("messages.yml")) {
                if (in != null) {
                    YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                            new InputStreamReader(in, StandardCharsets.UTF_8));
                    user.setDefaults(defaults);
                }
            } catch (IOException e) {
                plugin.getLogger().log(Level.WARNING, "Impossible de lire les defaults packagés de messages.yml", e);
            }

            flatten("", user, map);
        }

        /** Depth-first flatten so {@code section.key} paths behave like {@link ConfigurationSection#getString}. */
        private static void flatten(String prefix, ConfigurationSection section, Map<String, String> out) {
            for (String key : section.getKeys(false)) {
                String path = prefix.isEmpty() ? key : prefix + "." + key;
                Object val = section.get(key);
                if (val instanceof ConfigurationSection sub) {
                    flatten(path, sub, out);
                } else if (val != null) {
                    out.put(path, val.toString());
                }
            }
        }

        public String get(String path) {
            return get(path, "");
        }

        public String get(String path, String fallback) {
            String raw = map.getOrDefault(path, fallback);
            if (raw == null || raw.isEmpty()) return "";
            // Un seul endroit pour définir le préfixe : la clé "prefix" est
            // substituée automatiquement dans TOUS les autres messages. Ça
            // évite de recopier le gradient partout et laisse l'admin le
            // changer une seule fois.
            if (raw.indexOf('%') >= 0 && raw.contains("%prefix%")) {
                String prefix = map.getOrDefault("prefix", "");
                raw = raw.replace("%prefix%", prefix);
            }
            return raw;
        }
    }
}
