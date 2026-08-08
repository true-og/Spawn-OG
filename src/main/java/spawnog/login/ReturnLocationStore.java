package spawnog.login;

import java.time.Instant;
import java.util.UUID;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;

import spawnog.SpawnOG;
import spawnog.flight.FlightMode;

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

    // Token for records written before tokens existed, so their pending
    // confirmation still keys against something stable.
    private static final String LEGACY_TOKEN = "legacy";

    public ReturnLocationStore(SpawnOG plugin) {

        super(plugin, "return-locations.yml");

    }

    public boolean record(UUID playerId, String playerName, Location location, String reason, GameMode gamemode,
            boolean allowFlight, boolean flying, FlightMode mode)
    {

        if (location == null || location.getWorld() == null)
            return false;

        String path = path(playerId);
        data.set(path + ".player-name", playerName);
        data.set(path + ".recorded-at", Instant.now().toString());
        data.set(path + ".reason", reason);
        // Fresh per record, so a /spawnback warning issued for an older rescue
        // can never confirm a newer one the player was not shown.
        data.set(path + ".token", UUID.randomUUID().toString());
        data.set(path + ".location.world", location.getWorld().getName());
        data.set(path + ".location.x", location.getX());
        data.set(path + ".location.y", location.getY());
        data.set(path + ".location.z", location.getZ());
        data.set(path + ".location.yaw", location.getYaw());
        data.set(path + ".location.pitch", location.getPitch());
        // The mode of flight decides what /spawnback hands back; the raw
        // gamemode and ability are kept alongside so staff reading this file
        // can see what a player actually lost.
        data.set(path + ".mode", mode == null ? FlightMode.NONE.name() : mode.name());
        data.set(path + ".gamemode", gamemode == null ? null : gamemode.name());
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

        // Records written before modes were stored read as NONE, which matches
        // the old behavior of never restoring flight.
        return new ReturnPoint(location, data.getString(path + ".reason", "it was flagged unsafe"), world.getName(),
                gamemode(path), data.getBoolean(path + ".allow-flight", false),
                data.getBoolean(path + ".flying", false), mode(path), data.getString(path + ".token", LEGACY_TOKEN));

    }

    // NONE when the record predates modes or names one this build no longer
    // has, so an unreadable value never turns into a flight grant.
    private FlightMode mode(String path) {

        String recorded = data.getString(path + ".mode");
        if (recorded == null)
            return FlightMode.NONE;

        try {

            return FlightMode.valueOf(recorded);

        } catch (IllegalArgumentException error) {

            return FlightMode.NONE;

        }

    }

    // Null when the record predates the field or names a gamemode this server no
    // longer has, so an unreadable value is never mistaken for survival.
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

    // mode is what /spawnback restores; gamemode and allowFlight are the raw
    // pre-migration state, kept for diagnosis. token identifies this record so
    // a warning and its confirmation are known to speak about the same rescue.
    public record ReturnPoint(Location location, String reason, String worldName, GameMode gamemode,
            boolean allowFlight, boolean flying, FlightMode mode, String token)
    {
    }

}
