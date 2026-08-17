package spawnog.flight;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import spawnog.login.GamemodeAuthority;
import spawnog.login.GamemodePolicy;
import spawnog.login.NoClipAuthority;

// The single decision table for handing a rescued flyer flight back, shared by
// /spawnback and login so they never disagree; gamemode questions go to policy.
public final class FlightRestorer {

    public static final String NOCLIP_PERMISSION = "noclip.use";

    // Null when regional flight is off or WorldGuard is absent; regional
    // survival flight and landing loans are then refused.
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

    // Whether the gamemode would be handed back at the destination: policy must
    // sanction it, and creative needs the authority's inventory swap.
    public boolean canPromoteGamemode(Player player, GameMode target, Location destination) {

        if (target != GameMode.CREATIVE && target != GameMode.SPECTATOR)
            return false;
        if (target == GameMode.CREATIVE && gamemodeAuthority == null)
            return false;

        return gamemodePolicy.mayUse(player, target, destination);

    }

    // Hands a rescued player back the gamemode they left with, judged live where
    // they stand. Creative only through the authority; spectator may go raw.
    public boolean promoteGamemode(Player player, GameMode target) {

        if (!canPromoteGamemode(player, target, player.getLocation()))
            return false;
        if (player.getGameMode() == target)
            return true;
        if (gamemodeAuthority != null)
            return gamemodeAuthority.changeGameMode(player, target);

        player.setGameMode(target);
        return player.getGameMode() == target;

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

    // Creative flight is gamemode derived: the flight service never grounds a
    // creative flyer, so region rules and their bypass have no say here.
    private boolean creativeFlightPermitted(Player player, Location destination) {

        return gamemodeAuthority != null && gamemodePolicy.mayUse(player, GameMode.CREATIVE, destination);

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
