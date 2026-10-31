# pVaults

Personal multi-page vaults for Paper, Folia and Fabric. Every player gets their own storage pages behind `/vault`, admins can open anyone's vault, and the plugin is built around not duplicating items.

## Requirements

| Platform | Versions | Jar |
|---|---|---|
| Paper (and forks) | 1.21.1 – 26.3 | `pVaults-paper-<version>.jar` |
| Folia | 1.21.4 – 26.3 | same jar as Paper |
| Fabric (server or singleplayer) | 1.21.11 | `pVaults-fabric-1.21.11-<version>.jar` + Fabric API |
| Fabric | 26.3 | `pVaults-fabric-26.3-<version>.jar` + Fabric API |

Paper 26.x and every Fabric 26.x server need Java 25; 1.21.x needs Java 21. On Fabric, permissions go through
[fabric-permissions-api](https://github.com/lucko/fabric-permissions-api) (bundled), so LuckPerms works as usual;
without a permissions mod, admin commands fall back to permission level 2.

## Installation

1. Download the jar for your platform (or [build it yourself](#building)).
2. Paper/Folia: drop it into `plugins/`. Fabric: drop it and Fabric API into `mods/`.
3. Start the server. The config is generated automatically (`plugins/pVaults/config.yml` or `config/pvaults.json`).
4. Edit the config to taste and run `/playervaults reload`.

## Commands

| Command | Description | Permission |
|---|---|---|
| `/vault [page]` | Opens your vault, page 1 by default. | `playervaults.use` |
| `/vault view <player> [page]` | Opens another player's vault, online or offline. | `playervaults.admin` |
| `/playervaults reload` | Reloads the config. | `playervaults.admin` |

Use the arrows in the bottom row to switch pages.

## Permissions

| Permission | Description | Default |
|---|---|---|
| `playervaults.use` | Open and use your own vault. | Everyone |
| `playervaults.admin` | `/vault view` and `/playervaults reload`. | Operators |
| `playervaults.pages.<n>` | Raises the player's page count to `n` (up to 50). The highest one they have wins. | See below |

Players without a `playervaults.pages.<n>` node get `default-max-pages`. On Paper and Folia, operators have every
`playervaults.pages.<n>` node by default, so ops get all 50 pages. On Fabric nobody does unless a permissions mod
grants it. Admins viewing someone else's vault can always open all 50 pages.

## Configuration

`plugins/pVaults/config.yml`

| Option | Default | Description |
|---|---|---|
| `messages.prefix` | `[Vault]` gradient | Put in front of every message. [MiniMessage](https://webui.advntr.dev/) format. |
| `messages.reload` | | Sent after `/playervaults reload`. |
| `messages.no-permission` | | Missing permission. |
| `messages.players-only` | | `/vault` run from the console. |
| `messages.invalid-page` | | Page out of range. `<max>` = the highest page the viewer can open. |
| `messages.page-in-use` | | The page is already open somewhere else (see [How it works](#how-it-works)). |
| `messages.unknown-player` | | `/vault view` for a name that never joined. `<name>` = the typed name. |
| `vault.default-max-pages` | `2` | Pages a player gets without any `playervaults.pages.<n>` node (1–50). |
| `vault.page-title` | `%player_name%'s Vault (%page%/%max_pages%)` | GUI title. MiniMessage, with `%player_name%`, `%page%` and `%max_pages%`. |
| `vault.reconcile-interval-ticks` | `20` | How often an open vault is re-synced in the background as a safety net. You shouldn't need to touch this. |

On Fabric the options live in `config/pvaults.json`: `defaultMaxPages`, `pageTitle`, `saveIntervalTicks` (how often
changed vaults are written to disk, default 100) and a `messages` map. Fabric messages use `&` color codes instead of
MiniMessage, and use `%max%` / `%name%` as placeholders.

## How it works

**Paper and Folia.** Each open vault page is leased to exactly one viewer, so if an admin tries to view a page the
owner currently has open (or the other way round) they get `page-in-use`. This is what stops two Folia region threads
from editing the same items at once. The GUI is snapshotted one tick after every click or drag, and on close, quit
and shutdown. Vault files are written in the background with at most one write per player in flight, through a temp
file and an atomic move, so a crash mid-save can't corrupt a vault.

**Fabric.** Every viewer of a page shares the same live inventory, just like a chest, so an admin and the owner can
have the same page open together and see each other's changes. Changed pages are saved every `saveIntervalTicks`
and whenever a vault is closed.

The bottom row of the GUI is locked: the filler panes and arrows can't be taken, swapped or shift-clicked into.

## Data

| Platform | Location | Format |
|---|---|---|
| Paper / Folia | `plugins/pVaults/vaults/<uuid>.yml` | Bukkit YAML, `pages.<page>.<slot>` |
| Fabric | `<world>/pvaults/<uuid>.dat` | Compressed NBT with a `DataVersion`; items are upgraded through Minecraft's data fixers after a version update |

Vaults made with older pVaults versions on Paper load as-is.

## Building

Requires JDK 21 and JDK 25 (Gradle toolchains pick them up, or downloads them via foojay).

```bash
git clone https://github.com/eyman26/pVaults.git
cd pVaults
./gradlew buildAll
```

All jars end up in `build/dist/`. `./gradlew :paper:check` also re-compiles the Paper sources against the newest
Paper API to catch removed API early, and `./gradlew :paper:runServer` / `:paper:runFolia` start a dev server.

## License

Released under the [GNU General Public License v3.0](LICENSE).

Made by **eyman_**.
