package spawnog.flight;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import spawnog.login.GamemodeAuthority;
import spawnog.login.GamemodePolicy;
import spawnog.login.NoClipAuthority;

// The single decision table for handing a rescued flyer their flight back:
// /spawnback and the login bypass path both ask it, so the warning, the
// restore, and the in-place resume can never disagree. Every check is judged
// at the destination, and a nofly region outranks every mode of flight unless
// the player holds the regional flight bypass.
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

    // Whether the player's prior mode of flight would be handed back at the
    // destination. /spawnback asks this before warning, so a player headed for
    // a drop is told about it up front.
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
    // Permissions are re-checked live, so a right lost since the warning is a
    // refusal here rather than an unearned grant. False means the caller owes
    // the player fall protection.
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

    // The login bypass check: like canRestore, except a sanctioned spectator is
    // left alone rather than refused, since spectators fly by nature and there
    // is nothing to re-arm.
    public boolean canResumeInPlace(FlightMode mode, Player player, Location location) {

        if (mode == FlightMode.SPECTATOR)
            return gamemodePolicy.mayUse(player, GameMode.SPECTATOR, location);

        return canRestore(mode, player, location);

    }

    public boolean resumeInPlace(FlightMode mode, Player player) {

        if (mode == FlightMode.SPECTATOR)
            return true;

        return restore(mode, player);

    }

    // The narrowest flight that covers a descent when no mode can be restored:
    // a landing loan the flight service revokes on touchdown. For the rescue
    // paths that leave a player airborne with nothing else holding them up.
    public boolean loanFlight(Player player) {

        return regionFlightService != null && regionFlightService.resumeFlight(player);

    }

    private boolean flyPermitted(Player player, Location destination) {

        return regionFlightService != null && regionFlightService.hasFlightPermission(player)
                && regionFlightService.ruleKindAt(destination) == RegionFlightService.RuleKind.FLY;

    }

    // Creative flight needs GameModeInventories-OG's sanction for the gamemode
    // and the region rules' sanction for the flight: gamemodeinventories.anywhere
    // licenses creative everywhere, but a nofly region still grounds it.
    private boolean creativeFlightPermitted(Player player, Location destination) {

        if (regionFlightService == null || gamemodeAuthority == null)
            return false;
        if (!gamemodePolicy.mayUse(player, GameMode.CREATIVE, destination))
            return false;

        return regionFlightService.ruleKindAt(destination) != RegionFlightService.RuleKind.NOFLY
                || regionFlightService.hasBypass(player);

    }

    // Creative through the authority so the inventory swap runs, then the
    // airborne flag: creative grants the ability, but not the state.
    private boolean restoreCreative(Player player) {

        if (!gamemodeAuthority.changeGameMode(player, GameMode.CREATIVE))
            return false;

        if (player.getAllowFlight())
            player.setFlying(true);
        return true;

    }

}
