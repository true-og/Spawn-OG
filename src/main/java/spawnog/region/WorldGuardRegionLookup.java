package spawnog.region;

import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import org.bukkit.Location;

// WorldGuard-backed lookup. Only instantiated when WorldGuard and WorldEdit are
// both enabled, so its classes are never resolved on servers without them.
public final class WorldGuardRegionLookup implements RegionLookup {

    @Override
    public Set<String> regionsAt(Location location) {

        if (location == null || location.getWorld() == null)
            return Set.of();

        RegionContainer container = WorldGuard.getInstance().getPlatform().getRegionContainer();
        return container.createQuery().getApplicableRegions(BukkitAdapter.adapt(location)).getRegions().stream()
                .map(ProtectedRegion::getId).map(id -> id.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());

    }

}
