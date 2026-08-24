package spawnog.flight;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import spawnog.SpawnOG;
import spawnog.login.LoginMigrationService;
import spawnog.login.NoClipAuthority;

// Captures the flight snapshot at LOWEST, before grounding and NoClip cleanup.
// Kicks funnel into this quit event; a PlayerKickEvent handler would corrupt it.
public final class FlightQuitListener implements Listener {

    private final SpawnOG plugin;
    private final FlightSnapshotStore snapshotStore;
    private final FlightIntentStore flightIntentStore;
    private final LoginMigrationService loginMigrationService;
    // Null when NoClip-OG is not installed.
    private final NoClipAuthority noClipAuthority;

    public FlightQuitListener(SpawnOG plugin, FlightSnapshotStore snapshotStore, FlightIntentStore flightIntentStore,
            LoginMigrationService loginMigrationService, NoClipAuthority noClipAuthority)
    {

        this.plugin = plugin;
        this.snapshotStore = snapshotStore;
        this.flightIntentStore = flightIntentStore;
        this.loginMigrationService = loginMigrationService;
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
        // A pending rescue holds the airborne evidence: the player is not
        // flying mid-transaction, and clearing would erase the retry.
        else if (loginMigrationService == null || !loginMigrationService.isPending(player))
            snapshotStore.clear(player.getUniqueId());

    }

    // Replays the quit capture for everyone online, so a /reload or shutdown
    // that skips quit events records flyers before the flight service revokes.
    // Batched: per-player writes would rewrite the whole file once each.
    public void captureAll() {

        snapshotStore.batch("shutdown flight snapshots",
                () -> plugin.getServer().getOnlinePlayers().forEach(this::capture));

    }

}
