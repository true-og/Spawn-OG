# Spawn-OG

Spawn-OG owns safe player entry, spawn and respawn behavior, and TrueOG's
WorldGuard-based regional flight policy. It replaces EssentialsXSpawn and the
flight and gamemode management portions of legacy WGamemode-OG on Purpur 1.19.4.

## Login safety

1. Preserve the player's current invulnerability state and make them
   invulnerable while repair is in progress.
2. Load and teleport to the configured global spawn when relocation is needed.
3. Change non-staff spectator, creative, or adventure players to survival.
4. Successfully rescued autopsy players are told their original world and block coordinates in chat so they can identify the location they were browsing.

By default, login normalization is limited to `world`, `world_nether`, and
`world_the_end`. Add other survival worlds explicitly rather than applying the
rule to minigame or build worlds accidentally.

## Regional flight

Inside a `fly` region, players with `spawnog.flight` can toggle flight using
`/fly`. Inside a `nofly` region, flight is suspended unless the player has
`spawnog.flight.bypass`. Previous flight permission is restored on region exit
or disconnect, and fall damage caused by forced landing is cancelled once.

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
  warmup.
- `/spawn <player>` — Teleport another player to their resolved spawn.
- `/setspawn [group] [normalize-view] [normalize-position]` — Configure a spawn.
- `/fly` — Toggle flight inside a configured `fly` region.

## Permissions

- `spawnog.spawn` — Use `/spawn`.
- `spawnog.spawn.others` — Use `/spawn <player>`.
- `spawnog.setspawn` — Configure spawns.
- `spawnog.login-migration.bypass` — Explicitly exempt staff from login
  migration and normalization.
- `spawnog.flight` — Use regional `/fly`; granted by default.
- `spawnog.flight.bypass` — Ignore `nofly` regions; not granted by default.

## Building

Use the TrueOG bootstrap, or run:

```sh
./gradlew clean build eclipse --warning-mode all
```
