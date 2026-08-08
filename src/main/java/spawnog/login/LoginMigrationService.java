package spawnog.login;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import spawnog.SpawnOG;
import spawnog.flight.FlightMode;
import spawnog.flight.FlightRestorer;
import spawnog.flight.FlightSnapshotStore;
import spawnog.flight.FlightSnapshotStore.FlightSnapshot;
import spawnog.teleport.FallProtection;
import spawnog.world.ManagedWorlds;

public final class LoginMigrationService {

    public static final String BYPASS_PERMISSION = "spawnog.login-migration.bypass";

    private static final String AUTOPSY_REASON = "you were left in spectator mode by the OG:SMP autopsy";
    private static final String AIRBORNE_REASON = "you logged out mid-flight";
    private static final int DEFAULT_GRACE_SECONDS = 30;
    // Logins restore the exact quit position; this slack only absorbs
    // chunk-edge nudges, never a different place.
    private static final double SNAPSHOT_DISTANCE_SQUARED = 16.0D;

    private final SpawnOG plugin;
    private final AutopsyMigrationStore migrationStore;
    private final ReturnLocationStore returnLocationStore;
    private final FlightSnapshotStore flightSnapshotStore;
    private final GamemodePolicy gamemodePolicy;
    private final ManagedWorlds managedWorlds;
    private final FallProtection fallProtection;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final Map<UUID, PendingLogin> pendingLogins = new HashMap<>();
    private final List<Consumer<Player>> completionListeners = new ArrayList<>();
    // Decides whether and how a rescued flyer gets their flight back. Set after
    // construction because it is built around the flight service, which takes
    // this service in its own constructor.
    private FlightRestorer flightRestorer;

    public LoginMigrationService(SpawnOG plugin, AutopsyMigrationStore migrationStore,
            ReturnLocationStore returnLocationStore, FlightSnapshotStore flightSnapshotStore,
            GamemodePolicy gamemodePolicy, ManagedWorlds managedWorlds, FallProtection fallProtection)
    {

        this.plugin = plugin;
        this.migrationStore = migrationStore;
        this.returnLocationStore = returnLocationStore;
        this.flightSnapshotStore = flightSnapshotStore;
        this.gamemodePolicy = gamemodePolicy;
        this.managedWorlds = managedWorlds;
        this.fallProtection = fallProtection;

    }

    public boolean handleLogin(Player player, Location plannedDestination) {

        if (!plugin.getConfig().getBoolean("login-safety.enabled", true))
            return false;

        // A player who left mid-flight is unsafe by definition, regardless of
        // how safe the ground below looks: their flight was revoked on the way
        // out and nothing has decided yet whether they get it back. A snapshot
        // is only believed when the login it greets still looks like the quit
        // it recorded; a mismatch, a minigame arena, or an unmanaged world
        // discards it on the spot so it can never ambush a later login.
        FlightSnapshot snapshot = flightSnapshotStore.peek(player.getUniqueId());
        if (snapshot != null) {

            if (!isManagedWorld(player) || MinigameArenas.contains(player) || !corroborates(player, snapshot)) {

                flightSnapshotStore.clear(player.getUniqueId());
                plugin.getLogger().info("Discarded a flight snapshot for " + player.getName()
                        + " that did not match their login state.");

            } else

                return handleAirborneLogin(player, plannedDestination, snapshot);

        }

        return handleGroundedLogin(player, plannedDestination);

    }

