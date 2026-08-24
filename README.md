# Spawn-OG

Safe player entry, spawn management, and WorldGuard regional flight for [TrueOG Network](https://true-og.net).

## Features

- **Spawn Management:** Global, per-group (LuckPerms), and first-join spawns. The global spawn is synced into MyWorlds.
- **Teleport Warmup:** `/spawn` on yourself has a five-second countdown that moving cancels; `/spawn <player>` pulls the target instantly.
- **Login Safety Migration:** Players logging in at dangerous spots, in unsanctioned gamemodes, or unauthorized mid-flight are rescued to spawn. Every rescue records a way back before the move and explains why it happened.
- **Rescue Undo:** `/spawnback` (warn, then confirm) returns the player, lifting obstructed or hazardous spots and refusing sealed ones. Prior flight or gamemode is restored where policy permits; otherwise the player lands in survival under fall protection.
- **Fall Protection:** One-shot immunity covering exactly the fall the plugin caused.
- **Gamemode Authority:** GameModeInventories-OG decides all gamemode questions; Spawn-OG owns flight. Built-in exemption rules are a fallback only.
- **Regional Flight:** `/fly` inside WorldGuard regions marked `fly`; higher priority wins, ties are deny-first. The toggle survives restarts, and plugin-granted flight survives reloads without escaping tracking.
- **Compatibility:** LuckPerms, WorldEdit, WorldGuard, GameModeInventories-OG, NoClip-OG, MyWorlds, Essentials-OG.

## Commands

| Command | Description | Permission |
| --- | --- | --- |
| `/spawn [player]` | Teleport yourself (warmup) or another player (instant) to spawn | `spawnog.spawn` / `spawnog.spawn.others` |
| `/setspawn [group] [normalize-view] [normalize-position] [unset]` | Set or clear a spawn point | `spawnog.setspawn` |
| `/spawnback` | Return to where a login safety migration moved you from | `spawnog.spawnback` |
| `/fly` | Toggle flight inside a region that allows it | `spawnog.flight` |

## Permissions

- `spawnog.spawn` (default: true), `spawnog.spawn.others`, `spawnog.setspawn`
- `spawnog.spawnback` (default: true)
- `spawnog.login-migration.bypass` - Resume flight in place on relog; exempt from autopsy migration
- `spawnog.flight` (default: true), `spawnog.flight.bypass`, `spawnog.flight.*`
- *(Legacy aliases: `wgamemode.fly`, `wgamemode.fly.bypass`, `wgamemode.*`)*

## Compilation and Build

```bash
./gradlew build clean eclipse
```

The jar lands in `build/libs`.

## License

MIT

## Authors

- kasumabalidps
- NotAlexNoyle
