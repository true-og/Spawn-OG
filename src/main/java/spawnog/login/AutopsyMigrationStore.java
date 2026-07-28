package spawnog.login;

import java.time.Instant;
import java.util.UUID;

import org.bukkit.GameMode;
import org.bukkit.Location;

import spawnog.SpawnOG;

public final class AutopsyMigrationStore extends YamlStore {

    public AutopsyMigrationStore(SpawnOG plugin) {

        super(plugin, "autopsy-migrations.yml");

    }

    public boolean hasMigrated(UUID playerId) {

        return data.getBoolean(path(playerId) + ".complete", false);

    }

    public boolean record(UUID playerId, String playerName, GameMode originalGamemode, Location originalLocation) {

        String path = path(playerId);
        data.set(path + ".complete", true);
        data.set(path + ".player-name", playerName);
        data.set(path + ".migrated-at", Instant.now().toString());
        data.set(path + ".original-gamemode", originalGamemode.name());
        data.set(path + ".original-location.world",
                originalLocation.getWorld() == null ? null : originalLocation.getWorld().getName());
        data.set(path + ".original-location.x", originalLocation.getX());
        data.set(path + ".original-location.y", originalLocation.getY());
        data.set(path + ".original-location.z", originalLocation.getZ());
        data.set(path + ".original-location.yaw", originalLocation.getYaw());
        data.set(path + ".original-location.pitch", originalLocation.getPitch());

        if (save("autopsy migration for " + playerName))
            return true;

        data.set(path, null);
        return false;

    }

}
