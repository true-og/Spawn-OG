package spawnog.login;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import java.util.stream.Stream;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.MockedStatic;
import spawnog.SpawnOG;
import spawnog.flight.FlightMode;
import spawnog.flight.FlightRestorer;
import spawnog.flight.FlightSnapshotStore;
import spawnog.flight.FlightSnapshotStore.FlightSnapshot;
import spawnog.teleport.FallProtection;
import spawnog.world.ManagedWorlds;

class FlightLoginTest {

    private final SpawnOG plugin = mock(SpawnOG.class);
    private final Player player = mock(Player.class);
    private final World world = mock(World.class);
    private final Location origin = new Location(world, 1, 64, 1);
    private final Location spawn = new Location(world, 100, 64, 100);
    private final FlightSnapshotStore snapshots = mock(FlightSnapshotStore.class);
    private final ReturnLocationStore returns = mock(ReturnLocationStore.class);
    private final FlightRestorer restorer = mock(FlightRestorer.class);
    private final GamemodePolicy policy = mock(GamemodePolicy.class);
    private final BukkitScheduler scheduler = mock(BukkitScheduler.class);
    private final List<Runnable> tasks = new ArrayList<>();
    private final CompletableFuture<Boolean> teleport = new CompletableFuture<>();
    private LoginMigrationService service;
    private MockedStatic<MinigameArenas> arenas;
    private MockedStatic<LocationSafety> safety;

