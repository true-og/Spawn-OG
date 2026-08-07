package spawnog.flight;

import java.time.Instant;
import java.util.UUID;

import org.bukkit.entity.Player;

import spawnog.SpawnOG;
import spawnog.login.YamlStore;

// Remembers the players Spawn-OG grounded on their way out. Regional flight is
// revoked on quit so a grant is never written into the player's saved abilities
// and become permanent free flight, but that also erases the only evidence that
// they were airborne: the next login reads them as standing still, and a rescue
// from that login records a return point /spawnback will drop them from.
//
// Persisted rather than kept in memory because a shutdown revokes flight the
// same way, and the player may not come back until long after.
public final class AirborneQuitStore extends YamlStore {

    public AirborneQuitStore(SpawnOG plugin) {

        super(plugin, "airborne-quits.yml");

    }

    public void record(Player player) {

        String path = path(player.getUniqueId());
        if (data.contains(path))
            return;

        data.set(path + ".player-name", player.getName());
        data.set(path + ".recorded-at", Instant.now().toString());

        if (!save("airborne quit for " + player.getName()))
            data.set(path, null);

    }

    // True when Spawn-OG took the player out of the air on their way out. Clears
    // the record as it reads it, so one quit answers exactly one login and a
    // stale flag can never make a later, grounded login look airborne.
    public boolean consume(UUID playerId) {

        if (!data.contains(path(playerId)))
            return false;

        data.set(path(playerId), null);
        save("cleared airborne quit");
        return true;

    }

}
