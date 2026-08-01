package spawnog.login;

import java.time.Instant;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.World;

import spawnog.SpawnOG;

// Remembers where a player stood before Spawn-OG pulled them to spawn, so they
// can walk it back with /spawnback even though the position was flagged unsafe.
// Persisted, because players usually read the migration message a session or
// two later.
//
// <p>
// Coordinates are stored as plain numbers rather than serialized Locations:
// deserializing a Location whose world is unloaded throws, and that would take
// every other player's record down with it.
public final class ReturnLocationStore extends YamlStore {

    public ReturnLocationStore(SpawnOG plugin) {

        super(plugin, "return-locations.yml");

    }

    public boolean record(UUID playerId, String playerName, Location location, String reason, boolean allowFlight,
            boolean flying)
    {

        if (location == null || location.getWorld() == null)
            return false;

        String path = path(playerId);
        data.set(path + ".player-name", playerName);
        data.set(path + ".recorded-at", Instant.now().toString());
        data.set(path + ".reason", reason);
        data.set(path + ".location.world", location.getWorld().getName());
        data.set(path + ".location.x", location.getX());
        data.set(path + ".location.y", location.getY());
        data.set(path + ".location.z", location.getZ());
        data.set(path + ".location.yaw", location.getYaw());
        data.set(path + ".location.pitch", location.getPitch());
        // Flight state from before the migration touched it, so the return
        // teleport can resume flight for a player who was airborne.
        data.set(path + ".allow-flight", allowFlight);
        data.set(path + ".flying", flying);

        if (save("return location for " + playerName))
            return true;

        data.set(path, null);
        return false;

    }

    // True when a record exists, whether or not its world is still loaded.
    public boolean has(UUID playerId) {

        return data.contains(path(playerId) + ".location");

    }

    // The stored point, or null when nothing is stored or its world is unloaded.
    public ReturnPoint get(UUID playerId) {

        String path = path(playerId);
        if (!has(playerId))
            return null;

        World world = plugin.getServer().getWorld(worldName(path));
        if (world == null)
            return null;

        Location location = new Location(world, data.getDouble(path + ".location.x"),
                data.getDouble(path + ".location.y"), data.getDouble(path + ".location.z"),
                (float) data.getDouble(path + ".location.yaw"), (float) data.getDouble(path + ".location.pitch"));

        // Records written before flight state was stored read as false, which
        // matches the old behavior of never resuming flight.
        return new ReturnPoint(location, data.getString(path + ".reason", "it was flagged unsafe"), world.getName(),
                data.getBoolean(path + ".allow-flight", false), data.getBoolean(path + ".flying", false));

    }

    // The world a record points at, readable even when that world is unloaded.
    public String worldName(UUID playerId) {

        return worldName(path(playerId));

    }

    public void clear(UUID playerId) {

        if (!data.contains(path(playerId)))
            return;

        data.set(path(playerId), null);
        save("cleared return location");

    }

    private String worldName(String path) {

        return data.getString(path + ".location.world", "unknown");

    }

    public record ReturnPoint(Location location, String reason, String worldName, boolean allowFlight, boolean flying) {
    }

}
