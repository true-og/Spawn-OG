package spawnog.teleport;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import spawnog.login.LocationSafety;

// Cancels the next fall for a player Spawn-OG put in the air. Kept apart from
// regional flight so it works without permissions, regions, or WorldGuard.
public final class FallProtection implements Listener {

    private final Set<UUID> immune = new HashSet<>();

    // Held until the player is standing on something, so an arbitrarily long
    // drop is covered, and spent on the first fall damage that lands.
    public void grant(Player player) {

        if (player != null)
            immune.add(player.getUniqueId());

    }

    public void clear(Player player) {

        if (player != null)
            immune.remove(player.getUniqueId());

    }

    public void close() {

        immune.clear();

    }

    // Cancelled elsewhere already, so the protection is still owed and is not
    // spent on a fall that was never going to hurt.
    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {

        if (event.getCause() != EntityDamageEvent.DamageCause.FALL || !(event.getEntity() instanceof Player player))
            return;
        if (immune.remove(player.getUniqueId()))
            event.setCancelled(true);

    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {

        if (immune.isEmpty())
            return;

        Player player = event.getPlayer();
        if (immune.contains(player.getUniqueId()) && !player.isFlying()
                && LocationSafety.isSupported(player.getLocation()))
            immune.remove(player.getUniqueId());

    }

    // MONITOR: runs after the flight service's NORMAL quit handler, which
    // re-grants immunity while grounding flyers; clearing first would leak it.
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {

        clear(event.getPlayer());

    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onKick(PlayerKickEvent event) {

        clear(event.getPlayer());

    }

}
