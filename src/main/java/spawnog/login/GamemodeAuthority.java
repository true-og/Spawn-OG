package spawnog.login;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;

// Whoever decides which gamemodes a player may hold. GameModeInventories-OG owns
// that rule when it is installed; Spawn-OG's own rules stand in when it is not.
public interface GamemodeAuthority {

    // Whether the player may hold the gamemode at the location, which is not
    // always where they stand: login safety asks about the destination it is
    // about to move them to. Null when the authority cannot answer right now,
    // so the caller falls back.
    Boolean mayUse(Player player, GameMode gamemode, Location location);

}
