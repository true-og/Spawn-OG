package spawnog.region;

import java.util.Set;

import org.bukkit.Location;

// Region membership at a location. Kept behind an interface so Spawn-OG can
// answer gamemode questions on servers without WorldGuard installed.
public interface RegionLookup {

    // Used when WorldGuard is unavailable: no location is inside any region.
    RegionLookup NONE = location -> Set.of();

    // Lower-case ids of every region containing the location.
    Set<String> regionsAt(Location location);

}
