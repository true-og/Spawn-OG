package spawnog.commands;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
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
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import spawnog.SpawnOG;
import spawnog.flight.FlightMode;
import spawnog.flight.FlightRestorer;
import spawnog.login.LocationSafety;
import spawnog.login.LoginMigrationService;
import spawnog.login.ReturnLocationStore;
import spawnog.login.ReturnLocationStore.ReturnPoint;
import spawnog.teleport.FallProtection;

class SpawnBackReturnTest {

    private final SpawnOG plugin = mock(SpawnOG.class);
    private final Player player = mock(Player.class);
    private final Command command = mock(Command.class);
    private final World world = mock(World.class);
    private final Location origin = new Location(world, 1, 200, 1);
    private final ReturnLocationStore returns = mock(ReturnLocationStore.class);
    private final LoginMigrationService migration = mock(LoginMigrationService.class);
    private final FlightRestorer restorer = mock(FlightRestorer.class);
    private final FallProtection fallProtection = mock(FallProtection.class);
    private final List<Runnable> tasks = new ArrayList<>();
    private final CompletableFuture<Boolean> teleport = new CompletableFuture<>();
    private final UUID playerId = UUID.randomUUID();
    private SpawnBackCommand spawnBack;
    private MockedStatic<LocationSafety> safety;

    @BeforeEach
    void setUp() {

        safety = mockStatic(LocationSafety.class);
        safety.when(() -> LocationSafety.check(any(Location.class))).thenReturn(LocationSafety.Issue.NONE);
        when(plugin.getConfig()).thenReturn(new YamlConfiguration());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("SpawnBackReturnTest"));
        when(plugin.isEnabled()).thenReturn(true);
        final Server server = mock(Server.class);
        final BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(invocation -> {

            tasks.add(invocation.getArgument(1));
            return mock(BukkitTask.class);

        });
        when(world.getName()).thenReturn("world");
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getName()).thenReturn("Builder");
        when(player.isOnline()).thenReturn(true);
        when(player.getLocation()).thenReturn(origin);
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(player.teleportAsync(any(Location.class))).thenReturn(teleport);
        when(command.getPermission()).thenReturn("spawnog.spawnback");
        when(player.hasPermission("spawnog.spawnback")).thenReturn(true);
        when(migration.isPending(player)).thenReturn(false);
        when(returns.get(playerId)).thenReturn(new ReturnPoint(origin, "you logged out mid-flight", "world",
                GameMode.SURVIVAL, true, true, FlightMode.FLY, "token"));
        spawnBack = new SpawnBackCommand(plugin, returns, migration, restorer, fallProtection);

    }

    @AfterEach
    void tearDown() {

        safety.close();

    }

    @Test
    void lapsedFlightToggleIsLentBackInsteadOfDropped() {

        when(restorer.canResumeInPlace(FlightMode.FLY, player, origin)).thenReturn(false);
        when(restorer.canPromoteGamemode(player, GameMode.SURVIVAL, origin)).thenReturn(false);
        when(restorer.loanFlight(player)).thenAnswer(invocation -> {

            when(player.getAllowFlight()).thenReturn(true);
            return true;

        });

        run();
        run();
        teleport.complete(true);
        runTasks();

        verify(restorer).loanFlight(player);
        verify(restorer).rememberFlightIntent(player);
        verify(fallProtection, never()).grant(player);
        verify(returns).clear(playerId);

    }

    @Test
    void aGroundedReturnWithNothingToRestoreStillCoversTheLanding() {

        when(returns.get(playerId)).thenReturn(new ReturnPoint(origin, "it is a lethal drop above the ground", "world",
                GameMode.SURVIVAL, false, false, FlightMode.NONE, "token"));
        safety.when(() -> LocationSafety.clearAbove(any(Location.class))).thenReturn(origin);

        run();
        run();
        teleport.complete(true);
        runTasks();

        verify(restorer, never()).loanFlight(player);
        verify(fallProtection).grant(player);
        verify(returns).clear(playerId);

    }

    private void run() {

        assertTrue(spawnBack.onCommand(player, command, "spawnback", new String[0]));

    }

    private void runTasks() {

        while (!tasks.isEmpty())
            tasks.remove(0).run();

    }

}
