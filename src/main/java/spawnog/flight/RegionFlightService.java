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
import org.bukkit.GameMode;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.util.Vector;

import spawnog.SpawnOG;
import spawnog.login.LocationSafety;
import spawnog.login.LoginMigrationService;
import spawnog.teleport.FallProtection;

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
    private final FallProtection fallProtection;
    private final Map<String, RuleKind> regionRules = new HashMap<>();
    private final Map<UUID, FlightOverride> flightOverrides = new HashMap<>();
    // Flyers on a grant belonging to no region, so a /spawnback return does not
    // drop them. Revoked by landing rather than by leaving a region.
    private final Set<UUID> landingGrants = new HashSet<>();
    private final Set<UUID> scheduledRefreshes = new HashSet<>();
    private int particleStep;

    public RegionFlightService(SpawnOG plugin, LoginMigrationService loginMigrationService,
            FlightIntentStore flightIntentStore, FallProtection fallProtection)
    {

        this.plugin = plugin;
        this.loginMigrationService = loginMigrationService;
        this.flightIntentStore = flightIntentStore;
        this.fallProtection = fallProtection;
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

    // Puts a player back into the air after a plugin teleport when they flew
    // before. False: left airborne without flight, so the caller can say why.
    public boolean resumeFlight(Player player) {

        if (player == null || !player.isOnline())
            return false;

        // Reconcile where the player now stands first: the departed region's
        // override would otherwise be restored and take the flight back a tick later.
        refresh(player);

        UUID playerId = player.getUniqueId();
        RegionRule rule = currentRule(player);
        if (rule != null && rule.kind() == RuleKind.NOFLY && !hasBypass(player))
            return false;

        // Flight already allowed by another authority (creative, bypass, an
        // intent the refresh above re-armed): only the airborne flag is missing.
        if (player.getAllowFlight()) {

            player.setFlying(true);
            return true;

        }

        if (!hasFlightPermission(player))
            return false;

        // Every grant is recorded so region exit, nofly, and disconnect can take
        // it back; unrecorded it would persist as permanent free flight.
        if (rule != null && rule.kind() == RuleKind.FLY) {

            player.setAllowFlight(true);
            flightOverrides.put(playerId, new FlightOverride(false, true, rule.regionId(), RuleKind.FLY));
            player.setFlying(true);
            return true;

        }

        // On solid ground there is nothing to resume: the player is not falling,
        // so a grant here would only have to be taken back again.
        if (isOnSafeSurface(player))
            return true;

        // No region rule speaks for this spot, so the grant is a loan that lasts
        // until the player is back on the ground rather than a lasting ability.
        player.setAllowFlight(true);
        landingGrants.add(playerId);
        player.setFlying(true);
        return true;

    }

    public void refresh(Player player) {

        refresh(player, player == null ? null : player.getLocation());

    }

    // Judges at an explicit position: during move/teleport events the entity
    // still reports the pre-move spot, one step behind at region borders.
    public void refresh(Player player, org.bukkit.Location at) {

        if (player == null || !player.isOnline() || at == null)
            return;
        // While a login migration is pending, do not reschedule: its completion
        // listener refreshes. Re-polling once spun the scheduler and froze the server.
        if (loginMigrationService.isPending(player))
            return;
        if (releaseGamemodeFlyer(player))
            return;

        UUID playerId = player.getUniqueId();
        RegionRule rule = ruleAt(at);

        // The loan ends on touchdown or inside a denying region. Revoked before
        // the branches read abilities, so a nofly never records the loan as owned.
        if (landingGrants.contains(playerId) && (LocationSafety.isSupported(at)
                || rule != null && rule.kind() == RuleKind.NOFLY && !hasBypass(player)))
            revokeLandingGrant(player);

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

        } else if (previous.kind() == RuleKind.NOFLY && !previous.regionId().equals(rule.regionId())) {

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
        if (releaseGamemodeFlyer(player))
            return;

        // The LOWEST-priority quit snapshot already holds the airborne evidence,
        // so grounding loses nothing. Loan before override: a loan is never saved as
        // owned.
        revokeLandingGrant(player);
        restoreOverride(player, flightOverrides.get(player.getUniqueId()));

    }

    // Creative and spectator flight is gamemode-derived, never regional:
    // bookkeeping is dropped, not applied, so overrides neither ground nor leak.
    private boolean releaseGamemodeFlyer(Player player) {

        GameMode gamemode = player.getGameMode();
        if (gamemode != GameMode.CREATIVE && gamemode != GameMode.SPECTATOR)
            return false;

        landingGrants.remove(player.getUniqueId());
        flightOverrides.remove(player.getUniqueId());
        return true;

    }

    public void close() {

        Set<UUID> granted = new HashSet<>(landingGrants);
        granted.addAll(flightOverrides.keySet());
        // Through restore(), so a shutdown records the players it grounds exactly
        // like a disconnect does.
        granted.forEach(playerId -> {

            Player player = plugin.getServer().getPlayer(playerId);
            if (player != null)
                restore(player);

        });
        flightOverrides.clear();
        landingGrants.clear();
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
        // Judged where the player is going. Teleports never reach this handler
        // (own HandlerList); onTeleport's scheduled refresh covers them.
        if (!sameBlock)
            refresh(player, event.getTo());

    }

    // No PlayerKickEvent handler on purpose: a kick precedes the quit event the
    // snapshot reads from; grounding early would erase the airborne evidence.
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {

        restore(event.getPlayer());

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

    // Grants regional flight inside a permitted fly region, recording grant and
    // intent like a /fly toggle. The restore path for a rescued /fly flyer.
    public boolean grantRegionFlight(Player player) {

        if (player == null || !player.isOnline() || !hasFlightPermission(player))
            return false;

        RegionRule rule = currentRule(player);
        if (rule == null || rule.kind() != RuleKind.FLY)
            return false;

        if (!player.getAllowFlight()) {

            player.setAllowFlight(true);
            flightOverrides.put(player.getUniqueId(), new FlightOverride(false, true, rule.regionId(), RuleKind.FLY));

        }

        player.setFlying(true);
        flightIntentStore.record(player);
        return true;

    }

    // The rule governing a location, or null when no configured region covers
    // it. Published so the restore matrix can judge a /spawnback destination.
    public RuleKind ruleKindAt(org.bukkit.Location location) {

        RegionRule rule = location == null ? null : ruleAt(location);
        return rule == null ? null : rule.kind();

    }

    // Re-grants flight in a fly region when stored intent says /fly was on; the
    // live ability dies on quit, so the toggle would not survive a relog.
    private void rearmPersistedFlight(Player player, RegionRule rule) {

        if (!flightIntentStore.contains(player.getUniqueId()) || !hasFlightPermission(player))
            return;

        // Another authority already allows flight, so there is no override to
        // track; an airborne player still needs the flying flag put back.
        if (!player.getAllowFlight()) {

            player.setAllowFlight(true);
            flightOverrides.put(player.getUniqueId(), new FlightOverride(false, true, rule.regionId(), RuleKind.FLY));

        }

        // A player re-armed in mid-air relogged while flying and is already
        // falling, so put them back into flight instead of letting them drop.
        if (!player.isFlying() && !isOnSafeSurface(player))
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

    public boolean hasBypass(Player player) {

        return player.hasPermission(BYPASS_PERMISSION) || player.hasPermission(LEGACY_BYPASS_PERMISSION);

    }

    public boolean hasFlightPermission(Player player) {

        return player.hasPermission(FLIGHT_PERMISSION) || player.hasPermission(LEGACY_FLIGHT_PERMISSION);

    }

    private void revokeLandingGrant(Player player) {

        if (!landingGrants.remove(player.getUniqueId()))
            return;
        stopFlyingSafely(player);
        player.setAllowFlight(false);

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

            fallProtection.grant(player);
            player.setFlying(false);

        }

    }

    private boolean isOnSafeSurface(Player player) {

        return LocationSafety.isSupported(player.getLocation());

    }

    private void scheduleRefresh(Player player) {

        if (!plugin.isEnabled())
            return;

        UUID playerId = player.getUniqueId();
        if (!scheduledRefreshes.add(playerId))
            return;
        // Paper runs zero-delay tasks scheduled from within a task in the same
        // tick, so a one-tick delay guarantees the current tick finishes first.
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

    public enum RuleKind {
        FLY, NOFLY
    }

    private record RegionRule(String regionId, RuleKind kind, int priority) {
    }

    private record FlightOverride(boolean originalAllowFlight, boolean appliedAllowFlight, String regionId,
            RuleKind kind)
    {
    }

}
