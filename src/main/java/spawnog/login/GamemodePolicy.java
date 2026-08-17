package spawnog.login;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import spawnog.SpawnOG;
import spawnog.region.RegionLookup;

// Decides who may log in outside survival. GameModeInventories-OG owns the
// rule at runtime and is asked first; the local rules below stand in without it.
public final class GamemodePolicy {

    private static final String ANYWHERE_PERMISSION = "gamemodeinventories.anywhere";
    private static final String NOCLIP_PERMISSION = "noclip.use";
    private static final List<String> DEFAULT_CREATIVE_PERMISSIONS = List.of(NOCLIP_PERMISSION,
            "gamemodeinventories.toggle", ANYWHERE_PERMISSION);
    // GameModeInventories-OG gates spectator on these two, not on its creative
    // toggle, so a creative permission never sanctions a spectator login.
    private static final List<String> DEFAULT_SPECTATOR_PERMISSIONS = List.of(NOCLIP_PERMISSION,
            "gamemodeinventories.spectator");
    private static final List<String> DEFAULT_CREATIVE_REGIONS = List.of("spawn");

    private final SpawnOG plugin;
    private final RegionLookup regionLookup;
    private final GamemodeAuthority authority;

    public GamemodePolicy(SpawnOG plugin, RegionLookup regionLookup, GamemodeAuthority authority) {

        this.plugin = plugin;
        this.regionLookup = regionLookup;
        this.authority = authority;

    }

    // True when the player may hold the gamemode at the location, judged where
    // they will actually end up: a rescue asks about its destination.
    public boolean mayUse(Player player, GameMode gamemode, Location location) {

        if (gamemode == GameMode.SURVIVAL)
            return true;

        Boolean answer = authority == null ? null : authority.mayUse(player, gamemode, location);
        if (answer != null)
            return answer;

        // Neither NoClip-OG nor GameModeInventories-OG hands out adventure, so an
        // adventure login is always a leftover state rather than someone at work.
        if (gamemode != GameMode.CREATIVE && gamemode != GameMode.SPECTATOR)
            return false;
        if (!hasTooling(player, gamemode))
            return false;
        if (player.hasPermission(ANYWHERE_PERMISSION))
            return true;

        Set<String> creativeRegions = creativeRegions();
        return regionLookup.regionsAt(location).stream().anyMatch(creativeRegions::contains);

    }

    private boolean hasTooling(Player player, GameMode gamemode) {

        boolean creative = gamemode == GameMode.CREATIVE;
        List<String> permissions = plugin.getConfig()
                .getStringList("login-safety.gamemode-exemption-permissions." + (creative ? "creative" : "spectator"));
        if (permissions.isEmpty())
            permissions = creative ? DEFAULT_CREATIVE_PERMISSIONS : DEFAULT_SPECTATOR_PERMISSIONS;
        return permissions.stream().anyMatch(player::hasPermission);

    }

    private Set<String> creativeRegions() {

        Set<String> regions = new HashSet<>();
        plugin.getConfig().getStringList("login-safety.creative-regions").stream()
                .map(region -> region.toLowerCase(Locale.ROOT)).forEach(regions::add);

        if (regions.isEmpty())
            regions.addAll(DEFAULT_CREATIVE_REGIONS);

        return regions;

    }

}
