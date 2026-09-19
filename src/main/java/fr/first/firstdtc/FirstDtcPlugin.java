package fr.first.firstdtc;

import fr.first.firstdtc.command.FirstDtcCommand;
import fr.first.firstdtc.config.PluginConfig;
import fr.first.firstdtc.game.GameManager;
import fr.first.firstdtc.game.RewardService;
import fr.first.firstdtc.hook.NexoHook;
import fr.first.firstdtc.hook.OneBlockHook;
import fr.first.firstdtc.hook.PapiHook;
import fr.first.firstdtc.listener.BlockBreakListener;
import fr.first.firstdtc.listener.PlayerJoinListener;
import fr.first.firstdtc.placeholder.FirstDtcExpansion;
import fr.first.firstdtc.util.FoliaScheduler;
import fr.first.firstdtc.util.Msg;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class FirstDtcPlugin extends JavaPlugin {

    private PluginConfig pluginConfig;
    private FoliaScheduler scheduler;
    private GameManager gameManager;
    private RewardService rewardService;
    private PapiHook papiHook;
    private NexoHook nexoHook;
    private OneBlockHook oneBlockHook;
    private FirstDtcExpansion papiExpansion;
    private FirstDtcExpansion papiExpansionAlias;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        // SuperiorSkyblock2 is a soft-depend in plugin.yml (see the note there
        // about hot-reload + LegacyPluginLoadingStrategy). We check ourselves
        // that it's actually present AND enabled AND the static API instance
        // is wired before we touch anything that would NPE on the listener.
        org.bukkit.plugin.Plugin ssb = getServer().getPluginManager().getPlugin("SuperiorSkyblock2");
        if (ssb == null || !ssb.isEnabled()
                || com.bgsoftware.superiorskyblock.api.SuperiorSkyblockAPI.getSuperiorSkyblock() == null) {
            getLogger().severe("SuperiorSkyblock2 est requis mais n'est pas "
                    + (ssb == null ? "installé" : "complètement activé") + ". Désactivation de firstdtc.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Hooks are resolved BEFORE the config loads, because loading the config
        // eagerly parses messages that go through the MiniMessage pipeline.
        this.papiHook = new PapiHook(this);
        this.nexoHook = new NexoHook(this);
        this.oneBlockHook = new OneBlockHook(this);
        Msg.init(papiHook, nexoHook);

        this.pluginConfig = new PluginConfig(this);
        this.scheduler = new FoliaScheduler(this);
        this.rewardService = new RewardService(this);
        this.gameManager = new GameManager(this);

        getServer().getPluginManager().registerEvents(new BlockBreakListener(this), this);
        getServer().getPluginManager().registerEvents(new PlayerJoinListener(this), this);
        // Explicit log so admins can confirm the listener is alive - the plugin
        // was silently absent from event dispatch on more than one report.
        getLogger().info("BlockBreakListener enregistré (priorité HIGHEST, ignoreCancelled=false).");

        PluginCommand cmd = getCommand("firstdtc");
        if (cmd != null) {
            FirstDtcCommand handler = new FirstDtcCommand(this);
            cmd.setExecutor(handler);
            cmd.setTabCompleter(handler);
        }

        if (papiHook.isEnabled()) {
            // Register both the full identifier and the short alias so users can
            // type either %firstdtc_hp% or %dtc_hp%.
            this.papiExpansion = new FirstDtcExpansion(this, "firstdtc");
            this.papiExpansionAlias = new FirstDtcExpansion(this, "dtc");
            boolean a = papiExpansion.register();
            boolean b = papiExpansionAlias.register();
            if (a && b) {
                getLogger().info("Expansion PlaceholderAPI enregistrée : %firstdtc_*% et %dtc_*%.");
            } else {
                getLogger().warning("Enregistrement PlaceholderAPI incomplet (principal=" + a + ", alias=" + b + ").");
            }
        }

        getLogger().info("firstdtc activé (Folia-safe, PAPI="
                + papiHook.isEnabled() + ", Nexo=" + nexoHook.isEnabled()
                + ", SSBOneBlock=" + oneBlockHook.isEnabled() + ").");
    }

    @Override
    public void onDisable() {
        if (gameManager != null) gameManager.stop();
        if (papiExpansion != null) {
            try { papiExpansion.unregister(); } catch (Throwable ignored) {}
        }
        if (papiExpansionAlias != null) {
            try { papiExpansionAlias.unregister(); } catch (Throwable ignored) {}
        }
    }

    public PluginConfig getPluginConfig()  { return pluginConfig; }
    public FoliaScheduler getScheduler()   { return scheduler; }
    public GameManager getGameManager()    { return gameManager; }
    public RewardService getRewardService(){ return rewardService; }
    public PapiHook getPapiHook()         { return papiHook; }
    public NexoHook getNexoHook()         { return nexoHook; }
    public OneBlockHook getOneBlockHook() { return oneBlockHook; }
}
