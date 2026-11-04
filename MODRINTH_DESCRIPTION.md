# PlayerInspector

### Player inspection, inventory management, restoration, and staff vanish in one plugin

**PlayerInspector** is a Minecraft Java Edition server administration plugin that lets server operators inspect players, investigate their most recent death, manage their inventories, and restore saved items. Version **1.0.1** also adds direct shulker-box and bundle browsers and an OP-only `/vanish` command.

**One plugin. One main inspection command. More control for server staff.**

---

## Features

### Player statistics

- See a player's total playtime, deaths, player kills, and mob kills.
- Inspect their most recent recorded death: time, location, cause, attacker, and items carried.
- View the vanilla last-death coordinates if a detailed plugin record does not exist.

*Detailed death inventory snapshots begin only after PlayerInspector is installed.*

### Complete inventory inspection

- Read-only inspection of inventory, hotbar, armor, offhand, and ender chest.
- Item quantities, durability, and container contents.
- Browse shulker boxes and bundles, including nested contents.
- Inspect saved snapshots for offline players (not their live files).
- Filter container browsing to the player's inventory, ender chest, or last-death snapshot.

### Live inventory editing

- Take, give, move, swap, and rearrange items in online players' inventories.
- Edit ender chests, armor, and offhand items.
- Before opening an editor, save a safety backup to disk.
- Prevent conflicting transfers by rechecking actual items before editing.

*Editing and restoration require the target player to be online.*

### Death investigation and recovery

- Record the time, place, cause, and inventory at the most recent death.
- Inspect a player's death inventory without changing their current items.
- Request a confirmed restoration from a recorded death or other saved revision.

### Versioned backups and restoration

- Create manual backups and maintain safety backups.
- Browse retained backup revisions and restore a selected revision by ID.
- Restore inventory, ender chest, or both, with confirmation and safety checks.
- Configure backup retention in `plugins/PlayerInspector/config.yml`.

### Staff vanish (new in 1.0.1)

- `/vanish` lets an OP hide from ordinary players and their tab lists.
- Regular players receive a configurable fake "left the game" message on vanish and a fake "joined the game" message on return.
- Other OPs and staff with `playerinspector.vanish.see` can still see vanished administrators.
- Optional default protections block chat, item pickup, block interactions, and combat while vanished.
- Vanish resets when an administrator disconnects or the plugin/server restarts.

**Vanish is not full packet-level stealth.** It does not conceal presence from every other plugin, server ping count, command, map, or console. Another plugin controlling `/vanish` may conflict.

---

## Commands

Use `/pi <player>` for player inspection. Aliases: `/playerinspect` and `/pinspect`.

| Command | Description |
|---|---|
| `/pi <player>` | Open the player overview |
| `/pi <player> stats` | View playtime and death statistics |
| `/pi <player> inventory` | Inspect inventory, armor, offhand, durability, and nested containers |
| `/pi <player> ender` | View ender chest contents |
| `/pi <player> death` | View the most recent death and recorded items |
| `/pi <player> shulkers [all\|inventory\|ender\|death]` | Browse shulker boxes and their contents (read-only) |
| `/pi <player> bundles [all\|inventory\|ender\|death]` | Browse bundles and their contents (read-only) |
| `/pi <player> edit` | Edit an online player's inventory |
| `/pi <player> editender` | Edit an online player's ender chest |
| `/pi <player> backup` | Save a manual inventory backup |
| `/pi <player> backups [page]` | List saved inventory backup revisions |
| `/pi <player> restore death` | Request restoration of the latest death inventory |
| `/pi <player> restore manual all` | Request restoration of a manual inventory and ender chest backup |
| `/pi <player> restore <backup-ID> [inventory\|ender\|all]` | Request restoration of a selected backup revision |
| `/pi <player> confirm` | Confirm a pending restoration |
| `/vanish` | Toggle staff invisibility and send a fake leave/join message |
| `/vanish on` | Become hidden from ordinary players |
| `/vanish off` | Return to visibility |
| `/vanish status` | Check your current vanish state |

Singular aliases `/pi <player> shulker` and `/pi <player> bundle` also work. Omit the source filter (or use `all`) to search inventory and ender chest. The `death` filter searches the last-death snapshot.

### Examples

```text
/pi Steve
/pi Steve shulkers
/pi Steve bundles ender
/pi Steve death
/pi Steve backup
/pi Steve restore death
/pi Steve confirm
/vanish
/vanish off
```

---

## Permissions

Inspection and editing commands require **OP status**, with their corresponding `playerinspector.*` permissions.

- `playerinspector.use` - Access `/pi` and inspection commands (OP required).
- `playerinspector.edit` - Edit live inventories and create backups (OP required).
- `playerinspector.restore` - Restore items (OP required).
- `playerinspector.vanish` - Use `/vanish` (OP required).
- `playerinspector.vanish.see` - View other vanished staff (OPs can always see them).

## Compatibility

- **Edition:** Minecraft Java Edition
- **Target versions:** 1.21.0 to 1.21.11
- **Intended platforms:** Paper, Spigot, Purpur, and compatible Bukkit-based forks
- **Java:** 21
- **Not supported:** Fabric/Forge/NeoForge standalone mod loaders, Folia, or proxy-only servers

## Installation

1. Stop the server and back up the world and plugin data.
2. Place the `.jar` file in the `plugins/` folder.
3. Restart the server, OP your account, then run `/pi <player>` or `/vanish`.
4. Edit vanish messages and protection settings in `plugins/PlayerInspector/config.yml` if desired.

### Experimental testing notice

PlayerInspector **1.0.1 is an experimental release**. The source targets the Spigot API for Minecraft 1.21.0, but live compatibility across Paper/Spigot 1.21.0-1.21.11 has not been fully tested. Earlier prototype builds had API-linkage errors, so use an official-API Maven build for production candidates. Do not test inventory restoration on valuable items or a production server before making backups.

---

**Inspect. Investigate. Manage. Restore. Vanish.**
