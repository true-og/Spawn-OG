package spawnog.login;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.UUID;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;

import spawnog.SpawnOG;

public final class AutopsyMigrationStore {

    private final SpawnOG plugin;
    private final File migrationFile;
    private final YamlConfiguration migrations;

    public AutopsyMigrationStore(SpawnOG plugin) {

        this.plugin = plugin;
        this.migrationFile = new File(plugin.getDataFolder(), "autopsy-migrations.yml");
        this.migrations = YamlConfiguration.loadConfiguration(migrationFile);

    }

    public boolean hasMigrated(UUID playerId) {

        return migrations.getBoolean(path(playerId) + ".complete", false);

    }

    public boolean record(UUID playerId, String playerName, GameMode originalGamemode, Location originalLocation) {

        String path = path(playerId);
        migrations.set(path + ".complete", true);
        migrations.set(path + ".player-name", playerName);
        migrations.set(path + ".migrated-at", Instant.now().toString());
        migrations.set(path + ".original-gamemode", originalGamemode.name());
        migrations.set(path + ".original-location.world",
                originalLocation.getWorld() == null ? null : originalLocation.getWorld().getName());
        migrations.set(path + ".original-location.x", originalLocation.getX());
        migrations.set(path + ".original-location.y", originalLocation.getY());
        migrations.set(path + ".original-location.z", originalLocation.getZ());
        migrations.set(path + ".original-location.yaw", originalLocation.getYaw());
        migrations.set(path + ".original-location.pitch", originalLocation.getPitch());

        try {

            saveAtomically();
            return true;

        } catch (IOException error) {

            migrations.set(path, null);
            plugin.getLogger()
                    .severe("Unable to save the autopsy migration for " + playerName + ": " + error.getMessage());
            return false;

        }

    }

    private void saveAtomically() throws IOException {

        File dataFolder = plugin.getDataFolder();
        if (!dataFolder.exists() && !dataFolder.mkdirs())
            throw new IOException("Unable to create " + dataFolder);

        File temporaryFile = new File(dataFolder, migrationFile.getName() + ".tmp");
        migrations.save(temporaryFile);

        try {

            Files.move(temporaryFile.toPath(), migrationFile.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);

        } catch (AtomicMoveNotSupportedException error) {

            Files.move(temporaryFile.toPath(), migrationFile.toPath(), StandardCopyOption.REPLACE_EXISTING);

        }

    }

    private String path(UUID playerId) {

        return "players." + playerId;

    }

}
