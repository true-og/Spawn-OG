package spawnog.login;

import org.bukkit.GameMode;
import org.bukkit.entity.Player;

// Whoever decides which gamemodes a player may hold. GameModeInventories-OG owns
// that rule when it is installed; Spawn-OG's own rules stand in when it is not.
public interface GamemodeAuthority {

    // Null when the authority cannot answer right now, so the caller falls back.
    Boolean mayUse(Player player, GameMode gamemode);

}
