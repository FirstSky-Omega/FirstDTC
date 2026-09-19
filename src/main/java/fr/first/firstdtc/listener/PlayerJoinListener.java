package fr.first.firstdtc.listener;

import fr.first.firstdtc.FirstDtcPlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public final class PlayerJoinListener implements Listener {

    private final FirstDtcPlugin plugin;

    public PlayerJoinListener(FirstDtcPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        plugin.getGameManager().current().ifPresent(g -> g.showTo(event.getPlayer()));
    }
}
