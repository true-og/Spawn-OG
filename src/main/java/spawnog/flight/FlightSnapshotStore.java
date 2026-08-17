package spawnog.flight;

import java.time.Instant;
import java.util.UUID;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import spawnog.SpawnOG;
import spawnog.login.YamlStore;

// Persistent record of who left mid-flight: where, gamemode, and kind of
// flight. Last quit wins: a grounded quit erases any stale airborne record.
public final class FlightSnapshotStore extends YamlStore {

    public FlightSnapshotStore(SpawnOG plugin) {

        super(plugin, "flight-snapshots.yml");

    }

    public void record(Player player, boolean noclip, boolean flyIntent) {

        String path = path(player.getUniqueId());
        Location location = player.getLocation();
        data.set(path + ".player-name", player.getName());
        data.set(path + ".recorded-at", Instant.now().toString());
        // Plain numbers, not a serialized Location: one unloaded world would
        // throw and take every other player's record down with it.
        data.set(path + ".location.world", location.getWorld() == null ? null : location.getWorld().getName());
        data.set(path + ".location.x", location.getX());
        data.set(path + ".location.y", location.getY());
        data.set(path + ".location.z", location.getZ());
        data.set(path + ".location.yaw", location.getYaw());
        data.set(path + ".location.pitch", location.getPitch());
        data.set(path + ".gamemode", player.getGameMode().name());
        data.set(path + ".allow-flight", player.getAllowFlight());
        data.set(path + ".flying", player.isFlying());
        data.set(path + ".noclip", noclip);
        data.set(path + ".fly-intent", flyIntent);

        if (!save("flight snapshot for " + player.getName()))
            data.set(path, null);

    }

    // Reads without clearing: the record is only spent once the login that
    // consumed it has actually resolved, so a failed rescue keeps the way back.
    public FlightSnapshot peek(UUID playerId) {

        String path = path(playerId);
        if (!data.contains(path))
            return null;

        return new FlightSnapshot(gamemode(path), location(path), data.getBoolean(path + ".allow-flight", false),
                data.getBoolean(path + ".flying", true), data.getBoolean(path + ".noclip", false),
                data.getBoolean(path + ".fly-intent", false));

    }

    public void clear(UUID playerId) {

        if (!data.contains(path(playerId)))
            return;

        data.set(path(playerId), null);
        save("cleared flight snapshot");

    }

    // Null when the record predates the field or names a gamemode this server
    // no longer has; corroboration rejects such a record outright.
    private GameMode gamemode(String path) {

        String recorded = data.getString(path + ".gamemode");
        if (recorded == null)
            return null;

        try {

            return GameMode.valueOf(recorded);

        } catch (IllegalArgumentException error) {

            return null;

        }

    }

    // Null when the world is unloaded; the consumer falls back to wherever the
    // player actually logged in, which on a first read is the same spot.
    private Location location(String path) {

        World world = plugin.getServer().getWorld(data.getString(path + ".location.world", "unknown"));
        if (world == null)
            return null;

        return new Location(world, data.getDouble(path + ".location.x"), data.getDouble(path + ".location.y"),
                data.getDouble(path + ".location.z"), (float) data.getDouble(path + ".location.yaw"),
                (float) data.getDouble(path + ".location.pitch"));

    }

    // gamemode and location may be null on older or unloaded-world records. An
    // absent fly-intent reads false, so truncation classifies as NONE, never FLY.
    public record FlightSnapshot(GameMode gamemode, Location location, boolean allowFlight, boolean flying,
            boolean noclip, boolean flyIntent)
    {

        public FlightMode mode() {

            return FlightMode.classify(gamemode, noclip, flyIntent);

        }

    }

}
