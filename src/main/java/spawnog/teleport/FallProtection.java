package spawnog.teleport;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByBlockEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.spigotmc.event.entity.EntityMountEvent;

import spawnog.login.LocationSafety;

// Cancels the next fall for a player Spawn-OG put in the air. Kept apart from
// regional flight so it works without permissions, regions, or WorldGuard.
public final class FallProtection implements Listener {

    // The grant's tick guards it against being cleared by a teleport fired in
    // the very dispatch that granted it (a setback during the same move event).
    private final Map<UUID, Integer> immune = new HashMap<>();

    // Scoped to the one fall the plugin caused: spent on its landing, cleared
    // by whatever else ends it. Never a clock, so normal damage resumes then.
    public void grant(Player player) {

        if (player != null)
            immune.put(player.getUniqueId(), Bukkit.getCurrentTick());

    }

    public void clear(Player player) {

        if (player != null)
            immune.remove(player.getUniqueId());

    }

    public void close() {

        immune.clear();

    }

    // HIGHEST + ignoreCancelled: a cancelled event spends nothing, and little
    // runs after us that could un-cancel our own cancellation.
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {

        if (!(event.getEntity() instanceof Player player) || !isLandingDamage(event))
            return;
        // Cancelled even at zero final damage, so the plugin-caused landing
        // costs no hurt animation or absorption hearts either.
        if (immune.remove(player.getUniqueId()) != null)
            event.setCancelled(true);

    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {

        if (immune.isEmpty() || event.getTo() == null)
            return;

        Player player = event.getPlayer();
        if (!immune.containsKey(player.getUniqueId()))
            return;
        // getTo(): getLocation() is pre-move and this packet's damage fires
        // after us; positive fallDistance tells a live fall from a landing.
        if (!player.isFlying() && player.getFallDistance() <= 0.0F && holdsThePlayer(event.getTo()))
            immune.remove(player.getUniqueId());

    }

    // Grabbing a ladder or vine ends a fall just like ground or water does.
    private static boolean holdsThePlayer(Location to) {

        return LocationSafety.isSupported(to) || Tag.CLIMBABLE.isTagged(to.getBlock().getType());

    }

    // MONITOR: a mount or glide another plugin cancels never happened, and
    // the protection is then still owed for the ongoing fall.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onToggleGlide(EntityToggleGlideEvent event) {

        if (event.isGliding() && event.getEntity() instanceof Player player)
            clear(player);

    }

    // A mount catches the fall; any later fall damage belongs to the ride.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMount(EntityMountEvent event) {

        if (event.getEntity() instanceof Player player)
            clear(player);

    }

    // A teleport abandons the owed drop; portals fire a plain teleport event
    // too, so this also covers them.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {

        // A grant made earlier in this same dispatch (a setback teleport fired
        // from inside a move event) is still owed and survives.
        Integer grantedTick = immune.get(event.getPlayer().getUniqueId());
        if (grantedTick != null && grantedTick != Bukkit.getCurrentTick())
            clear(event.getPlayer());

    }

    // Losing creative or spectator mid-air strips flight inside vanilla, with
    // no plugin call to intercept, so protection is granted on the change itself.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameModeChange(PlayerGameModeChangeEvent event) {

        Player player = event.getPlayer();
        GameMode to = event.getNewGameMode();
        // Gamemode flight makes falls harmless; an unspent grant must not ride
        // along and absorb some later survival fall.
        if (to == GameMode.CREATIVE || to == GameMode.SPECTATOR) {

            clear(player);
            return;

        }

        GameMode from = player.getGameMode();
        if (from != GameMode.CREATIVE && from != GameMode.SPECTATOR)
            return;
        if (!player.isInsideVehicle() && !LocationSafety.isSupported(player.getLocation()))
            grant(player);

    }

    // A death ends whatever fall was owed; the grant must not survive into the
    // next life and absorb an unrelated fall there.
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {

        clear(event.getEntity());

    }

    // MONITOR: runs after the flight service's NORMAL quit handler, which
    // re-grants immunity while grounding flyers; clearing first would leak it.
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {

        clear(event.getPlayer());

    }

    // ignoreCancelled: a cancelled kick leaves the player online and possibly
    // still falling, so the protection is still owed.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onKick(PlayerKickEvent event) {

        clear(event.getPlayer());

    }

    // A stalagmite landing is CONTACT, not FALL, and is the most damaging way a
    // plugin-caused drop can end; both are outcomes of losing flight in the air.
    private static boolean isLandingDamage(EntityDamageEvent event) {

        if (event.getCause() == EntityDamageEvent.DamageCause.FALL)
            return true;

        return event.getCause() == EntityDamageEvent.DamageCause.CONTACT
                && event instanceof EntityDamageByBlockEvent byBlock && byBlock.getDamager() != null
                && byBlock.getDamager().getType() == Material.POINTED_DRIPSTONE;

    }

}
