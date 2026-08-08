package spawnog.flight;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import spawnog.SpawnOG;
import spawnog.login.NoClipAuthority;

// Captures the flight snapshot on the way out. LOWEST so it reads the state
// before RegionFlightService grounds the player and before NoClip-OG's MONITOR
// cleanup erases its own record of them. Kicks funnel into the same quit event,
// which is also why nothing here (or in the services behind it) may act on
// PlayerKickEvent: that fires earlier and would corrupt the snapshot.
public final class FlightQuitListener implements Listener {

    private final SpawnOG plugin;
    private final FlightSnapshotStore snapshotStore;
    private final FlightIntentStore flightIntentStore;
    // Null when NoClip-OG is not installed.
    private final NoClipAuthority noClipAuthority;

    public FlightQuitListener(SpawnOG plugin, FlightSnapshotStore snapshotStore, FlightIntentStore flightIntentStore,
            NoClipAuthority noClipAuthority)
    {

        this.plugin = plugin;
        this.snapshotStore = snapshotStore;
        this.flightIntentStore = flightIntentStore;
        this.noClipAuthority = noClipAuthority;

    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent event) {

        capture(event.getPlayer());

    }

    // Last quit wins: a flying player writes a snapshot, a grounded one erases
    // any leftover, so one record can never answer for two sessions.
    public void capture(Player player) {

        if (player.isFlying())
            snapshotStore.record(player, noClipAuthority != null && noClipAuthority.isNoClipping(player.getUniqueId()),
                    flightIntentStore.contains(player.getUniqueId()));
        else
            snapshotStore.clear(player.getUniqueId());

    }

    // Replays the quit capture for everyone still online, so a /reload or a
    // shutdown that skips quit events records flyers the same way a disconnect
    // does. Runs before the flight service revokes anything.
    public void captureAll() {

        plugin.getServer().getOnlinePlayers().forEach(this::capture);

    }

}
