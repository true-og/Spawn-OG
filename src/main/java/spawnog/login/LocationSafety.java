package spawnog.login;

import java.util.Set;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;

// Judges whether a login spot would hurt the player on arrival. The bar is low
// on purpose: water, grass, slabs, and other partial blocks all stay safe.
public final class LocationSafety {

    // A drop this long or shorter is survivable, so a player suspended above one is
    // left where they logged out.
    private static final int MAX_SAFE_FALL_BLOCKS = 5;

    // Blocks that damage a player occupying them.
    private static final Set<Material> CONTACT_HAZARDS = Set.of(Material.LAVA, Material.FIRE, Material.SOUL_FIRE,
            Material.WITHER_ROSE);

    // Blocks that damage a player standing on top of them.
    private static final Set<Material> SURFACE_HAZARDS = Set.of(Material.MAGMA_BLOCK, Material.CAMPFIRE,
            Material.SOUL_CAMPFIRE, Material.CACTUS);

    private LocationSafety() {

    }

    public static Issue check(Location location) {

        if (location == null)
            return Issue.MISSING_WORLD;

        World world = location.getWorld();
        if (world == null)
            return Issue.MISSING_WORLD;
        if (location.getY() < world.getMinHeight() || location.getY() >= world.getMaxHeight())
            return Issue.OUT_OF_BOUNDS;
        if (!world.getWorldBorder().isInside(location))
            return Issue.OUTSIDE_BORDER;

        Block feet = location.getBlock();
        Block head = feet.getRelative(BlockFace.UP);

        // Only full opaque cubes suffocate. Slabs, stairs, fences, carpets, snow
        // layers, and plants are all passable enough to stand inside.
        if (feet.getType().isOccluding() || head.getType().isOccluding())
            return Issue.SUFFOCATION;
        if (CONTACT_HAZARDS.contains(feet.getType()) || CONTACT_HAZARDS.contains(head.getType()))
            return Issue.HAZARD;

        return checkLanding(feet, world);

    }

    public static boolean isUnsafe(Location location) {

        return check(location).unsafe();

    }

    // Whether something underfoot holds the player (ground or liquid), telling
    // a finished landing, when protection is no longer owed, from a live fall.
    public static boolean isSupported(Location feet) {

        if (feet == null)
            return false;
        return isSolidUnderfoot(feet.clone().subtract(0.0D, 0.08D, 0.0D))
                || isSolidUnderfoot(feet.clone().subtract(0.0D, 0.51D, 0.0D));

    }

    private static boolean isSolidUnderfoot(Location location) {

        Block block = location.getBlock();
        return block.isLiquid() || !block.isPassable();

    }

    // The lowest position at or above the given one that neither suffocates nor
    // burns. Landing is not judged (caller covers falls); null when blocked.
    public static Location clearAbove(Location location) {

        if (location == null)
            return null;

        World world = location.getWorld();
        if (world == null)
            return null;

        int x = location.getBlockX();
        int z = location.getBlockZ();
        int startY = Math.max(location.getBlockY(), world.getMinHeight());

        for (int y = startY; y < world.getMaxHeight() - 1; y++) {

            Block feet = world.getBlockAt(x, y, z);
            Block head = feet.getRelative(BlockFace.UP);
            if (feet.getType().isOccluding() || head.getType().isOccluding() || CONTACT_HAZARDS.contains(feet.getType())
                    || CONTACT_HAZARDS.contains(head.getType()))
                continue;

            // The exact recorded position is kept whenever it is already clear.
            if (y == location.getBlockY())
                return location.clone();
            return new Location(world, location.getX(), y, location.getZ(), location.getYaw(), location.getPitch());

        }

        return null;

    }

    private static Issue checkLanding(Block feet, World world) {

        Block block = feet;
        int drop = 0;

        while (block.getY() >= world.getMinHeight()) {

            Material type = block.getType();

            // Water breaks a fall of any height; lava ends it.
            if (block.isLiquid())
                return type == Material.LAVA ? Issue.HAZARD : Issue.NONE;

            // Ladders, vines, and scaffolding hold the player in place.
            if (Tag.CLIMBABLE.isTagged(type))
                return Issue.NONE;

            // Anything with collision carries the player: full blocks, slabs, stairs,
            // trapdoors, fences, and everything else they can stand on.
            if (!block.isPassable()) {

                if (SURFACE_HAZARDS.contains(type))
                    return Issue.HAZARD;
                return drop <= MAX_SAFE_FALL_BLOCKS ? Issue.NONE : Issue.LONG_FALL;

            }

            block = block.getRelative(BlockFace.DOWN);
            drop++;

        }

        return Issue.VOID;

    }

    public enum Issue {

        NONE("safe"), MISSING_WORLD("its world is not loaded"), OUT_OF_BOUNDS("it is outside the world's build limits"),
        OUTSIDE_BORDER("it is outside the world border"), SUFFOCATION("it is inside a solid block"),
        HAZARD("it is inside or on top of a damaging block"), LONG_FALL("it is a lethal drop above the ground"),
        VOID("there is nothing below it");

        private final String description;

        Issue(String description) {

            this.description = description;

        }

        public String description() {

            return description;

        }

        public boolean unsafe() {

            return this != NONE;

        }

    }

}