    // A relog in flight: either the player still has the right to that flight
    // where they hang and holds the bypass, in which case it is resumed on the
    // spot, or they are pulled to spawn with /spawnback as the way back.
    private boolean handleAirborneLogin(Player player, Location plannedDestination, FlightSnapshot snapshot) {

        FlightMode mode = snapshot.mode();

        if (player.hasPermission(BYPASS_PERMISSION) && flightRestorer != null
                && flightRestorer.canResumeInPlace(mode, player, player.getLocation())
                && flightRestorer.resumeInPlace(mode, player))
        {

            player.setFallDistance(0.0F);
            // Re-asserted a tick later: other plugins reconcile abilities during
            // the join tick, and a flyer they briefly grounded must not fall.
            plugin.getServer().getScheduler().runTask(plugin, () -> {

                if (player.isOnline() && player.getAllowFlight() && !player.isFlying()
                        && !LocationSafety.isSupported(player.getLocation()))
                    player.setFlying(true);

            });
            flightSnapshotStore.clear(player.getUniqueId());
            send(player, "locale.flightResumed", "<gold>Welcome back. Your flight was resumed where you left.</gold>");
            return true;

        }

        Location destination = plannedDestination == null ? globalSpawn() : plannedDestination;
        if (destination == null) {

            // The snapshot is kept, so the next login can retry the rescue.
            protectInPlace(player, snapshot, player.isInvulnerable());
            send(player, "locale.loginSafetyFailed",
                    "<red>Your login location could not be made safe because the global spawn is not configured. Staff have been notified.</red>");
            plugin.getLogger().severe("Unable to safely migrate " + player.getName()
                    + " because spawns.global.location is not configured.");
            return true;

        }

        // Corroboration has already pinned the snapshot to this login: the
        // recorded spot is where the player hangs right now, and the recorded
        // gamemode is the one they carry.
        GameMode originalGamemode = snapshot.gamemode();
        Location origin = snapshot.location().clone();
        // Judged at the rescue destination, same as a grounded login: a
        // creative flyer rescued into the spawn creative region keeps creative
        // instead of being dropped to survival.
        boolean normalizeGamemode = !gamemodePolicy.mayUse(player, originalGamemode, destination)
                && plugin.getConfig().getBoolean("login-safety.normalize-non-staff-gamemode", true);
        beginTransaction(player, destination, new PendingLogin(player.isInvulnerable(), snapshot.allowFlight(), true,
                originalGamemode, origin, normalizeGamemode, false, LocationSafety.Issue.NONE, snapshot));
        return true;

    }

    private boolean handleGroundedLogin(Player player, Location plannedDestination) {

        boolean managedWorld = isManagedWorld(player);
        GameMode originalGamemode = player.getGameMode();
        boolean staff = player.hasPermission(BYPASS_PERMISSION);
        // Any non-survival login is normalized unless the player is entitled to
        // that gamemode where they logged in; being staff is not enough on its own.
        boolean mayKeepGamemode = gamemodePolicy.mayUse(player, originalGamemode, player.getLocation());
        boolean normalizeGamemode = managedWorld && !mayKeepGamemode
                && plugin.getConfig().getBoolean("login-safety.normalize-non-staff-gamemode", true);
        // A sanctioned spectator is working, not an abandoned autopsy state.
        // A minigame puts its own players into spectator legitimately, so a disconnect
        // from an arena looks exactly
        // like the abandoned season 1 state this migration exists to rescue. Migrating
        // one of those would burn the
        // player's one-shot flag and record the arena as their /spawnback location.
        boolean minigameArena = MinigameArenas.contains(player);
        boolean autopsyMigration = managedWorld && !staff && !mayKeepGamemode && !minigameArena
                && originalGamemode == GameMode.SPECTATOR
                && plugin.getConfig().getBoolean("login-safety.autopsy-migration.enabled", true)
                && !migrationStore.hasMigrated(player.getUniqueId());
        boolean vulnerableAfterLogin = normalizeGamemode || originalGamemode == GameMode.SURVIVAL
                || originalGamemode == GameMode.ADVENTURE;
        LocationSafety.Issue issue = vulnerableAfterLogin ? LocationSafety.check(player.getLocation())
                : LocationSafety.Issue.NONE;
        boolean unsafe = issue.unsafe();

        Location destination = plannedDestination;
        if (destination == null && (autopsyMigration || unsafe))
            destination = globalSpawn();

        // The player ends up at the destination, so their right to the login
        // gamemode is judged there too: a creative login rescued into the spawn
        // creative region keeps creative instead of being dropped to survival.
        if (destination != null && !mayKeepGamemode && gamemodePolicy.mayUse(player, originalGamemode, destination)) {

            normalizeGamemode = false;
            autopsyMigration = false;

        }

        boolean teleportRequired = destination != null;
        if (!teleportRequired && !normalizeGamemode)
            return false;

        if ((autopsyMigration || unsafe) && destination == null) {

            protectInPlace(player, null, player.isInvulnerable());
            send(player, "locale.loginSafetyFailed",
                    "<red>Your login location could not be made safe because the global spawn is not configured. Staff have been notified.</red>");
            plugin.getLogger().severe("Unable to safely migrate " + player.getName()
                    + " because spawns.global.location is not configured.");
            return true;

        }

        beginTransaction(player, destination,
                new PendingLogin(player.isInvulnerable(), player.getAllowFlight(), player.isFlying(), originalGamemode,
                        player.getLocation().clone(), normalizeGamemode, autopsyMigration, issue, null));
        return true;

    }

