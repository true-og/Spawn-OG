package spawnog.flight;

import org.bukkit.GameMode;

// The kind of flight a player left with, deciding how /spawnback puts them
// back into the air. Classified once at quit and carried through the record.
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

    // NoClip wins because /nc drives the gamemode itself. Survival flight is
    // FLY only with a real /fly toggle, so a toggle is never invented.
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
