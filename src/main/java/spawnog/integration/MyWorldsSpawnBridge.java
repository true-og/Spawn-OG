package spawnog.integration;

import java.lang.reflect.Method;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;

// Pushes the global spawn into MyWorlds so World#getSpawnLocation matches
// /setspawn. Reflection keeps it soft; My_Worlds is the TrueOG fork name.
public final class MyWorldsSpawnBridge implements Listener {

    private static final String[] PLUGIN_NAMES = { "MyWorlds", "My_Worlds" };
    private static final String WORLD_CONFIG_CLASS = "com.bergerkiller.bukkit.mw.WorldConfig";
    private static final String WORLD_CONFIG_STORE_CLASS = "com.bergerkiller.bukkit.mw.WorldConfigStore";

    private final JavaPlugin plugin;

    public MyWorldsSpawnBridge(JavaPlugin plugin) {

        this.plugin = plugin;

    }

    public static boolean isMyWorldsPresent() {

        for (String name : PLUGIN_NAMES)
            if (Bukkit.getPluginManager().isPluginEnabled(name))
                return true;

        return false;

    }

    // MyWorlds re-applies its stored spawn whenever a world loads, so a late load
    // of the spawn world would otherwise undo the adoption done at startup.
    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {

        Location spawn = configuredSpawn();
        if (spawn == null || spawn.getWorld() == null)
            return;
        if (!event.getWorld().getName().equals(spawn.getWorld().getName()))
            return;

        adopt();

    }

    // Hands the configured global spawn to MyWorlds and persists it to worlds.yml.
    public void adopt() {

        if (!plugin.getConfig().getBoolean("myworlds-spawn-sync", true))
            return;

        if (!isMyWorldsPresent())
            return;

        Location spawn = configuredSpawn();
        if (spawn == null) {

            plugin.getLogger().info("spawns.global.location is not set; leaving the MyWorlds spawn alone.");
            return;

        }

        World world = spawn.getWorld();
        if (world == null) {

            plugin.getLogger().warning("The world of spawns.global.location is not loaded; MyWorlds spawn unchanged.");
            return;

        }

        try {

            Class<?> worldConfigClass = Class.forName(WORLD_CONFIG_CLASS);
            Method get = worldConfigClass.getMethod("get", String.class);
            Object worldConfig = get.invoke(null, world.getName());
            if (worldConfig == null) {

                plugin.getLogger().warning("MyWorlds has no world config for '" + world.getName() + "'.");
                return;

            }

            Location current = currentSpawn(worldConfigClass, worldConfig);
            if (current != null && sameSpot(current, spawn))
                return;

            worldConfigClass.getMethod("setSpawnLocation", Location.class).invoke(worldConfig, spawn);
            Class.forName(WORLD_CONFIG_STORE_CLASS).getMethod("saveAll").invoke(null);

            plugin.getLogger()
                    .info("MyWorlds spawn for '" + world.getName() + "' adopted from Spawn-OG: " + fmt(spawn) + ".");

        } catch (ReflectiveOperationException | RuntimeException e) {

            plugin.getLogger().warning("Failed to hand the Spawn-OG spawn to MyWorlds: " + e);

        }

    }

    private Location currentSpawn(Class<?> worldConfigClass, Object worldConfig) {

        try {

            Object value = worldConfigClass.getMethod("getSpawnLocation").invoke(worldConfig);
            return value instanceof Location location ? location : null;

        } catch (ReflectiveOperationException | RuntimeException e) {

            return null;

        }

    }

    private Location configuredSpawn() {

        return plugin.getConfig().getLocation("spawns.global.location");

    }

    // MyWorlds keeps the spawn to two decimals of yaw/pitch like Spawn-OG does, so
    // an exact-enough comparison avoids rewriting worlds.yml on every world load.
    private boolean sameSpot(Location a, Location b) {

        return a.getWorld() == b.getWorld() && Math.abs(a.getX() - b.getX()) < 0.01
                && Math.abs(a.getY() - b.getY()) < 0.01 && Math.abs(a.getZ() - b.getZ()) < 0.01
                && Math.abs(a.getYaw() - b.getYaw()) < 0.01 && Math.abs(a.getPitch() - b.getPitch()) < 0.01;

    }

    private String fmt(Location l) {

        return l.getWorld().getName() + " " + round(l.getX()) + " " + round(l.getY()) + " " + round(l.getZ());

    }

    private double round(double d) {

        return Math.round(d * 100.0) / 100.0;

    }

}
