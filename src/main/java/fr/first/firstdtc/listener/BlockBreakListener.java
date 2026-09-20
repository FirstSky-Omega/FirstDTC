package fr.first.firstdtc.listener;

import com.bgsoftware.superiorskyblock.api.SuperiorSkyblockAPI;
import com.bgsoftware.superiorskyblock.api.island.Island;
import com.bgsoftware.superiorskyblock.api.world.Dimension;
import com.bgsoftware.superiorskyblock.api.wrappers.SuperiorPlayer;
import fr.first.firstdtc.FirstDtcPlugin;
import fr.first.firstdtc.config.PluginConfig;
import fr.first.firstdtc.hook.OneBlockHook;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class BlockBreakListener implements Listener {

    private final FirstDtcPlugin plugin;

    /**
     * Per-player "last counted break" fingerprint. Used to dedupe when
     * SSBOneBlock fires a synthetic BlockBreakEvent right after the real one
     * (same block, same nanosecond bucket). Cleared as it grows via natural
     * overwrites — no need for eviction because it caps at online-player size.
     */
    private final ConcurrentHashMap<UUID, LastHit> lastHitByPlayer = new ConcurrentHashMap<>();

    public BlockBreakListener(FirstDtcPlugin plugin) {
        this.plugin = plugin;
    }

    private record LastHit(UUID islandId, int x, int y, int z, long nanoTime) {}

    /**
     * We run at HIGHEST with {@code ignoreCancelled = false}.
     *
     * <p>SSBOneBlock's handler runs at LOWEST and cancels the real event to do
     * its own drop/place logic. Relying on its synthetic replay to fire at
     * MONITOR turned out to be too fragile (silent no-op in several install
     * setups), so we take the direct route: see EVERY BlockBreakEvent, even
     * cancelled ones, and count only when the broken block sits exactly on the
     * island's OneBlock position. That way we don't care whether the module
     * cancels + replays, cancels + swallows, or lets the break through — the
     * position filter is enough to isolate genuine OneBlock hits, and a same-
     * tick dedupe below covers the case where a plugin fires a synthetic
     * replay of the same break.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBreak(BlockBreakEvent event) {
        if (!plugin.getGameManager().isRunning()) return;

        PluginConfig cfg = plugin.getPluginConfig();
        Player player = event.getPlayer();
        Block block = event.getBlock();
        World world = block.getWorld();

        // Entry trace, only when debug is on. The unconditional INFO version
        // that lived here helped diagnose "the bar isn't moving" but was too
        // costly to leave on a busy server (one console I/O per break, per
        // player). Toggle debug: true in config.yml to bring it back.
        debug("recv " + player.getName() + " @ " + fmt(block)
                + " cancelled=" + event.isCancelled() + " gm=" + player.getGameMode());

        // Note: no gamemode filter. Creative admins / staff testing the event
        // and Adventure-mode players all count the same. The other filters
        // (island membership + OneBlock position) are enough to keep the score
        // honest.

        // World filter (empty set = all worlds allowed).
        Set<String> worlds = cfg.getWorlds();
        if (!worlds.isEmpty() && !worlds.contains(world.getName())) {
            debug("skip: world '" + world.getName() + "' not in filter");
            return;
        }

        // Optional material whitelist (empty = accept every material).
        Set<Material> allowed = cfg.getAllowedBlocks();
        if (!allowed.isEmpty() && !allowed.contains(block.getType())) {
            debug("skip: material " + block.getType() + " not in allowed-blocks");
            return;
        }

        Island island = SuperiorSkyblockAPI.getGrid().getIslandAt(block.getLocation());
        if (island == null || island.isSpawn()) {
            debug("skip: no island at " + fmt(block));
            return;
        }

        if (cfg.isRequireOwnIsland()) {
            SuperiorPlayer sp = SuperiorSkyblockAPI.getPlayer(player);
            if (sp == null || !island.isMember(sp)) {
                debug("skip: " + player.getName() + " is not a member of island " + island.getUniqueId());
                return;
            }
        }

        // OneBlock filter: ask the module directly when possible, fall back to
        // configured offset math otherwise.
        if (cfg.isOnlyOneBlock()) {
            OneBlockHook hook = plugin.getOneBlockHook();
            Location oneBlock = hook.getOneBlock(island);
            if (oneBlock == null) oneBlock = fallbackOneBlock(cfg, island, world);

            if (oneBlock == null) {
                debug("skip: could not resolve OneBlock position for island " + island.getUniqueId());
                return;
            }
            if (!sameBlock(oneBlock, block)) {
                debug("skip: broken block " + fmt(block) + " != OneBlock " + fmt(oneBlock));
                return;
            }
        }

        // Dedupe: if we counted the exact same OneBlock hit for this player less
        // than 50 ms ago (i.e. the current tick), it's a synthetic replay. Skip.
        long now = System.nanoTime();
        LastHit prev = lastHitByPlayer.get(player.getUniqueId());
        if (prev != null
                && prev.islandId.equals(island.getUniqueId())
                && prev.x == block.getX() && prev.y == block.getY() && prev.z == block.getZ()
                && (now - prev.nanoTime) < 50_000_000L) {
            debug("dedupe: same OneBlock break already counted this tick for " + player.getName());
            return;
        }
        lastHitByPlayer.put(player.getUniqueId(),
                new LastHit(island.getUniqueId(), block.getX(), block.getY(), block.getZ(), now));

        plugin.getGameManager().recordBreak(player, island);
        debug("hit: " + player.getName() + " -> island " + island.getUniqueId()
                + " (cancelled=" + event.isCancelled() + ")");
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastHitByPlayer.remove(event.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static boolean sameBlock(Location loc, Block block) {
        return loc.getWorld() != null
                && loc.getWorld().equals(block.getWorld())
                && loc.getBlockX() == block.getX()
                && loc.getBlockY() == block.getY()
                && loc.getBlockZ() == block.getZ();
    }

    /** Fallback used when SSBOneBlock isn't loaded or its reflection call failed. */
    private static Location fallbackOneBlock(PluginConfig cfg, Island island, World world) {
        for (Dimension d : Dimension.values()) {
            try {
                Location c = island.getCenter(d);
                if (c == null || !world.equals(c.getWorld())) continue;
                return new Location(world,
                        c.getBlockX() + cfg.getOneBlockOffsetX(),
                        c.getBlockY() + cfg.getOneBlockOffsetY(),
                        c.getBlockZ() + cfg.getOneBlockOffsetZ());
            } catch (Throwable ignored) {
                // island.getCenter throws when the island has no world for that
                // dimension - just try the next one.
            }
        }
        return null;
    }

    private void debug(String msg) {
        if (plugin.getPluginConfig().isDebug()) {
            plugin.getLogger().info("[break] " + msg);
        }
    }

    private static String fmt(Block b) {
        return b.getWorld().getName() + " " + b.getX() + "," + b.getY() + "," + b.getZ();
    }

    private static String fmt(Location l) {
        return (l.getWorld() == null ? "?" : l.getWorld().getName())
                + " " + l.getBlockX() + "," + l.getBlockY() + "," + l.getBlockZ();
    }
}
