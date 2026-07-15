package spawnog.listener;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import spawnog.SpawnOG;
import spawnog.login.LoginMigrationService;

public class SpawnListener implements Listener {

    private final SpawnOG plugin;
    private final LoginMigrationService loginMigration;

    public SpawnListener(SpawnOG plugin, LoginMigrationService loginMigration) {

        this.plugin = plugin;
        this.loginMigration = loginMigration;

    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {

        Player p = e.getPlayer();
        boolean first = !p.hasPlayedBefore();
        boolean force = plugin.getConfig().getBoolean("spawn-on-join", false);

        Location dest = null;
        if (first) {

            String nb = plugin.getConfig().getString("newbies.spawnpoint", "newbie").toLowerCase();
            dest = plugin.getConfig().getLocation("spawns." + nb + ".location");
            if (dest == null)
                dest = plugin.getConfig().getLocation("spawns.global.location");

        } else if (force) {

            dest = plugin.getConfig().getLocation("spawns.global.location");

        }

        if (!loginMigration.handleLogin(p, dest) && dest != null)
            p.teleportAsync(dest.clone());

    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {

        loginMigration.cancel(e.getPlayer());

    }

    @EventHandler
    public void onKick(PlayerKickEvent e) {

        loginMigration.cancel(e.getPlayer());

    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {

        Player p = e.getPlayer();
        boolean atHome = plugin.getConfig().getBoolean("respawn-at-home", true);

        if (atHome && (p.getBedSpawnLocation() != null))
            return;

        String essentialsName = null;
        if (plugin.getServer().getPluginManager().isPluginEnabled("Essentials-OG"))
            essentialsName = "Essentials-OG";
        else if (plugin.getServer().getPluginManager().isPluginEnabled("Essentials"))
            essentialsName = "Essentials";

        if (atHome && essentialsName != null) {

            try {

                Object ess = plugin.getServer().getPluginManager().getPlugin(essentialsName);
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