    @BeforeEach
    void setUp() {

        arenas = mockStatic(MinigameArenas.class);
        safety = mockStatic(LocationSafety.class);
        safety.when(() -> LocationSafety.check(origin)).thenReturn(LocationSafety.Issue.LONG_FALL);
        safety.when(() -> LocationSafety.check(spawn)).thenReturn(LocationSafety.Issue.NONE);
        final YamlConfiguration config = new YamlConfiguration();
        config.set("spawns.global.location", spawn);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("FlightLoginTest"));
        when(plugin.isEnabled()).thenReturn(true);
        final Server server = mock(Server.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(invocation -> {

            tasks.add(invocation.getArgument(1));
            return mock(BukkitTask.class);

        });
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getName()).thenReturn("Builder");
        when(player.getWorld()).thenReturn(world);
        when(world.getName()).thenReturn("world");
        when(player.getLocation()).thenReturn(origin);
        when(player.isOnline()).thenReturn(true);
        when(player.getGameMode()).thenReturn(GameMode.CREATIVE);
        when(player.teleportAsync(any(Location.class))).thenReturn(teleport);
        when(snapshots.peek(player.getUniqueId()))
                .thenReturn(new FlightSnapshot(GameMode.CREATIVE, origin, true, false, true, false));
        when(policy.mayUse(eq(player), eq(GameMode.CREATIVE), any(Location.class))).thenReturn(true);
        when(returns.record(any(UUID.class), anyString(), any(Location.class), anyString(), any(GameMode.class),
                anyBoolean(), anyBoolean(), any(FlightMode.class))).thenReturn(true);
        final ManagedWorlds managed = mock(ManagedWorlds.class);
        when(managed.contains(world)).thenReturn(true);
        service = new LoginMigrationService(plugin, mock(AutopsyMigrationStore.class), returns, snapshots, policy,
                mock(GamemodeAuthority.class), managed, mock(FallProtection.class), mock(LoginShield.class));
        service.setFlightRestorer(restorer);

    }

    @AfterEach
    void tearDown() {

        arenas.close();
        safety.close();

    }

    @ParameterizedTest
    @MethodSource("restorationCases")
    void permittedModeResumesRegardlessOfMigrationBypass(FlightMode mode, boolean flying, boolean bypass) {

        snapshot(mode, flying);
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(player.hasPermission(LoginMigrationService.BYPASS_PERMISSION)).thenReturn(bypass);
        when(restorer.canResumeInPlace(mode, player, origin)).thenReturn(true);
        when(restorer.resumeInPlace(mode, player, flying)).thenReturn(true);
        assertTrue(service.handleLogin(player, null));
        verify(restorer).resumeInPlace(mode, player, flying);
        verify(player, never()).teleportAsync(any(Location.class));
        verify(player, never()).hasPermission(LoginMigrationService.BYPASS_PERMISSION);
        // The snapshot must survive until the scheduled verification of abilities.
        verify(snapshots, never()).clear(player.getUniqueId());
        when(player.getAllowFlight()).thenReturn(true);
        runTasks();
        verify(snapshots).clear(player.getUniqueId());

    }

    @Test
    void unavailableGroundedNoClipRescuesAndRecordsTheToggle() {

        assertTrue(service.handleLogin(player, null));
        verify(player).teleportAsync(spawn);
        verify(returns).record(player.getUniqueId(), "Builder", origin, "you logged out with NoClip enabled",
                GameMode.CREATIVE, true, false, FlightMode.NOCLIP);
        verify(restorer, never()).resumeInPlace(any(FlightMode.class), eq(player), anyBoolean());

    }

    @ParameterizedTest
    @MethodSource("restorationCases")
    void deniedModeCannotResumeRegardlessOfMigrationBypass(FlightMode mode, boolean flying, boolean bypass) {

        snapshot(mode, flying);
        when(player.hasPermission(LoginMigrationService.BYPASS_PERMISSION)).thenReturn(bypass);
        when(policy.mayUse(player, GameMode.CREATIVE, origin)).thenReturn(false);
        assertTrue(service.handleLogin(player, null));
        verify(player).teleportAsync(spawn);
        verify(restorer, never()).resumeInPlace(any(FlightMode.class), eq(player), anyBoolean());
        verify(player, never()).hasPermission(LoginMigrationService.BYPASS_PERMISSION);

    }

    @ParameterizedTest
    @MethodSource("restorationCases")
    void configuredJoinTeleportRestoresPermittedModeAtDestination(FlightMode mode, boolean flying, boolean bypass) {

        snapshot(mode, flying);
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(player.hasPermission(LoginMigrationService.BYPASS_PERMISSION)).thenReturn(bypass);
        when(restorer.canResumeInPlace(mode, player, origin)).thenReturn(true);
        when(restorer.restore(mode, player, flying)).thenAnswer(invocation -> {

            org.junit.jupiter.api.Assertions.assertEquals(spawn, player.getLocation());
            return true;

        });
        assertTrue(service.handleLogin(player, spawn));
        verify(player).teleportAsync(spawn);
        verify(restorer, never()).resumeInPlace(any(FlightMode.class), eq(player), anyBoolean());
        verify(restorer, never()).restore(mode, player, flying);
        when(player.getLocation()).thenReturn(spawn);
        teleport.complete(true);
        runTasks();
        verify(restorer).restore(mode, player, flying);
        verify(player, never()).hasPermission(LoginMigrationService.BYPASS_PERMISSION);
        verify(snapshots).clear(player.getUniqueId());

    }

    @ParameterizedTest
    @MethodSource("restorationCases")
    void destinationDenialCannotBeOverriddenByMigrationBypass(FlightMode mode, boolean flying, boolean bypass) {

        snapshot(mode, flying);
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(player.hasPermission(LoginMigrationService.BYPASS_PERMISSION)).thenReturn(bypass);
        when(policy.mayUse(player, GameMode.CREATIVE, spawn)).thenReturn(false);
        when(restorer.canResumeInPlace(mode, player, origin)).thenReturn(true);
        assertTrue(service.handleLogin(player, spawn));
        when(player.getLocation()).thenReturn(spawn);
        teleport.complete(true);
        runTasks();
        verify(restorer).restore(mode, player, flying);
        verify(restorer, never()).resumeInPlace(any(FlightMode.class), eq(player), anyBoolean());
        verify(player, never()).hasPermission(LoginMigrationService.BYPASS_PERMISSION);
        verify(player, never()).setAllowFlight(true);
        verify(player, never()).setFlying(true);

    }

    @Test
    void groundedFlightToggleIsRecordedForTheReturn() {

        when(snapshots.peek(player.getUniqueId())).thenReturn(null);
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(restorer.liveMode(player, GameMode.SURVIVAL)).thenReturn(FlightMode.FLY);
        assertTrue(service.handleLogin(player, null));
        verify(returns).record(player.getUniqueId(), "Builder", origin, "it is a lethal drop above the ground",
                GameMode.SURVIVAL, false, false, FlightMode.FLY);

    }

    @Test
    void spectatorPhaseIsRecordedAsCreativeForReturnPolicy() {

        when(player.getGameMode()).thenReturn(GameMode.SPECTATOR);
        when(snapshots.peek(player.getUniqueId()))
                .thenReturn(new FlightSnapshot(GameMode.SPECTATOR, origin, true, true, true, false));
        assertTrue(service.handleLogin(player, null));
        verify(returns).record(player.getUniqueId(), "Builder", origin, "you logged out with NoClip enabled",
                GameMode.CREATIVE, true, true, FlightMode.NOCLIP);
        verify(policy).mayUse(player, GameMode.CREATIVE, spawn);

    }

    private void snapshot(FlightMode mode, boolean flying) {

        final GameMode gamemode = mode == FlightMode.FLY ? GameMode.SURVIVAL : GameMode.CREATIVE;
        when(player.getGameMode()).thenReturn(gamemode);
        when(snapshots.peek(player.getUniqueId())).thenReturn(
                new FlightSnapshot(gamemode, origin, true, flying, mode == FlightMode.NOCLIP, mode == FlightMode.FLY));

    }

    private void runTasks() {

        while (!tasks.isEmpty())
            tasks.remove(0).run();

    }

    private static Stream<Arguments> restorationCases() {

        return Stream.of(false, true)
                .flatMap(bypass -> Stream.of(Arguments.of(FlightMode.NOCLIP, false, bypass),
                        Arguments.of(FlightMode.NOCLIP, true, bypass), Arguments.of(FlightMode.GMIC, true, bypass),
                        Arguments.of(FlightMode.FLY, true, bypass)));

    }

}
