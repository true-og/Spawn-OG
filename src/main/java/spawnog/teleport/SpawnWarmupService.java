package spawnog.teleport;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.scheduler.BukkitTask;

import spawnog.SpawnOG;
import spawnog.login.LocationSafety;

// Owns the countdown between /spawn and its teleport. Leaving the starting
// block cancels it; damage does not, so a player under attack still gets pulled.
public final class SpawnWarmupService implements Listener {

    public static final int WARMUP_SECONDS = 5;

    private static final long WARMUP_TICKS = 20L * WARMUP_SECONDS;

    private final SpawnOG plugin;
    private final FallProtection fallProtection;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final Map<UUID, PendingTeleport> pendingTeleports = new HashMap<>();

    public SpawnWarmupService(SpawnOG plugin, FallProtection fallProtection) {

        this.plugin = plugin;
        this.fallProtection = fallProtection;

    }

    public void schedule(Player player, Location destination) {

        UUID playerId = player.getUniqueId();
        PendingTeleport replaced = pendingTeleports.remove(playerId);
        if (replaced != null)
            replaced.task().cancel();

        send(player, "locale.spawnWarmup", "<gold>Teleporting to spawn in <red><seconds> seconds...</red></gold>",
                Placeholder.unparsed("seconds", String.valueOf(WARMUP_SECONDS)));

        BukkitTask task = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {

            // Drop the pending entry first: PlayerTeleportEvent extends
            // PlayerMoveEvent, so the teleport would otherwise cancel itself.
            pendingTeleports.remove(playerId);
            teleport(player, destination);

        }, WARMUP_TICKS);

        // For a rider the move events carry the vehicle's position, so the
        // origin must be the vehicle's too or the first nudge false-cancels.
        Location origin = player.isInsideVehicle() && player.getVehicle() != null
                ? player.getVehicle().getLocation().clone()
                : player.getLocation().clone();
        pendingTeleports.put(playerId, new PendingTeleport(task, origin));

    }

    // An admin-initiated pull skips the countdown: the target's ordinary
    // walking would otherwise cancel a teleport they never asked for.
    public void teleportNow(Player player, Location destination) {

        cancelSilently(player);
        teleport(player, destination);

    }

    private void teleport(Player player, Location destination) {

        if (!player.isOnline())
            return;
        // The destination world can unload during the countdown; teleporting
        // into it would throw and strand the player with no message.
        if (!destination.isWorldLoaded()) {

            send(player, "locale.spawnWorldUnloaded",
                    "<red>The spawn world is not loaded, so you cannot be teleported there.</red>");
            return;

        }

        player.teleportAsync(destination).whenComplete((success, error) -> {

            if (!plugin.isEnabled())
                return;

            plugin.getServer().getScheduler().runTask(plugin, () -> {

                if (!player.isOnline())
                    return;

                if (!Boolean.TRUE.equals(success) || error != null) {

                    send(player, "locale.spawnTeleportFailed", "<red>Your teleport to spawn failed.</red>");
                    return;

                }

                // A spawn set in the air still gets a covered landing.
                if (!LocationSafety.isSupported(player.getLocation())) {

                    fallProtection.grant(player);
                    player.setFallDistance(0.0F);

                }

            });

        });

    }

    public boolean isPending(Player player) {

        return player != null && pendingTeleports.containsKey(player.getUniqueId());

    }

    public void cancelSilently(Player player) {

        PendingTeleport pending = pendingTeleports.remove(player.getUniqueId());
        if (pending != null)
            pending.task().cancel();

    }

    public void close() {

        pendingTeleports.values().forEach(pending -> pending.task().cancel());
        pendingTeleports.clear();

    }

    // ignoreCancelled: a move another plugin bounced back never happened.
    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {

        if (pendingTeleports.isEmpty() || event.getTo() == null)
            return;

        Player player = event.getPlayer();
        PendingTeleport pending = pendingTeleports.get(player.getUniqueId());
        if (pending == null || !leftOrigin(pending.origin(), event.getTo()))
            return;

        pendingTeleports.remove(player.getUniqueId());
        pending.task().cancel();
        send(player, "locale.spawnCancelled", "<red>Teleport to spawn cancelled because you moved.</red>");

    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {

        cancelSilently(event.getPlayer());

    }

    @EventHandler
    public void onKick(PlayerKickEvent event) {

        cancelSilently(event.getPlayer());

    }

    // Any other teleport (a /spawnback return, a portal) supersedes the
    // warmup; without this it would rip the player back seconds later.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {

        cancelSilently(event.getPlayer());

    }

    // A respawn relocates without firing a move event; the countdown must not
    // survive the death and yank the fresh spawn.
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {

        cancelSilently(event.getEntity());

    }

    private boolean leftOrigin(Location origin, Location current) {

        // Block granularity, not exact coordinates: looking around or the sub-block
        // drift the client sends while standing still must not cancel the teleport.
        return origin.getWorld() != current.getWorld() || origin.getBlockX() != current.getBlockX()
                || origin.getBlockY() != current.getBlockY() || origin.getBlockZ() != current.getBlockZ();

    }

    private void send(Player player, String path, String fallback, TagResolver... placeholders) {

        String message = plugin.getConfig().getString(path, fallback);
        Component component = miniMessage.deserialize(message, placeholders);
        player.sendMessage(component);

    }

    private record PendingTeleport(BukkitTask task, Location origin) {
    }

}
