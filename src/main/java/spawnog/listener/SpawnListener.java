package spawnog.listener;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import spawnog.SpawnOG;
import spawnog.login.LoginMigrationService;
import spawnog.world.ManagedWorlds;

public class SpawnListener implements Listener {

    private final SpawnOG plugin;
    private final LoginMigrationService loginMigration;
    private final ManagedWorlds managedWorlds;

    public SpawnListener(SpawnOG plugin, LoginMigrationService loginMigration, ManagedWorlds managedWorlds) {

        this.plugin = plugin;
        this.loginMigration = loginMigration;
        this.managedWorlds = managedWorlds;

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
        Location destination = preferred(p, e);

        // Bed, home, and configured spawn all keep their say, but only inside
        // the worlds Spawn-OG governs. A bed left in a minigame world, or a
        // death in one, would otherwise respawn the player outside the SMP.
        if (!managedWorlds.contains(destination == null ? null : destination.getWorld()))
            destination = lastManagedSpawn(p);

        if (destination != null)
            e.setRespawnLocation(destination);

    }

    // Where the player would land without the managed-world guarantee: their bed
    // if the server found one usable, then their Essentials home, then the
    // configured spawn.
    private Location preferred(Player p, PlayerRespawnEvent e) {

        boolean atHome = plugin.getConfig().getBoolean("respawn-at-home", true);

        if (atHome && p.getBedSpawnLocation() != null)
            return e.getRespawnLocation();

        if (atHome) {

            Location home = essentialsHome(p);
            if (home != null)
                return home;

        }

        return plugin.getConfig().getLocation("spawns.global.location");

    }

    // The spawn of the last managed world the player was in. The configured
    // spawn wins inside its own world; the other dimensions have only their own.
    private Location lastManagedSpawn(Player p) {

        Location global = plugin.getConfig().getLocation("spawns.global.location");
        World last = managedWorlds.lastFor(p);

        if (last == null)
            return global == null ? null : global.clone();
        if (global != null && last.equals(global.getWorld()))
            return global.clone();

        return last.getSpawnLocation();

    }

    private Location essentialsHome(Player p) {

        String essentialsName = null;
        if (plugin.getServer().getPluginManager().isPluginEnabled("Essentials-OG"))
            essentialsName = "Essentials-OG";
        else if (plugin.getServer().getPluginManager().isPluginEnabled("Essentials"))
            essentialsName = "Essentials";

        if (essentialsName == null)
            return null;

        try {

            Object ess = plugin.getServer().getPluginManager().getPlugin(essentialsName);
            Object user = ess.getClass().getMethod("getUser", Player.class).invoke(ess, p);
            Location home = (Location) user.getClass().getMethod("getHome", String.class).invoke(user, "home");
            return home == null ? null : home.clone();

        } catch (Throwable ignored) {

            return null;

        }

    }

}
