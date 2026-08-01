package spawnog.flight;

import java.time.Instant;
import java.util.UUID;

import org.bukkit.entity.Player;

import spawnog.SpawnOG;
import spawnog.login.YamlStore;

// Remembers who has /fly switched on, so the choice survives relogs and
// restarts instead of dying with the session. Only the intent is stored;
// whether flight is actually granted is re-decided against the region rules
// and permissions every time it is applied.
public final class FlightIntentStore extends YamlStore {

    public FlightIntentStore(SpawnOG plugin) {

        super(plugin, "flight-intents.yml");

    }

    public void record(Player player) {

        String path = path(player.getUniqueId());
        data.set(path + ".player-name", player.getName());
        data.set(path + ".recorded-at", Instant.now().toString());

        if (!save("flight intent for " + player.getName()))
            data.set(path, null);

    }

    public boolean contains(UUID playerId) {

        return data.contains(path(playerId));

    }

    public void clear(Player player) {

        if (!data.contains(path(player.getUniqueId())))
            return;

        data.set(path(player.getUniqueId()), null);
        save("cleared flight intent for " + player.getName());

    }

}
