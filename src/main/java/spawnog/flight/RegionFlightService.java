package spawnog.flight;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.util.Location;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import com.sk89q.worldguard.protection.regions.RegionQuery;
import org.bukkit.Color;
import org.bukkit.Particle;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.util.Vector;

import spawnog.SpawnOG;
import spawnog.login.LoginMigrationService;

public final class RegionFlightService implements Listener {

    public static final String FLIGHT_PERMISSION = "spawnog.flight";
    public static final String LEGACY_FLIGHT_PERMISSION = "wgamemode.fly";
    public static final String BYPASS_PERMISSION = "spawnog.flight.bypass";
    public static final String LEGACY_BYPASS_PERMISSION = "wgamemode.fly.bypass";

    private static final long PARTICLE_INITIAL_DELAY_TICKS = 10L;
    private static final long PARTICLE_PERIOD_TICKS = 6L;
    private static final double PARTICLE_RADIUS = 0.12D;
    private static final double PARTICLE_Y_OFFSET = -0.12D;
    private static final double PARTICLE_BACK_OFFSET = 0.45D;
    private static final Color[] RAINBOW_TRAIL_COLORS = { Color.fromRGB(255, 82, 82), Color.fromRGB(255, 171, 64),
            Color.fromRGB(255, 238, 88), Color.fromRGB(105, 240, 174), Color.fromRGB(64, 196, 255),
            Color.fromRGB(124, 77, 255), Color.fromRGB(255, 64, 129) };

    private final SpawnOG plugin;
    private final LoginMigrationService loginMigrationService;
    private final FlightIntentStore flightIntentStore;
    private final Map<String, RuleKind> regionRules = new HashMap<>();
    private final Map<UUID, FlightOverride> flightOverrides = new HashMap<>();
    private final Set<UUID> fallImmunity = new HashSet<>();
    private final Set<UUID> scheduledRefreshes = new HashSet<>();
    private int particleStep;

