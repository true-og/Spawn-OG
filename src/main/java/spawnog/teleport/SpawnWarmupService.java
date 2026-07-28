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
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import spawnog.SpawnOG;

// Owns the countdown between /spawn and the teleport that follows it. Moving
// out of the block the countdown started in cancels the teleport; taking damage
// does not, so a player being hit while standing still still gets pulled to
// spawn.
public final class SpawnWarmupService implements Listener {

    public static final int WARMUP_SECONDS = 5;

    private static final long WARMUP_TICKS = 20L * WARMUP_SECONDS;

    private final SpawnOG plugin;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final Map<UUID, PendingTeleport> pendingTeleports = new HashMap<>();

    public SpawnWarmupService(SpawnOG plugin) {

        this.plugin = plugin;

    }

    public void schedule(Player player, Location destination) {

        UUID playerId = player.getUniqueId();
        PendingTeleport replaced = pendingTeleports.remove(playerId);
        if (replaced != null)
            replaced.task().cancel();

        send(player, "locale.spawnWarmup", "<gold>Teleporting to spawn in <red><seconds> seconds...</red></gold>",
                Placeholder.unparsed("seconds", String.valueOf(WARMUP_SECONDS)));

        BukkitTask task = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {

            // Drop the pending entry before teleporting: PlayerTeleportEvent extends
            // PlayerMoveEvent, so leaving it in place would make the teleport cancel
            // itself the moment it fires.
            pendingTeleports.remove(playerId);
            if (player.isOnline())
                player.teleportAsync(destination);

        }, WARMUP_TICKS);

        pendingTeleports.put(playerId, new PendingTeleport(task, player.getLocation().clone()));

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

    @EventHandler
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
