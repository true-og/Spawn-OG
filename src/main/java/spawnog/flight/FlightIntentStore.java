package spawnog.flight;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.UUID;

import org.bukkit.entity.Player;

import spawnog.SpawnOG;
import spawnog.login.YamlStore;

// Remembers who has /fly switched on, so the choice survives relogs and
// restarts. Only intent is stored; each grant is re-judged against the rules.
public final class FlightIntentStore extends YamlStore {

    // Grant markers only matter to the enable that follows the reload that
    // wrote them; anything older belongs to players who never rejoined.
    private static final Duration GRANT_MARKER_LIFETIME = Duration.ofDays(30);

    public FlightIntentStore(SpawnOG plugin) {

        super(plugin, "flight-intents.yml");
        pruneGrantMarkers();

    }

    public void record(Player player) {

        String path = path(player.getUniqueId());
        // Already recorded: spamming /fly must not rewrite the whole file.
        if (data.contains(path))
            return;
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

    // Marks flight the service left standing across a reload, so the next
    // enable re-adopts it as plugin-owned instead of letting it drift free.
    public void recordGrantMarkers(Collection<Player> players) {

        if (players.isEmpty())
            return;

        players.forEach(player -> {

            String path = grantPath(player.getUniqueId());
            data.set(path + ".player-name", player.getName());
            data.set(path + ".recorded-at", Instant.now().toString());

        });
        save("reload flight grants");

    }

    // True when a reload-surviving grant is on record; consuming clears it.
    public boolean consumeGrantMarker(Player player) {

        String path = grantPath(player.getUniqueId());
        if (!data.contains(path))
            return false;

        data.set(path, null);
        save("consumed reload flight grant for " + player.getName());
        return true;

    }

    private String grantPath(UUID playerId) {

        return "grants." + playerId;

    }

    private void pruneGrantMarkers() {

        var grants = data.getConfigurationSection("grants");
        if (grants == null)
            return;

        boolean pruned = false;
        for (String key : grants.getKeys(false)) {

            String recordedAt = data.getString("grants." + key + ".recorded-at");
            boolean stale;
            try {

                stale = recordedAt == null
                        || Instant.parse(recordedAt).isBefore(Instant.now().minus(GRANT_MARKER_LIFETIME));

            } catch (DateTimeParseException error) {

                stale = true;

            }

            if (stale) {

                data.set("grants." + key, null);
                pruned = true;

            }

        }

        if (pruned)
            save("pruned stale reload flight grants");

    }

}
