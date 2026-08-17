package spawnog.flight;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import spawnog.login.GamemodeAuthority;
import spawnog.login.GamemodePolicy;
import spawnog.login.NoClipAuthority;

// The single decision table for handing a rescued flyer flight back, shared by
// /spawnback and login so they never disagree; a nofly region outranks all but bypass.
public final class FlightRestorer {

    public static final String NOCLIP_PERMISSION = "noclip.use";

    // Null when regional flight is off or WorldGuard is absent; every mode is
    // then refused, because nothing could enforce a nofly region afterwards.
    private final RegionFlightService regionFlightService;
    private final GamemodePolicy gamemodePolicy;
    // Null without GameModeInventories-OG; creative is then never restored,
    // because only its machinery swaps inventories safely.
    private final GamemodeAuthority gamemodeAuthority;
    // Null without NoClip-OG.
    private final NoClipAuthority noClipAuthority;

    public FlightRestorer(RegionFlightService regionFlightService, GamemodePolicy gamemodePolicy,
            GamemodeAuthority gamemodeAuthority, NoClipAuthority noClipAuthority)
    {

        this.regionFlightService = regionFlightService;
        this.gamemodePolicy = gamemodePolicy;
        this.gamemodeAuthority = gamemodeAuthority;
        this.noClipAuthority = noClipAuthority;

    }

    // Whether the prior mode of flight would be handed back at the destination.
    // Asked before warning, so a player headed for a drop is told up front.
    public boolean canRestore(FlightMode mode, Player player, Location destination) {

        if (mode == null || player == null || destination == null)
            return false;

        return switch (mode) {

            case FLY -> flyPermitted(player, destination);
            case GMIC -> creativeFlightPermitted(player, destination);
            case NOCLIP -> creativeFlightPermitted(player, destination) && player.hasPermission(NOCLIP_PERMISSION)
                    && noClipAuthority != null;
            // Plain spectator flight is never restored; the player returns in
            // survival with fall protection instead.
            case SPECTATOR, NONE -> false;

        };

    }

    // Puts the player back into their prior mode of flight where they stand.
    // Rights re-checked live; false means the caller owes fall protection.
    public boolean restore(FlightMode mode, Player player) {

        if (player == null || !canRestore(mode, player, player.getLocation()))
            return false;

        return switch (mode) {

            case FLY -> regionFlightService.grantRegionFlight(player);
            case GMIC -> restoreCreative(player);
            case NOCLIP -> restoreCreative(player) && noClipAuthority.enterNoClip(player);
            case SPECTATOR, NONE -> false;

        };

    }

    // Like canRestore, except a sanctioned spectator is left alone. Only a live
    // spectator qualifies: force-gamemode rewrites the mode between quit and login.
    public boolean canResumeInPlace(FlightMode mode, Player player, Location location) {

        if (mode == FlightMode.SPECTATOR)
            return player.getGameMode() == GameMode.SPECTATOR
                    && gamemodePolicy.mayUse(player, GameMode.SPECTATOR, location);

        return canRestore(mode, player, location);

    }

    public boolean resumeInPlace(FlightMode mode, Player player) {

        if (mode == FlightMode.SPECTATOR)
            return true;

        return restore(mode, player);

    }

    // The narrowest flight covering a descent when nothing can be restored: a
    // landing loan the flight service revokes on touchdown.
    public boolean loanFlight(Player player) {

        return regionFlightService != null && regionFlightService.resumeFlight(player);

    }

    private boolean flyPermitted(Player player, Location destination) {

        return regionFlightService != null && regionFlightService.hasFlightPermission(player)
                && regionFlightService.ruleKindAt(destination) == RegionFlightService.RuleKind.FLY;

    }

    // Creative flight needs GMI's sanction for the gamemode and the region
    // rules' for the flight: anywhere licenses creative, a nofly still grounds it.
    private boolean creativeFlightPermitted(Player player, Location destination) {

        if (regionFlightService == null || gamemodeAuthority == null)
            return false;
        if (!gamemodePolicy.mayUse(player, GameMode.CREATIVE, destination))
            return false;

        return regionFlightService.ruleKindAt(destination) != RegionFlightService.RuleKind.NOFLY
                || regionFlightService.hasBypass(player);

    }

    // Creative through the authority so the inventory swap runs. Abilities are
    // re-asserted, not trusted; setFlying throws while allow-flight is off.
    private boolean restoreCreative(Player player) {

        if (!gamemodeAuthority.changeGameMode(player, GameMode.CREATIVE))
            return false;

        player.setAllowFlight(true);
        player.setFlying(true);
        return true;

    }

}
