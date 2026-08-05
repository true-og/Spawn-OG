package spawnog.world;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

// The SMP dimensions Spawn-OG governs, read from login-safety.worlds. Login
// safety normalizes logins in them and respawns are kept inside them, so both
// consult one list. Also remembers which of them each player was in last,
// because a respawn that would land outside them has to pick one to come back to.
public final class ManagedWorlds implements Listener {

    private static final List<String> DEFAULTS = List.of("world", "world_nether", "world_the_end");

    private final JavaPlugin plugin;
    private final Map<UUID, String> lastWorld = new HashMap<>();

    public ManagedWorlds(JavaPlugin plugin) {

        this.plugin = plugin;

    }

    public boolean contains(World world) {

        return world != null && names().stream().anyMatch(name -> name.equalsIgnoreCase(world.getName()));

    }

    // The last managed world the player was in, falling back to the first
    // configured one that is loaded. Null only when none of them is.
    public World lastFor(Player player) {

        String remembered = lastWorld.get(player.getUniqueId());
        World world = remembered == null ? null : plugin.getServer().getWorld(remembered);
        if (world != null)
            return world;

        for (String name : names()) {

            World candidate = plugin.getServer().getWorld(name);
            if (candidate != null)
                return candidate;

        }

        return null;

    }

    public void close() {

        lastWorld.clear();

    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {

        remember(event.getPlayer());

    }

    @EventHandler
    public void onChangedWorld(PlayerChangedWorldEvent event) {

        remember(event.getPlayer());

    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {

        lastWorld.remove(event.getPlayer().getUniqueId());

    }

    // Only managed worlds are recorded, so a trip into a minigame world leaves
    // the player's last SMP dimension standing as the one to return them to.
    private void remember(Player player) {

        World world = player.getWorld();
        if (contains(world))
            lastWorld.put(player.getUniqueId(), world.getName());

    }

    private List<String> names() {

        List<String> configured = plugin.getConfig().getStringList("login-safety.worlds");
        return configured.isEmpty() ? DEFAULTS : configured;

    }

}
