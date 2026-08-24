package spawnog.login;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;

// Damage shield for players mid-rescue. Plugin-owned state, never the
// persisted invulnerable flag, so a crash can never bake it into playerdata.
public final class LoginShield implements Listener {

    private final Set<UUID> shielded = new HashSet<>();

    public void protect(Player player) {

        if (player != null)
            shielded.add(player.getUniqueId());

    }

    public void release(Player player) {

        if (player != null)
            shielded.remove(player.getUniqueId());

    }

    public void close() {

        shielded.clear();

    }

    // LOWEST, so the cancellation lands before anyone else looks: fall
    // protection keeps its one-shot token for the drop after the shield lifts,
    // and combat taggers listening with ignoreCancelled never see shielded hits.
    // The void still kills, matching what vanilla invulnerability let through.
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {

        if (!(event.getEntity() instanceof Player player) || !shielded.contains(player.getUniqueId()))
            return;
        if (event.getCause() != EntityDamageEvent.DamageCause.VOID)
            event.setCancelled(true);

    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {

        release(event.getEntity());

    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {

        release(event.getPlayer());

    }

}
