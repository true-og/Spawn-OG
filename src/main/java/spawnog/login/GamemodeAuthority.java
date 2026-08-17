package spawnog.login;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;

// Whoever decides which gamemodes a player may hold. GameModeInventories-OG owns
// that rule when it is installed; Spawn-OG's own rules stand in when it is not.
public interface GamemodeAuthority {

    // Whether the player may hold the gamemode at the location (often a rescue
    // destination, not where they stand). Null when the authority cannot answer.
    Boolean mayUse(Player player, GameMode gamemode, Location location);

    // Switches gamemode through the authority so its side effects (inventory
    // swaps, exemptions) apply; false when absent or the switch was cancelled.
    default boolean changeGameMode(Player player, GameMode gamemode) {

        return false;

    }

}
