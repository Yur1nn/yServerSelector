# yServerSelector

A Velocity + Paper pair that provides a real chest GUI selector.

## Architecture

- `yServerSelector` (Velocity):
  - owns command handling, server snapshots, native queue/fallback logic, and connection routing.
  - sends menu payloads to the player's current backend server over plugin messaging.
- `yServerSelector-Paper` (Paper, required on each child server):
  - receives menu payload and opens a native chest GUI.
  - sends selected entry key back to Velocity over the same channel.

## Features

- `/server` command opens a real chest inventory menu
- Static enable/disable per item (`enabled`)
- Optional hide when backend is offline (`show-when-offline`)
- Native server groups (`groups`) with balancing modes:
  - `ROUND_ROBIN`
  - `LEAST_PLAYERS`
  - `RANDOM`
  - `FIRST_AVAILABLE`
  - `FILL`
- Proxy ping polling + Paper heartbeat for online/player count visibility
- Built-in native queue system
- Optional fallback routing when destination connect/queue fails
- Group picker UX:
  - Left-click group entry: normal routing/queue
  - If already on a member of that group, left-click opens member list instead of queueing
  - Right-click group entry always opens member list

## Commands

- `/server` - open selector menu
- `/server join <key>` - connect to a configured entry by key
- `/server status` - show your current queue status
- `/server leave` - leave your current queue
- `/server debug` - print runtime routing/group/queue diagnostics
- `/server reload` - reload config (permission-controlled)

## Config (Velocity)

Configuration file is generated at:

- `plugins/yserverselector/config.yml`

Edit `menu.items` to define servers, slots, and placeholders.
Make sure `menu.plugin-message-channel` matches the Paper plugin channel.

## Build

```bash
gradle build
```

Output jar:

- `build/libs/yserverselector-1.0.0.jar`

## Install

1. Build and install this jar to Velocity `plugins`.
2. Build and install the Paper companion jar to every child server `plugins`.
3. Restart proxy + child servers.

## Notes

Velocity does not open native chest inventories directly, so the Paper companion is mandatory for GUI mode.
