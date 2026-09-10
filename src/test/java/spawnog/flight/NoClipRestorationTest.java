package spawnog.flight;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.UUID;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import spawnog.SpawnOG;
import spawnog.login.GamemodeAuthority;
import spawnog.login.GamemodePolicy;
import spawnog.login.LoginMigrationService;
import spawnog.login.NoClipAuthority;

class NoClipRestorationTest {

    private final Player player = mock(Player.class);
    private final Location location = new Location(null, 1, 64, 1);
    private final GamemodePolicy policy = mock(GamemodePolicy.class);
    private final GamemodeAuthority gmi = mock(GamemodeAuthority.class);
    private final NoClipAuthority noclip = mock(NoClipAuthority.class);
    private final FlightRestorer restorer = new FlightRestorer(null, policy, gmi, noclip);

    @BeforeEach
    void setUp() {

        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getLocation()).thenReturn(location);
        when(player.hasPermission("noclip.use")).thenReturn(true);
        when(policy.mayUse(player, GameMode.CREATIVE, location)).thenReturn(true);
        when(gmi.changeGameMode(player, GameMode.CREATIVE)).thenReturn(true);
        when(noclip.enterNoClip(player)).thenReturn(true);

    }

    @Test
    void groundedNoClipRestoresCreativeThroughGmiBeforeEnablingToggle() {

        assertTrue(restorer.resumeInPlace(FlightMode.NOCLIP, player, false));
        final InOrder order = inOrder(gmi, noclip);
        order.verify(gmi).changeGameMode(player, GameMode.CREATIVE);
        order.verify(noclip).enterNoClip(player);
        verify(player).setAllowFlight(true);
        verify(player).setFlying(false);
        verify(player, never()).setFlying(true);

    }

    @Test
    void lostCreativeAuthorityCannotBeReplacedBySpectatorPermission() {

        when(player.getGameMode()).thenReturn(GameMode.SPECTATOR);
        when(policy.mayUse(player, GameMode.CREATIVE, location)).thenReturn(false);
        when(policy.mayUse(player, GameMode.SPECTATOR, location)).thenReturn(true);
        assertFalse(restorer.canResumeInPlace(FlightMode.NOCLIP, player, location));
        assertFalse(restorer.restore(FlightMode.NOCLIP, player));
        verifyNoInteractions(gmi, noclip);

    }

    @Test
    void missingNoclipPermissionOrPluginRefusesRestoration() {

        when(player.hasPermission("noclip.use")).thenReturn(false);
        assertFalse(restorer.restore(FlightMode.NOCLIP, player));
        when(player.hasPermission("noclip.use")).thenReturn(true);
        assertFalse(new FlightRestorer(null, policy, gmi, null).restore(FlightMode.NOCLIP, player));
        assertFalse(new FlightRestorer(null, policy, null, noclip).restore(FlightMode.NOCLIP, player));
        verifyNoInteractions(gmi, noclip);

    }

    @Test
    void cancelledCreativeChangeCannotActivateNoClip() {

        when(gmi.changeGameMode(player, GameMode.CREATIVE)).thenReturn(false);
        assertFalse(restorer.restore(FlightMode.NOCLIP, player));
        verifyNoInteractions(noclip);
        verify(player, never()).setAllowFlight(true);

    }

    @Test
    void groundedIntentSurvivingReloadIsCapturedForNextLogin() {

        when(player.getGameMode()).thenReturn(GameMode.CREATIVE);
        when(noclip.hasNoClipIntent(player)).thenReturn(true);
        final FlightSnapshotStore snapshots = mock(FlightSnapshotStore.class);
        final FlightIntentStore flightIntent = mock(FlightIntentStore.class);
        final FlightQuitListener listener = new FlightQuitListener(mock(SpawnOG.class), snapshots, flightIntent,
                mock(LoginMigrationService.class), noclip);
        listener.capture(player);
        verify(snapshots).record(player, true, false);
        verify(snapshots, never()).clear(player.getUniqueId());

    }

    @Test
    void staleIntentInSurvivalDoesNotBecomeNoClipSnapshot() {

        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(noclip.hasNoClipIntent(player)).thenReturn(true);
        final FlightSnapshotStore snapshots = mock(FlightSnapshotStore.class);
        final FlightQuitListener listener = new FlightQuitListener(mock(SpawnOG.class), snapshots,
                mock(FlightIntentStore.class), mock(LoginMigrationService.class), noclip);
        listener.capture(player);
        verify(snapshots).clear(player.getUniqueId());
        verify(snapshots, never()).record(player, true, false);

    }

}
