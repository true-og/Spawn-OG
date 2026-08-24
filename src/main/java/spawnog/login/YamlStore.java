package spawnog.login;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

import org.bukkit.configuration.file.YamlConfiguration;

import spawnog.SpawnOG;

// Crash-safe YAML file backing the persistent per-player stores.
public abstract class YamlStore {

    protected final SpawnOG plugin;
    protected final YamlConfiguration data;

    private final File file;
    // While batching, mutations mark dirty instead of writing; the batch's end
    // performs one write for all of them.
    private boolean batching;
    private boolean dirty;

    protected YamlStore(SpawnOG plugin, String fileName) {

        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), fileName);
        this.data = YamlConfiguration.loadConfiguration(file);

    }

    // Groups many mutations into one write; shutdown sweeps would otherwise
    // rewrite the whole file once per player. A nested batch folds into the
    // outer one rather than stealing its dirty flag.
    public void batch(String description, Runnable mutations) {

        if (batching) {

            mutations.run();
            return;

        }

        batching = true;
        dirty = false;

        try {

            mutations.run();

        } finally {

            batching = false;
            if (dirty)
                save(description);
            dirty = false;

        }

    }

    protected boolean save(String description) {

        if (batching) {

            dirty = true;
            return true;

        }

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
