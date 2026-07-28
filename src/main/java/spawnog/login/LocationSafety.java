package spawnog.login;

import java.util.Set;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;

// Decides whether a login location would hurt or kill the player who logs in
// there. The bar is deliberately low: only positions that damage the player on
// arrival count as unsafe. Standing in water, tall grass, on a slab, or on any
// other block that is not a full cube is normal survival play and stays safe.
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
