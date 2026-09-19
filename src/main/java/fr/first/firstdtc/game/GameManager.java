package fr.first.firstdtc.game;

import com.bgsoftware.superiorskyblock.api.island.Island;
import fr.first.firstdtc.FirstDtcPlugin;
import org.bukkit.entity.Player;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thin front-desk for the game: at most one active {@link CoreGame} at a time,
 * safe start/stop, and the entry point event listeners call into.
 */
public final class GameManager {

    private final FirstDtcPlugin plugin;
    private final AtomicReference<CoreGame> current = new AtomicReference<>();

    public GameManager(FirstDtcPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean isRunning() {
        CoreGame g = current.get();
        return g != null && !g.isEnded();
    }

    public Optional<CoreGame> current() {
        return Optional.ofNullable(current.get());
    }

    /**
     * Try to launch a new run. Returns false if one is already active.
     */
    public boolean start() {
        CoreGame fresh = new CoreGame(plugin);
        if (!current.compareAndSet(null, fresh)) return false;
        fresh.start();
        return true;
    }

    /**
     * Cancel the current run without payout. Returns false if no run is active.
     */
    public boolean stop() {
        CoreGame g = current.getAndSet(null);
        if (g == null) return false;
        g.cancel();
        return true;
    }

    /**
     * Termine la partie maintenant mais distribue les récompenses comme si le
     * timer avait expiré. Retourne false si aucune partie n'est active.
     *
     * <p>Note : on ne fait pas {@code getAndSet(null)} comme {@code stop()},
     * parce que {@link CoreGame#finish()} déclenche {@code CoreGame.end}, qui
     * appelle {@link #onGameEnded} depuis la global region une fois les
     * récompenses distribuées.
     */
    public boolean finish() {
        CoreGame g = current.get();
        if (g == null || g.isEnded()) return false;
        g.finish();
        return true;
    }

    /** Called by {@link CoreGame} once its end sequence finishes. */
    public void onGameEnded() {
        current.set(null);
    }

    public void recordBreak(Player player, Island island) {
        CoreGame g = current.get();
        if (g == null) return;
        g.recordBreak(player, island);
    }
}
