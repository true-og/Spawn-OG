package spawnog.listener;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import spawnog.SpawnOG;

public class SpawnListener implements Listener {

    private final SpawnOG plugin = SpawnOG.getInstance();

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {

        Player p = e.getPlayer();
        boolean first = !p.hasPlayedBefore();
        boolean force = plugin.getConfig().getBoolean("spawn-on-join", false);

        if (!first && !force) {

            // Returning players keep their logout position; rescue them if it is unsafe.
            rescueIfUnsafe(p);
            return;

        }

        Location dest;
        if (first) {

            String nb = plugin.getConfig().getString("newbies.spawnpoint", "newbie").toLowerCase();
            dest = plugin.getConfig().getLocation("spawns." + nb + ".location");
            if (dest == null)
                dest = plugin.getConfig().getLocation("spawns.global.location");

        } else {

            dest = plugin.getConfig().getLocation("spawns.global.location");

        }

        if (dest != null)
            p.teleportAsync(dest.clone());

    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {

        Player p = e.getPlayer();
        boolean atHome = plugin.getConfig().getBoolean("respawn-at-home", true);

        if (atHome && (p.getBedSpawnLocation() != null))
            return;

        if (atHome && plugin.getServer().getPluginManager().isPluginEnabled("Essentials")) {

            try {

                Object ess = plugin.getServer().getPluginManager().getPlugin("Essentials");
                Object user = ess.getClass().getMethod("getUser", Player.class).invoke(ess, p);
                Location home = (Location) user.getClass().getMethod("getHome", String.class).invoke(user, "home");
                if (home != null) {

                    e.setRespawnLocation(home.clone());
                    return;

                }

            } catch (Throwable ignored) {

            }

        }

        Location def = plugin.getConfig().getLocation("spawns.global.location");
        if (def != null)
            e.setRespawnLocation(def.clone());

    }

    // Teleport the player to spawn and report their old coordinates when their
    // login location is unsafe.
    private void rescueIfUnsafe(Player p) {

        Location from = p.getLocation();
        if (!isUnsafe(from))
            return;

        Location spawn = plugin.getConfig().getLocation("spawns.global.location");
        if (spawn == null)
            return;

        String raw = plugin.getConfig().getString("locale.unsafeLogin",
                "<gold>You logged in at an unsafe location (<red><x>, <y>, <z></red> in <world>) and were teleported to spawn.</gold>");
        Component msg = MiniMessage.miniMessage().deserialize(raw,
                Placeholder.unparsed("x", String.valueOf(from.getBlockX())),
                Placeholder.unparsed("y", String.valueOf(from.getBlockY())),
                Placeholder.unparsed("z", String.valueOf(from.getBlockZ())),
                Placeholder.unparsed("world", from.getWorld() != null ? from.getWorld().getName() : "unknown"));
        p.sendMessage(msg);

        p.teleportAsync(spawn.clone());

    }

    // Unsafe when an occluding block overlaps the player's body (suffocation) or
    // there is nothing solid underfoot (fall).
    private boolean isUnsafe(Location loc) {

        Block feet = loc.getBlock();
        Block head = feet.getRelative(BlockFace.UP);
        Block ground = feet.getRelative(BlockFace.DOWN);

        // Suffocation: head or feet inside a full solid block.
        if (feet.getType().isOccluding() || head.getType().isOccluding())
            return true;

        // Air underfoot: nothing to stand on.
        return ground.isEmpty();

    }

}
