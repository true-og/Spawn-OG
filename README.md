# Spawn-OG

Spawn-OG owns safe player entry, spawn and respawn behavior, and TrueOG's
WorldGuard-based regional flight policy. It replaces EssentialsXSpawn and the
flight and gamemode management portions of legacy WGamemode-OG on Purpur 1.19.4.

## Login safety

1. Make the player invulnerable while repair is in progress, restoring their own
   state afterwards.
2. Teleport to the global spawn when relocation is needed.
3. Return unsanctioned spectator, creative, and adventure logins to survival.
4. Tell rescued players the world and block coordinates they came from.

Limited to `world`, `world_nether`, and `world_the_end`. Add other survival
worlds explicitly, so minigame and build worlds are not caught by accident.

### Who may log in outside survival

Non-survival logins become survival unless the player may use that gamemode
where they logged in. GameModeInventories-OG owns that rule; Spawn-OG asks its
`GameModePolicy` rather than keeping a second copy. There is no bypass.

Its rules: creative needs `gamemodeinventories.anywhere` or a creative region;
spectator needs `gamemodeinventories.spectator` or `noclip.use` while
`restrict_spectator` is on; adventure is never granted.

Without GameModeInventories-OG, Spawn-OG falls back to
`login-safety.gamemode-exemption-permissions` and `login-safety.creative-regions`,
which mirror the same rules. A creative region is a WorldGuard region matched by
id, not a flag; with no WorldGuard only `gamemodeinventories.anywhere` exempts
anyone.

### What counts as unsafe

Water, tall grass, slabs, stairs, and carpet are ordinary survival play and are
left alone. Only these are relocated: inside a full solid block, inside or on
top of a damaging block, in or above lava, above a drop longer than five blocks
with no water to break it, over the void, outside the world border, or outside
the build limits.

### Returning to an unsafe location

`/spawnback` warns why the position was flagged; `/spawnback confirm` within
thirty seconds sends the player back at their own risk. One shot per migration,
stored in `return-locations.yml`, which also carries the pre-migration gamemode
and flight state.

Fall damage on arrival is always cancelled, for every player and every return
point. Nothing else is: a return into lava or over the void is still fatal.

An airborne player is put back in the air, into the narrowest flight that covers
the descent. What they get is decided at the return point, not copied out of the
record:

- Inside a `fly` region, regional flight is granted and tracked, so leaving the
  region takes it back.
- Anywhere else it is a loan, revoked the moment the player lands, so
  `/spawnback` never leaves flight behind.
- Inside a `nofly` region nothing is granted unless the player has
  `spawnog.flight.bypass`, and `/spawnback` says so instead of failing silently.

`/gmic` and `/nc` only set a gamemode and let the server derive flight from it,
so a player the migration normalized out of creative or spectator has no ability
of their own left to resume. The loan stands in for it and expires on landing,
rather than outliving the gamemode it replaced. A player still holding one of
those gamemodes simply starts flying again.

Records predating the stored gamemode are handled the same way; only the stored
airborne flag decides whether flight is resumed at all.

## Respawn

Bed, then Essentials `home`, then the configured spawn — but only inside the
worlds from `login-safety.worlds`. Anything resolving outside them (a bed in a
minigame world, a death in one) is dropped in favour of the last of those worlds
the player was in. Beds and homes in the SMP are unaffected.

Set `respawn-at-home: false` to send every death to the configured spawn.

## Regional flight

Inside a `fly` region, players with `spawnog.flight` can toggle flight using
`/fly`. Inside a `nofly` region, flight is suspended unless the player has
`spawnog.flight.bypass`. Previous flight permission is restored on region exit
or disconnect, and fall damage caused by forced landing is cancelled once.

An enabled `/fly` toggle sticks, stored in `flight-intents.yml` until toggled
off, and is re-armed on relog, after a migration, and on region re-entry.

Grants are revoked on disconnect so none is ever written into a player's saved
abilities and becomes permanent free flight. That also grounds them, which the
saved abilities then remember instead of the flight, so a player taken out of
the air on the way out is noted in `airborne-quits.yml` and their next login
reads as airborne again. The note is spent by that login, whether or not it
migrates them.

Three consequences worth knowing:

- Relogging mid-air where flight will be re-armed is not an unsafe login. The
  player is left flying instead of pulled to spawn.
- Gamemode entitlement is judged at a rescue teleport's destination, so a
  creative login pulled into the spawn creative region keeps creative.
- Logging out mid-air on a grant and back in somewhere lethal still counts as
  the airborne login it was, so `/spawnback` returns the flight rather than the
  fall.

## Regional item drops

```text
/rg flag -w world spawn item-drop deny
```

Repeat the above command with each additional world and region that should deny
drops. Remove the explicit policy with `item-drop` and no value, or restore
drops with `item-drop allow`. Staff bypass, when genuinely required, is the
LuckPerms permission `worldguard.region.bypass.<world>`.

This replaces WGamemode-OG's `stopItemDrop` behavior with an explicit region
policy that applies consistently whether or not the player has toggled flight.

## Commands

- `/spawn` — Teleport to the global or LuckPerms-group spawn after a five-second
  warmup. Moving cancels it; damage does not.
- `/spawn <player>` — Teleport another player to their resolved spawn.
- `/setspawn [group] [normalize-view] [normalize-position]` — Configure a spawn.
- `/spawnback [confirm]` — Return to where a login safety migration moved you
  from.
- `/fly` — Toggle flight inside a configured `fly` region.

## Permissions

- `spawnog.spawn` — Use `/spawn`.
- `spawnog.spawn.others` — Use `/spawn <player>`.
- `spawnog.setspawn` — Configure spawns.
- `spawnog.spawnback` — Use `/spawnback`; granted by default.
- `spawnog.login-migration.bypass` — Exempt staff from autopsy migration only.
- `spawnog.flight` — Use regional `/fly`; granted by default.
- `spawnog.flight.bypass` — Ignore `nofly` regions; not granted by default.

## Building

GameModeInventories-OG is a submodule under `libs/`, compiled from source and
never bundled. `bootstrap.sh` fetches it, and `settings.gradle.kts` runs that on
every configure. Take a newer version with
`git submodule update --remote libs/GameModeInventories-OG`.

Use the TrueOG bootstrap, or run:

```sh
./gradlew clean build eclipse --warning-mode all
```
