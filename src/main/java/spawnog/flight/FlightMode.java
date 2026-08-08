package spawnog.flight;

import org.bukkit.GameMode;

// The kind of flight a player was using when they left, which decides how
// /spawnback puts them back into the air. Classified once from the quit
// snapshot and carried through the return record.
public enum FlightMode {

    // Regional /fly: survival flight granted by RegionFlightService.
    FLY,
    // Creative flight from /gmic (GameModeInventories-OG).
    GMIC,
    // NoClip-OG's /nc, which phases between creative and spectator.
    NOCLIP,
    // Plain spectator flight, never restored by /spawnback.
    SPECTATOR,
    // No flight to restore: a grounded rescue or a record predating modes.
    NONE;

    // NoClip wins over the gamemode because /nc drives the gamemode itself: a
    // noclipping player reads as creative or spectator depending on the tick.
    // Survival flight only counts as FLY when the player had actually toggled
    // /fly; flight without that intent (a landing loan, or a record whose
    // gamemode is unreadable) is NONE, because /spawnback must never invent a
    // /fly toggle the player did not make.
    public static FlightMode classify(GameMode gamemode, boolean noclip, boolean flyIntent) {

        if (noclip)
            return NOCLIP;
        if (gamemode == GameMode.CREATIVE)
            return GMIC;
        if (gamemode == GameMode.SPECTATOR)
            return SPECTATOR;
        if (flyIntent)
            return FLY;
        return NONE;

    }

}