    public void cancel(Player player) {

        PendingLogin pending = pendingLogins.remove(player.getUniqueId());
        if (pending != null)
            player.setInvulnerable(pending.originalInvulnerable());

    }

    public boolean isPending(Player player) {

        return player != null && pendingLogins.containsKey(player.getUniqueId());

    }

    public void addCompletionListener(Consumer<Player> listener) {

        completionListeners.add(listener);

    }

    public void setFlightRestorer(FlightRestorer flightRestorer) {

        this.flightRestorer = flightRestorer;

    }

    public void close() {

        pendingLogins.forEach((playerId, pending) -> {

            Player player = plugin.getServer().getPlayer(playerId);
            if (player != null)
                player.setInvulnerable(pending.originalInvulnerable());

        });
        pendingLogins.clear();

    }

    private void beginTransaction(Player player, Location destination, PendingLogin pending) {

        UUID playerId = player.getUniqueId();
        if (pendingLogins.containsKey(playerId))
            return;

        pendingLogins.put(playerId, pending);

        player.setInvulnerable(true);
        player.setFallDistance(0.0F);
        player.setVelocity(new Vector());

        if (destination == null) {

            plugin.getServer().getScheduler().runTask(plugin, () -> complete(player, true, null));
            return;

        }

        CompletableFuture<Boolean> teleport = player.teleportAsync(destination.clone());
        teleport.whenComplete((success, error) -> {

            if (!plugin.isEnabled())
                return;
            plugin.getServer().getScheduler().runTask(plugin,
                    () -> complete(player, Boolean.TRUE.equals(success), error));

        });

    }

    private void complete(Player player, boolean teleportSucceeded, Throwable teleportError) {

        PendingLogin pending = pendingLogins.remove(player.getUniqueId());
        if (pending == null)
            return;

        try {

            finishTransaction(player, pending, teleportSucceeded, teleportError);

        } finally {

            completionListeners.forEach(listener -> listener.accept(player));

        }

    }

