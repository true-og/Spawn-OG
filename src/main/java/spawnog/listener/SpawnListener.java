package spawnog.listener;

import org.bukkit.Location;
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

        if (!first && !force)
            return;

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

}
