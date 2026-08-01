package spawnog.login;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiPredicate;
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

public final class LoginMigrationService {

    private static final List<String> DEFAULT_STAFF_PERMISSIONS = List.of("spawnog.login-migration.bypass",
            "staffog.seebroadcast", "chat-og.staff", "sv.use");
    private static final Set<String> DEFAULT_WORLDS = Set.of("world", "world_nether", "world_the_end");
    private static final String AUTOPSY_REASON = "you were left in spectator mode by the OG:SMP autopsy";

    private final SpawnOG plugin;
    private final AutopsyMigrationStore migrationStore;
    private final ReturnLocationStore returnLocationStore;
    private final GamemodePolicy gamemodePolicy;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final Map<UUID, PendingLogin> pendingLogins = new HashMap<>();
    private final List<Consumer<Player>> completionListeners = new ArrayList<>();
    // Answers whether regional flight will be re-armed for a player at a
    // location. Set after construction because the flight service takes this
    // service in its own constructor; stays null when regional flight is off.
    private BiPredicate<Player, Location> flightEligibility;

    public LoginMigrationService(SpawnOG plugin, AutopsyMigrationStore migrationStore,
            ReturnLocationStore returnLocationStore, GamemodePolicy gamemodePolicy)
    {

        this.plugin = plugin;
        this.migrationStore = migrationStore;
        this.returnLocationStore = returnLocationStore;
        this.gamemodePolicy = gamemodePolicy;

    }

    public boolean handleLogin(Player player, Location plannedDestination) {

        if (!plugin.getConfig().getBoolean("login-safety.enabled", true))
            return false;

        boolean managedWorld = isManagedWorld(player);
        GameMode originalGamemode = player.getGameMode();
        boolean staff = isStaff(player);
        // Any non-survival login is normalized unless the player is entitled to
        // that gamemode where they logged in; being staff is not enough on its own.
        boolean mayKeepGamemode = gamemodePolicy.mayUse(player, originalGamemode, player.getLocation());
        boolean normalizeGamemode = managedWorld && !mayKeepGamemode
                && plugin.getConfig().getBoolean("login-safety.normalize-non-staff-gamemode", true);
        // A sanctioned spectator is working, not an abandoned autopsy state.
        boolean autopsyMigration = managedWorld && !staff && !mayKeepGamemode && originalGamemode == GameMode.SPECTATOR
                && plugin.getConfig().getBoolean("login-safety.autopsy-migration.enabled", true)
                && !migrationStore.hasMigrated(player.getUniqueId());
        boolean vulnerableAfterLogin = normalizeGamemode || originalGamemode == GameMode.SURVIVAL
                || originalGamemode == GameMode.ADVENTURE;
        LocationSafety.Issue issue = vulnerableAfterLogin ? LocationSafety.check(player.getLocation())
                : LocationSafety.Issue.NONE;
        // A lethal drop is not lethal for a flyer whose regional flight will be
        // re-armed on that very spot, so they are left in the air instead of
        // being pulled to spawn only for /spawnback to drop them later.
        if ((issue == LocationSafety.Issue.LONG_FALL || issue == LocationSafety.Issue.VOID) && flightEligibility != null
                && flightEligibility.test(player, player.getLocation()))
            issue = LocationSafety.Issue.NONE;
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

            protectAfterFailedRescue(player, originalGamemode, unsafe);
            send(player, "locale.loginSafetyFailed",
                    "<red>Your login location could not be made safe because the global spawn is not configured. Staff have been notified.</red>");
            plugin.getLogger().severe("Unable to safely migrate " + player.getName()
                    + " because spawns.global.location is not configured.");
            return true;

        }

        beginTransaction(player, destination, originalGamemode, normalizeGamemode, autopsyMigration, issue);
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

    public void setFlightEligibility(BiPredicate<Player, Location> flightEligibility) {

        this.flightEligibility = flightEligibility;

    }

    public void close() {

        pendingLogins.forEach((playerId, pending) -> {

            Player player = plugin.getServer().getPlayer(playerId);
            if (player != null)
                player.setInvulnerable(pending.originalInvulnerable());

        });
        pendingLogins.clear();

    }

    private void beginTransaction(Player player, Location destination, GameMode originalGamemode,
            boolean normalizeGamemode, boolean autopsyMigration, LocationSafety.Issue issue)
    {

        UUID playerId = player.getUniqueId();
        if (pendingLogins.containsKey(playerId))
            return;

        PendingLogin pending = new PendingLogin(player.isInvulnerable(), player.getAllowFlight(), player.isFlying(),
                originalGamemode, player.getLocation().clone(), normalizeGamemode, autopsyMigration, issue);
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

            protectAfterFailedRescue(player, pending.originalGamemode(), true);
            player.setInvulnerable(pending.originalInvulnerable());
            send(player, "locale.loginSafetyFailed",
                    "<red>Your login safety migration could not finish. You were left in a non-damaging gamemode; please contact staff.</red>");
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

        if (pending.autopsyMigration()) {

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

    private void offerReturn(Player player, PendingLogin pending) {

        String reason = pending.autopsyMigration() ? AUTOPSY_REASON : pending.safetyIssue().description();
        // The pre-migration flight state travels with the record, so /spawnback
        // can put a rescued flyer back into the air instead of dropping them.
        if (!returnLocationStore.record(player.getUniqueId(), player.getName(), pending.originalLocation(), reason,
                pending.originalAllowFlight(), pending.originalFlying()))
            return;

        send(player, "locale.returnAvailable",
                "<gold>Run <click:run_command:'/spawnback'><hover:show_text:'<gold>Click to run <red>/spawnback</red></gold>'><red>/spawnback</red></hover></click> if you want to go back there anyway.</gold>");

    }

    private void protectAfterFailedRescue(Player player, GameMode originalGamemode, boolean unsafe) {

        if (unsafe && originalGamemode != GameMode.CREATIVE && originalGamemode != GameMode.SPECTATOR) {

            player.setGameMode(GameMode.SPECTATOR);
            player.setFallDistance(0.0F);

        }

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

        List<String> configuredWorlds = plugin.getConfig().getStringList("login-safety.worlds");
        if (configuredWorlds.isEmpty())
            return DEFAULT_WORLDS.contains(player.getWorld().getName());
        return configuredWorlds.stream().anyMatch(world -> world.equalsIgnoreCase(player.getWorld().getName()));

    }

    private boolean isStaff(Player player) {

        List<String> permissions = plugin.getConfig().getStringList("login-safety.staff-bypass-permissions");
        if (permissions.isEmpty())
            permissions = DEFAULT_STAFF_PERMISSIONS;
        return permissions.stream().anyMatch(player::hasPermission);

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

    private record PendingLogin(boolean originalInvulnerable, boolean originalAllowFlight, boolean originalFlying,
            GameMode originalGamemode, Location originalLocation, boolean normalizeGamemode, boolean autopsyMigration,
            LocationSafety.Issue safetyIssue)
    {

        boolean unsafe() {

            return safetyIssue.unsafe();

        }

    }

}
