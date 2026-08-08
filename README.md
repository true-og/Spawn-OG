# Spawn-OG

Safe player entry, spawn management, and WorldGuard regional flight for TrueOG.

## Features

- **Spawn Management:** Allows setting and teleporting to the server spawn point.
- **Login Safety Migration:** Relocates players safely to spawn upon login if they are in a dangerous location, and allows returning via `/spawnback`.
- **Regional Flight:** Integrates with WorldGuard to allow players to toggle flight (`/fly`) inside designated regions.
- **Broad Compatibility:** Works seamlessly with LuckPerms, WorldEdit, WorldGuard, GameModeInventories-OG, NoClip-OG, MyWorlds, and Essentials-OG.

## Commands

| Command | Description | Permission |
| --- | --- | --- |
| `/spawn [player]` | Teleport yourself (or another player) to the server spawn | `spawnog.spawn` / `spawnog.spawn.others` |
| `/setspawn [args]` | Set or clear the server spawn point | `spawnog.setspawn` |
| `/spawnback` | Return to the location a login safety migration moved you from | `spawnog.spawnback` |
| `/fly` | Toggle flight inside a WorldGuard region configured to allow it | `spawnog.flight` |

## Permissions

- `spawnog.spawn` - Use `/spawn` on yourself (default: true)
- `spawnog.spawn.others` - Use `/spawn <player>`
- `spawnog.setspawn` - Use `/setspawn`
- `spawnog.spawnback` - Use `/spawnback` to return to your previous location (default: true)
- `spawnog.login-migration.bypass` - Resume flight in place on relog; exempt from autopsy migration
- `spawnog.flight` - Use `/fly` inside regions configured to allow flight (default: true)
- `spawnog.flight.bypass` - Exempt a player from regional no-flight rules
- `spawnog.flight.*` - Grant regional flight use and bypass
- *(Legacy WGamemode-OG aliases: `wgamemode.fly`, `wgamemode.fly.bypass`, `wgamemode.*`)*

## Compilation and Build

This project uses Gradle. To build the plugin:

```bash
./gradlew build
```

This will produce the plugin jar in the `build/libs` directory. Spotless is used for formatting and Checkstyle for code style checks.

## License

MIT

## Authors

- kasumabalidps
- NotAlexNoyle