    public RegionFlightService(SpawnOG plugin, LoginMigrationService loginMigrationService,
            FlightIntentStore flightIntentStore)
    {

        this.plugin = plugin;
        this.loginMigrationService = loginMigrationService;
        this.flightIntentStore = flightIntentStore;
        loginMigrationService.addCompletionListener(this::scheduleRefresh);
        loadRules();
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::spawnFlightParticles, PARTICLE_INITIAL_DELAY_TICKS,
                PARTICLE_PERIOD_TICKS);

    }

    public boolean canToggleFlight(Player player) {

        if (player == null || !hasFlightPermission(player))
            return false;
        RegionRule rule = currentRule(player);
        return rule != null && rule.kind() == RuleKind.FLY;

    }

    public boolean toggleFlight(Player player) {

        RegionRule rule = currentRule(player);
        if (rule == null || rule.kind() != RuleKind.FLY)
            return player.getAllowFlight();

        UUID playerId = player.getUniqueId();
        FlightOverride previous = flightOverrides.get(playerId);
        boolean originalAllowFlight;
        if (previous == null || previous.kind() != RuleKind.FLY) {

            restoreOverride(player, previous);
            originalAllowFlight = player.getAllowFlight();

        } else {

            originalAllowFlight = previous.originalAllowFlight();

        }

        boolean enable = !player.getAllowFlight();
        if (enable) {

            player.setAllowFlight(true);

        } else {

            stopFlyingSafely(player);
            player.setAllowFlight(false);

        }

        if (player.getAllowFlight() == originalAllowFlight) {

            flightOverrides.remove(playerId);

        } else {

            flightOverrides.put(playerId,
                    new FlightOverride(originalAllowFlight, player.getAllowFlight(), rule.regionId(), RuleKind.FLY));

        }

        // The choice outlives the session: an enabled toggle is re-armed on the
        // next login or region entry, a disabled one stays off until asked again.
        if (enable)
            flightIntentStore.record(player);
        else
            flightIntentStore.clear(player);

        return player.getAllowFlight();

    }

    // Puts a player back into the air after a plugin-driven teleport when they
    // were flying beforehand. The teleport's region refresh has already run by
    // the time this is called, so re-enabling here is not overwritten. Refuses
    // when the player lacks flight permission or the destination forbids flight.
    public boolean resumeFlight(Player player) {

        if (player == null || !player.isOnline())
            return false;

        RegionRule rule = currentRule(player);
        if (rule != null && rule.kind() == RuleKind.NOFLY && !hasBypass(player))
            return false;

        // Flight already allowed by another authority (creative, bypass, an
        // active override): only the airborne flag needs restoring.
        if (player.getAllowFlight()) {

            player.setFlying(true);
            return true;

        }

        if (!hasFlightPermission(player))
            return false;

        // Inside a fly region, go through the toggle so reconciliation keeps the
        // override; elsewhere the raw ability is enough, since refresh only acts
        // where rules or overrides exist.
        if (rule != null && rule.kind() == RuleKind.FLY)
            toggleFlight(player);
        else
            player.setAllowFlight(true);

        player.setFlying(true);
        return true;

    }

    public void refresh(Player player) {

        if (player == null || !player.isOnline())
            return;
        // While a login migration is in flight, do not reschedule: the completion
        // listener registered in the constructor refreshes once it resolves.
        // Re-polling here previously spun the scheduler within a single tick and
        // froze the server, because the migration could only resolve on a later tick.
        if (loginMigrationService.isPending(player))
            return;

        UUID playerId = player.getUniqueId();
        RegionRule rule = currentRule(player);
        FlightOverride previous = flightOverrides.get(playerId);

        if (rule == null) {

            restoreOverride(player, previous);
            return;

        }

        if (rule.kind() == RuleKind.FLY) {

            if (previous != null && previous.kind() == RuleKind.NOFLY)
                restoreOverride(player, previous);
            previous = flightOverrides.get(playerId);
            if (previous == null) {

                rearmPersistedFlight(player, rule);
                return;

            }

            if (previous.kind() == RuleKind.FLY) {

                if (player.getAllowFlight() != previous.appliedAllowFlight()) {

                    if (!previous.appliedAllowFlight())
                        stopFlyingSafely(player);
                    player.setAllowFlight(previous.appliedAllowFlight());

                }

                if (!previous.regionId().equals(rule.regionId()))
                    flightOverrides.put(playerId, new FlightOverride(previous.originalAllowFlight(),
                            previous.appliedAllowFlight(), rule.regionId(), RuleKind.FLY));

            }

            return;

        }

        if (hasBypass(player)) {

            restoreOverride(player, previous);
            return;

        }

        if (previous != null && previous.kind() == RuleKind.FLY) {

            restoreOverride(player, previous);
            previous = null;

        }

        if (previous == null) {

            previous = new FlightOverride(player.getAllowFlight(), false, rule.regionId(), RuleKind.NOFLY);
            flightOverrides.put(playerId, previous);

        } else if (previous != null && previous.kind() == RuleKind.NOFLY
                && !previous.regionId().equals(rule.regionId()))
        {

            flightOverrides.put(playerId,
                    new FlightOverride(previous.originalAllowFlight(), false, rule.regionId(), RuleKind.NOFLY));

        }

        if (player.getAllowFlight()) {

            stopFlyingSafely(player);
            player.setAllowFlight(false);

        }

    }

    public void restore(Player player) {

        if (player == null)
            return;
        restoreOverride(player, flightOverrides.get(player.getUniqueId()));
        fallImmunity.remove(player.getUniqueId());

    }

    public void close() {

        Set.copyOf(flightOverrides.keySet()).forEach(playerId -> {

            Player player = plugin.getServer().getPlayer(playerId);
            if (player != null)
                restoreOverride(player, flightOverrides.get(playerId));

        });
        flightOverrides.clear();
        fallImmunity.clear();
        scheduledRefreshes.clear();

    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {

        scheduleRefresh(event.getPlayer());

    }

    @EventHandler
    public void onTeleport(PlayerTeleportEvent event) {

        scheduleRefresh(event.getPlayer());

    }

    @EventHandler
    public void onChangedWorld(PlayerChangedWorldEvent event) {

        // Essentials-OG resets flight during this same event. Reconcile one tick
        // later so Spawn-OG remains the final authority for regional flight.
        scheduleRefresh(event.getPlayer());

    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {

        scheduleRefresh(event.getPlayer());

    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {

        if (event.getTo() == null)
            return;

        Player player = event.getPlayer();
        boolean sameBlock = event.getFrom().getWorld() == event.getTo().getWorld()
                && event.getFrom().getBlockX() == event.getTo().getBlockX()
                && event.getFrom().getBlockY() == event.getTo().getBlockY()
                && event.getFrom().getBlockZ() == event.getTo().getBlockZ();
        if (!sameBlock)
            refresh(player);

        if (fallImmunity.contains(player.getUniqueId()) && isOnSafeSurface(player) && !player.isFlying())
            fallImmunity.remove(player.getUniqueId());

    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {

        restore(event.getPlayer());

    }

    @EventHandler
    public void onKick(PlayerKickEvent event) {

        restore(event.getPlayer());

    }

    @EventHandler
    public void onDamage(EntityDamageEvent event) {

        if (event.getCause() != EntityDamageEvent.DamageCause.FALL || !(event.getEntity() instanceof Player player))
            return;
        if (fallImmunity.remove(player.getUniqueId()))
            event.setCancelled(true);

    }

    private void loadRules() {

        if (plugin.getConfig().getConfigurationSection("flight.regions") == null) {

            regionRules.put("spawn", RuleKind.FLY);
            regionRules.put("warzone", RuleKind.NOFLY);
            return;

        }

        plugin.getConfig().getConfigurationSection("flight.regions").getValues(false).forEach((regionId, value) -> {

            RuleKind kind;
            try {

                kind = RuleKind.valueOf(String.valueOf(value).toUpperCase(Locale.ROOT));

            } catch (IllegalArgumentException error) {

                throw new IllegalArgumentException(
                        "Invalid flight rule for region '" + regionId + "'. Expected fly or nofly.", error);

            }

            regionRules.put(regionId.toLowerCase(Locale.ROOT), kind);

        });

    }

    // Whether refresh() will re-arm flight for the player at the location: a
    // stored intent, flight permission, and a fly rule all present. Login safety
    // asks this before rescuing an airborne player, because a flyer who gets
    // their wings back in place has nothing to be rescued from.
    public boolean willResumeFlightAt(Player player, org.bukkit.Location location) {

        if (player == null || location == null)
            return false;
        if (!flightIntentStore.contains(player.getUniqueId()) || !hasFlightPermission(player))
            return false;

        RegionRule rule = ruleAt(location);
        return rule != null && rule.kind() == RuleKind.FLY;

    }

    // Re-grants flight inside a fly region for a player whose stored intent says
    // /fly was left on. The live ability is dropped on quit (and by login
    // normalization), so without this the toggle would not survive a relog.
    private void rearmPersistedFlight(Player player, RegionRule rule) {

        if (!flightIntentStore.contains(player.getUniqueId()) || !hasFlightPermission(player))
            return;

        // Another authority (creative mode, a bypass, another plugin) already
        // allows flight; there is nothing to arm and no override to track.
        if (player.getAllowFlight())
            return;

        player.setAllowFlight(true);
        flightOverrides.put(player.getUniqueId(), new FlightOverride(false, true, rule.regionId(), RuleKind.FLY));

        // A player re-armed in mid-air relogged while flying and is already
        // falling, so put them back into flight instead of letting them drop.
        if (!isOnSafeSurface(player))
            player.setFlying(true);

    }

    private RegionRule currentRule(Player player) {

        return ruleAt(player.getLocation());

    }

    private RegionRule ruleAt(org.bukkit.Location bukkitLocation) {

        RegionContainer container = WorldGuard.getInstance().getPlatform().getRegionContainer();
        RegionQuery query = container.createQuery();
        Location location = BukkitAdapter.adapt(bukkitLocation);
        ApplicableRegionSet applicable = query.getApplicableRegions(location);
        RegionRule best = null;

        for (ProtectedRegion region : applicable.getRegions()) {

            String regionId = region.getId().toLowerCase(Locale.ROOT);
            RuleKind kind = regionRules.get(regionId);
            if (kind == null)
                continue;

            RegionRule candidate = new RegionRule(regionId, kind, region.getPriority());
            if (best == null || candidate.priority() > best.priority()
                    || candidate.priority() == best.priority() && candidate.regionId().compareTo(best.regionId()) < 0)
                best = candidate;

        }

        return best;

    }

    private boolean hasBypass(Player player) {

        return player.hasPermission(BYPASS_PERMISSION) || player.hasPermission(LEGACY_BYPASS_PERMISSION);

    }

    private boolean hasFlightPermission(Player player) {

        return player.hasPermission(FLIGHT_PERMISSION) || player.hasPermission(LEGACY_FLIGHT_PERMISSION);

    }

    private void restoreOverride(Player player, FlightOverride state) {

        if (state == null)
            return;
        if (player.isFlying() && !state.originalAllowFlight())
            stopFlyingSafely(player);
        if (player.getAllowFlight() != state.originalAllowFlight())
            player.setAllowFlight(state.originalAllowFlight());
        flightOverrides.remove(player.getUniqueId());

    }

    private void stopFlyingSafely(Player player) {

        if (player.isFlying()) {

            fallImmunity.add(player.getUniqueId());
            player.setFlying(false);

        }

    }

    private boolean isOnSafeSurface(Player player) {

        org.bukkit.Location feet = player.getLocation();
        return isSafeSurface(feet.clone().subtract(0.0D, 0.08D, 0.0D))
                || isSafeSurface(feet.clone().subtract(0.0D, 0.51D, 0.0D));

    }

    private boolean isSafeSurface(org.bukkit.Location location) {

        Block block = location.getBlock();
        return block.isLiquid() || !block.isPassable();

    }

    private void scheduleRefresh(Player player) {

        if (!plugin.isEnabled())
            return;

        UUID playerId = player.getUniqueId();
        if (!scheduledRefreshes.add(playerId))
            return;
        // Paper's scheduler executes zero-delay tasks scheduled from within a task
        // in the same tick, so a one-tick delay is required to guarantee the
        // current tick always finishes before the refresh runs.
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {

            scheduledRefreshes.remove(playerId);
            refresh(player);

        }, 1L);

    }

    private void spawnFlightParticles() {

        particleStep++;
        plugin.getServer().getOnlinePlayers().forEach(player -> {

            FlightOverride state = flightOverrides.get(player.getUniqueId());
            if (state == null || state.kind() != RuleKind.FLY || !player.getAllowFlight() || !player.isFlying())
                return;

            org.bukkit.Location location = flightParticleLocation(player);
            if (hasBypass(player))
                spawnRainbowParticles(player, location);
            else
                player.getWorld().spawnParticle(Particle.CLOUD, location, 4, PARTICLE_RADIUS, 0.025D, PARTICLE_RADIUS,
                        0.004D);

        });

    }

    private org.bukkit.Location flightParticleLocation(Player player) {

        org.bukkit.Location feet = player.getLocation();
        Vector behind = feet.getDirection();
        behind.setY(0.0D);
        if (behind.lengthSquared() > 0.0D)
            feet.add(behind.normalize().multiply(-PARTICLE_BACK_OFFSET));
        return feet.add(0.0D, PARTICLE_Y_OFFSET, 0.0D);

    }

    private void spawnRainbowParticles(Player player, org.bukkit.Location feet) {

        int count = 12;
        double radius = 0.18D;
        Vector trailDirection = player.getLocation().getDirection();
        trailDirection.setY(0.0D);
        if (trailDirection.lengthSquared() > 0.0D)
            trailDirection.normalize().multiply(-0.14D);
        else
            trailDirection = null;

        for (int i = 0; i < count; i++) {

            int colorIndex = Math.floorMod(particleStep + i, RAINBOW_TRAIL_COLORS.length);
            double angle = particleStep * 0.42D + i * Math.PI * 2.0D / count;
            org.bukkit.Location particleLocation = feet.clone().add(Math.cos(angle) * radius,
                    Math.sin(angle * 2.0D) * 0.04D, Math.sin(angle) * radius);
            if (trailDirection != null)
                particleLocation.add(trailDirection.clone().multiply(i % 4));
            Particle.DustOptions dust = new Particle.DustOptions(RAINBOW_TRAIL_COLORS[colorIndex], 0.85F);
            player.getWorld().spawnParticle(Particle.REDSTONE, particleLocation, 1, 0.015D, 0.015D, 0.015D, 0.0D, dust);

        }

    }

    private enum RuleKind {
        FLY, NOFLY
    }

    private record RegionRule(String regionId, RuleKind kind, int priority) {
    }

    private record FlightOverride(boolean originalAllowFlight, boolean appliedAllowFlight, String regionId,
            RuleKind kind)
    {
    }

}
