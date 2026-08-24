package spawnog.listener;

import java.util.Locale;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import spawnog.SpawnOG;
import spawnog.login.LocationSafety;
import spawnog.login.LoginMigrationService;
import spawnog.login.MinigameArenas;
import spawnog.teleport.FallProtection;
import spawnog.world.ManagedWorlds;

public class SpawnListener implements Listener {

    private final SpawnOG plugin;
    private final LoginMigrationService loginMigration;
    private final ManagedWorlds managedWorlds;
    private final FallProtection fallProtection;

    public SpawnListener(SpawnOG plugin, LoginMigrationService loginMigration, ManagedWorlds managedWorlds,
            FallProtection fallProtection)
    {

        this.plugin = plugin;
        this.loginMigration = loginMigration;
        this.managedWorlds = managedWorlds;
        this.fallProtection = fallProtection;

    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {

        Player p = e.getPlayer();
        boolean first = !p.hasPlayedBefore();
        boolean force = plugin.getConfig().getBoolean("spawn-on-join", false);

        Location dest = null;
        if (first) {

            String nb = plugin.getConfig().getString("newbies.spawnpoint", "newbie").toLowerCase(Locale.ROOT);
            dest = plugin.getConfig().getLocation("spawns." + nb + ".location");
            if (dest == null)
                dest = plugin.getConfig().getLocation("spawns.global.location");

        } else if (force) {

            dest = plugin.getConfig().getLocation("spawns.global.location");

        }

        // Cloned once here: everything downstream may hold or adjust it.
        if (dest != null)
            dest = dest.clone();

        if (!loginMigration.handleLogin(p, dest) && dest != null)
            p.teleportAsync(dest);

    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {

        loginMigration.cancel(e.getPlayer());

    }

    // ignoreCancelled: a cancelled kick leaves the player online mid-rescue;
    // tearing the transaction down would orphan it with no reconciliation.
    @EventHandler(ignoreCancelled = true)
    public void onKick(PlayerKickEvent e) {

        loginMigration.cancel(e.getPlayer());

    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {

        Player p = e.getPlayer();
        // A minigame death respawns by the minigame's rules, not the SMP's.
        if (MinigameArenas.contains(p))
            return;

        Location destination = preferred(p, e);

        // Bed, home, and configured spawn keep their say only inside governed
        // worlds; a minigame bed or death must not respawn the player outside the SMP.
        if (!managedWorlds.contains(destination == null ? null : destination.getWorld()))
            destination = lastManagedSpawn(p);

        if (destination == null)
            return;

        // Lifted out of anything built over it since it was recorded.
        Location cleared = LocationSafety.clearAbove(destination);
        if (cleared != null)
            destination = cleared;
        e.setRespawnLocation(destination);

        // An airborne respawn point gets its landing covered.
        plugin.getServer().getScheduler().runTask(plugin, () -> {

            if (p.isOnline() && !LocationSafety.isSupported(p.getLocation())) {

                fallProtection.grant(p);
                p.setFallDistance(0.0F);

            }

        });

    }

    // Without the managed-world guarantee: usable bed first, then Essentials
    // home, then the configured spawn.
    private Location preferred(Player p, PlayerRespawnEvent e) {

        boolean atHome = plugin.getConfig().getBoolean("respawn-at-home", true);

        if (atHome && p.getBedSpawnLocation() != null)
            return e.getRespawnLocation();

        if (atHome) {

            Location home = essentialsHome(p);
            if (home != null)
                return home;

        }

        // Cloned: the respawn destination is the config's live instance, and it
        // ends up handed to the respawn event.
        Location global = plugin.getConfig().getLocation("spawns.global.location");
        return global == null ? null : global.clone();

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