    private void finishTransaction(Player player, PendingLogin pending, boolean teleportSucceeded,
            Throwable teleportError)
    {

        if (!player.isOnline()) {

            player.setInvulnerable(pending.originalInvulnerable());
            return;

        }

        if (!teleportSucceeded || teleportError != null || isUnsafe(player.getLocation())) {

            // The airborne snapshot survives a failed rescue, so the next login
            // tries again instead of the failure becoming the final word.
            protectInPlace(player, pending.snapshot(), pending.originalInvulnerable());
            send(player, "locale.loginSafetyFailed",
                    "<red>Your login safety migration could not finish. You were protected in place; please contact staff.</red>");
            plugin.getLogger().severe("Login safety transaction failed for " + player.getName()
                    + (teleportError == null ? "." : ": " + teleportError.getMessage()));
            return;

        }

        if (pending.normalizeGamemode()) {

            if (player.isFlying())
                player.setFlying(false);
            player.setFallDistance(0.0F);
            player.setGameMode(GameMode.SURVIVAL);
            if (player.getGameMode() != GameMode.SURVIVAL) {

                player.setInvulnerable(pending.originalInvulnerable());
                send(player, "locale.loginSafetyFailed",
                        "<red>Another plugin prevented your safe switch to survival. Staff have been notified.</red>");
                plugin.getLogger().severe(
                        "Another plugin prevented Spawn-OG from normalizing " + player.getName() + " to survival.");
                return;

            }

            if (plugin.getConfig().getBoolean("login-safety.remove-flight-on-normalize", true))
                player.setAllowFlight(false);

        }

        if (pending.snapshot() != null) {

            Location from = pending.originalLocation();
            send(player, "locale.flyingRescue",
                    "<gold>You logged out mid-flight at <red><x>, <y>, <z></red> in <world> and were brought to spawn.</gold>",
                    Placeholder.unparsed("x", String.valueOf(from.getBlockX())),
                    Placeholder.unparsed("y", String.valueOf(from.getBlockY())),
                    Placeholder.unparsed("z", String.valueOf(from.getBlockZ())),
                    Placeholder.unparsed("world", from.getWorld() == null ? "unknown" : from.getWorld().getName()));
            // The snapshot is only spent once the way back is safely on disk.
            if (offerReturn(player, pending))
                flightSnapshotStore.clear(player.getUniqueId());

        } else if (pending.autopsyMigration()) {

            boolean recorded = migrationStore.record(player.getUniqueId(), player.getName(), pending.originalGamemode(),
                    pending.originalLocation());
            if (!recorded) {

                rollbackAutopsyCommit(player, pending);
                player.setInvulnerable(pending.originalInvulnerable());
                send(player, "locale.loginSafetyFailed",
                        "<red>Your autopsy migration could not be recorded. You remain safely in spectator mode; please contact staff.</red>");
                return;

            }

            Location from = pending.originalLocation();
            send(player, "locale.autopsyMigration",
                    "<gold>Your OG:SMP autopsy state was safely migrated from <red><x>, <y>, <z></red> in <world>. You were moved to spawn and returned to survival.</gold>",
                    Placeholder.unparsed("x", String.valueOf(from.getBlockX())),
                    Placeholder.unparsed("y", String.valueOf(from.getBlockY())),
                    Placeholder.unparsed("z", String.valueOf(from.getBlockZ())),
                    Placeholder.unparsed("world", from.getWorld() == null ? "unknown" : from.getWorld().getName()));
            offerReturn(player, pending);

        } else if (pending.normalizeGamemode()) {

            send(player, "locale.gamemodeNormalized",
                    "<gold>Your login gamemode was safely returned to survival.</gold>");

        } else if (pending.unsafe()) {

            Location from = pending.originalLocation();
            send(player, "locale.unsafeLogin",
                    "<gold>You logged in at an unsafe location (<red><x>, <y>, <z></red> in <world>) and were teleported to spawn.</gold>",
                    Placeholder.unparsed("x", String.valueOf(from.getBlockX())),
                    Placeholder.unparsed("y", String.valueOf(from.getBlockY())),
                    Placeholder.unparsed("z", String.valueOf(from.getBlockZ())),
                    Placeholder.unparsed("world", from.getWorld() == null ? "unknown" : from.getWorld().getName()));
            offerReturn(player, pending);

        }

        player.setInvulnerable(pending.originalInvulnerable());

    }

    // Writes the /spawnback record and offers it. False means the player was
    // told the way back could not be saved, and the caller must not spend
    // whatever evidence would let a later login try again.
    private boolean offerReturn(Player player, PendingLogin pending) {

        String reason;
        if (pending.snapshot() != null)
            reason = AIRBORNE_REASON;
        else if (pending.autopsyMigration())
            reason = AUTOPSY_REASON;
        else
            reason = pending.safetyIssue().description();

        FlightMode mode = pending.snapshot() == null ? FlightMode.NONE : pending.snapshot().mode();
        if (!returnLocationStore.record(player.getUniqueId(), player.getName(), pending.originalLocation(), reason,
                pending.originalGamemode(), pending.originalAllowFlight(), pending.originalFlying(), mode))
        {

            send(player, "locale.returnRecordFailed",
                    "<red>Your way back could not be saved. Staff have been notified.</red>");
            plugin.getLogger().severe("Unable to record the return location for " + player.getName() + ".");
            return false;

        }

        send(player, "locale.returnAvailable",
                "<gold>Run <click:run_command:'/spawnback'><hover:show_text:'<gold>Click to run <red>/spawnback</red></gold>'><red>/spawnback</red></hover></click> if you want to go back there anyway.</gold>");
        return true;

    }

