package fr.first.firstdtc.game;

import com.bgsoftware.superiorskyblock.api.island.Island;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Damage bookkeeping for a single island during a run. All mutation goes
 * through the synchronised methods so multiple region threads on Folia can
 * safely append breaks from different chunks.
 */
public final class IslandStats {

    private final Island island;
    private final Map<UUID, Double> damageByPlayer = new HashMap<>();
    private final Map<UUID, String> nameByPlayer = new HashMap<>();
    private double total;

    public IslandStats(Island island) {
        this.island = island;
    }

    public Island island() { return island; }

    public synchronized void add(UUID playerId, String playerName, double damage) {
        damageByPlayer.merge(playerId, damage, Double::sum);
        nameByPlayer.putIfAbsent(playerId, playerName);
        total += damage;
    }

    public synchronized double total() { return total; }

    public synchronized double damageFor(UUID playerId) {
        return damageByPlayer.getOrDefault(playerId, 0.0d);
    }

    /** Snapshot of member damage, highest first. */
    public synchronized List<MemberDamage> topMembers() {
        List<MemberDamage> out = new ArrayList<>(damageByPlayer.size());
        for (Map.Entry<UUID, Double> e : damageByPlayer.entrySet()) {
            out.add(new MemberDamage(e.getKey(), nameByPlayer.getOrDefault(e.getKey(), "?"), e.getValue()));
        }
        out.sort(Comparator.comparingDouble(MemberDamage::damage).reversed());
        return Collections.unmodifiableList(out);
    }

    public record MemberDamage(UUID uuid, String name, double damage) {}
}
