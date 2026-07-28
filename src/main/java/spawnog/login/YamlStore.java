package spawnog.login;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

import org.bukkit.configuration.file.YamlConfiguration;

import spawnog.SpawnOG;

// Crash-safe YAML file backing the login stores.
abstract class YamlStore {

    protected final SpawnOG plugin;
    protected final YamlConfiguration data;

    private final File file;

    protected YamlStore(SpawnOG plugin, String fileName) {

        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), fileName);
        this.data = YamlConfiguration.loadConfiguration(file);

    }

    protected boolean save(String description) {

        try {

            saveAtomically();
            return true;

        } catch (IOException error) {

            plugin.getLogger().severe("Unable to save the " + description + ": " + error.getMessage());
            return false;

        }

    }

    protected String path(UUID playerId) {

        return "players." + playerId;

    }

    private void saveAtomically() throws IOException {

        File dataFolder = plugin.getDataFolder();
        if (!dataFolder.exists() && !dataFolder.mkdirs())
            throw new IOException("Unable to create " + dataFolder);

        File temporaryFile = new File(dataFolder, file.getName() + ".tmp");
        data.save(temporaryFile);

        try {

            Files.move(temporaryFile.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);

        } catch (AtomicMoveNotSupportedException error) {

            Files.move(temporaryFile.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);

        }

    }

}
