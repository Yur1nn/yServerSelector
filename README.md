# VelocityServerSelector

A lightweight Velocity proxy plugin that provides a configurable `/server` selector menu with clickable entries.

## Features

- `/server` command to open a server selector menu
- Slot-based, chest-style layout rendered in chat
- Fully configurable menu title, rows, items, and lore
- Placeholder support in display and lore:
  - `%server%`, `%server_name%`
  - `%player_count%`, `%online%`
  - `%status%`
  - `%icon%`
  - `%has_ajqueue%`
- Optional AJQueue integration:
  - If AJQueue is present and item has `use-queue: true`, runs configured queue command
  - If AJQueue is not present, falls back to direct Velocity server connect

## Commands

- `/server` - open selector menu
- `/server join <key>` - connect to a configured entry by key
- `/server reload` - reload config (permission-controlled)

## Config

Configuration file is generated at:

- `plugins/velocityserverselector/config.yml`

Edit `menu.items` to define servers, slots, and placeholders.

## Build

```bash
gradle build
```

Output jar:

- `build/libs/VelocityServerSelector-1.0.0.jar`

## Install

1. Build the jar.
2. Place jar in your Velocity `plugins` folder.
3. Restart Velocity.

## Notes

Velocity does not provide direct inventory/chest GUI APIs like backend server plugins do. This plugin uses a chest-style clickable chat layout to stay proxy-native and lightweight.