    // The failure mode when a rescue cannot run or finish: the player keeps
    // their gamemode and place, gets the narrowest protection that stops the
    // situation from hurting them, and everything expires on its own. Never
    // parks anyone in spectator, because nothing would ever take them out.
    private void protectInPlace(Player player, FlightSnapshot snapshot, boolean originalInvulnerable) {

        // A flyer is given back the flight they already had, as a loan the
        // flight service revokes on landing, rather than being left to fall.
        if (snapshot != null && flightRestorer != null)
            flightRestorer.loanFlight(player);

        fallProtection.grant(player);
        player.setFallDistance(0.0F);
        player.setInvulnerable(true);

        int graceSeconds = plugin.getConfig().getInt("login-safety.failed-rescue-grace-seconds", DEFAULT_GRACE_SECONDS);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {

            if (player.isOnline())
                player.setInvulnerable(originalInvulnerable);

        }, graceSeconds * 20L);

    }

    private void rollbackAutopsyCommit(Player player, PendingLogin pending) {

        if (player.isFlying())
            player.setFlying(false);
        player.setGameMode(pending.originalGamemode());
        player.setAllowFlight(pending.originalAllowFlight());
        if (pending.originalFlying() && player.getAllowFlight())
            player.setFlying(true);
        player.setFallDistance(0.0F);

    }

    private boolean isManagedWorld(Player player) {

        return managedWorlds.contains(player.getWorld());

    }

    // Whether the player's live login state still looks like the quit the
    // snapshot recorded: same spot, same gamemode, and still off the ground.
    // Anything else means the snapshot describes an older session and must not
    // drive a rescue or a flight-restoring return record. Live flight bits are
    // not required on their own because RegionFlightService grounds /fly
    // flyers on the way out after the snapshot is taken, so a genuine
    // mid-flight quitter can relog with allow-flight off, hanging unsupported.
    private boolean corroborates(Player player, FlightSnapshot snapshot) {

        Location recorded = snapshot.location();
        Location current = player.getLocation();
        if (recorded == null || recorded.getWorld() == null || !recorded.getWorld().equals(current.getWorld()))
            return false;
        if (recorded.distanceSquared(current) > SNAPSHOT_DISTANCE_SQUARED)
            return false;
        if (snapshot.gamemode() == null || player.getGameMode() != snapshot.gamemode())
            return false;

        return player.isFlying() || player.getAllowFlight() || !LocationSafety.isSupported(current);

    }

    private Location globalSpawn() {

        Location spawn = plugin.getConfig().getLocation("spawns.global.location");
        return spawn == null ? null : spawn.clone();

    }

    private boolean isUnsafe(Location location) {

        return LocationSafety.isUnsafe(location);

    }

    private void send(Player player, String path, String fallback, TagResolver... placeholders) {

        String message = plugin.getConfig().getString(path, fallback);
        Component component = miniMessage.deserialize(message, placeholders);
        player.sendMessage(component);

    }

    // snapshot is non-null only for an airborne rescue, and then carries the
    // state the quit captured; the original* fields describe the same moment.
    private record PendingLogin(boolean originalInvulnerable, boolean originalAllowFlight, boolean originalFlying,
            GameMode originalGamemode, Location originalLocation, boolean normalizeGamemode, boolean autopsyMigration,
            LocationSafety.Issue safetyIssue, FlightSnapshot snapshot)
    {

        boolean unsafe() {

            return safetyIssue.unsafe();

        }

    }

}
