# Player Manager

**Minecraft plugin:** PlayerInspector v1.0.1

Inspect, manage, and recover player inventories from one **OP-only** command. Written for **Spigot/Paper 1.21.0–1.21.11**, targeting the **Minecraft 1.21.0 Spigot API** (`1.21-R0.1-SNAPSHOT`) and **Java 21**. The `plugin.yml` declares `api-version: '1.21'` (not 1.21.4). This is a *source-level compatibility target*, **not** proof of testing on all twelve releases. The plugin uses public Bukkit/Spigot APIs, not NMS or dependencies on other plugins. It intentionally refuses to start on versions outside 1.21.0–1.21.11.


## New in 1.0.1: shulker, bundle and vanish commands

- `/pi <player> shulkers [all|inventory|ender|death]` - browse all shulker boxes in player storage (including nested containers) with read-only contents.
- `/pi <player> bundles [all|inventory|ender|death]` - browse bundles using the same controls. Singular aliases `/pi <player> shulker` and `/pi <player> bundle` also work.
- `/vanish [on|off|status]` - OP-only staff vanish; normal players receive a configurable fake quit/join message when toggling and cannot see vanished staff in the normal game world/tab list. Other OPs can still see them.

While vanished, public chat, pickup, combat and interactions are blocked by default. Configure these in `plugins/PlayerInspector/config.yml` under `vanish:`. This is **not** packet-level/total invisibility: other plugins, server-list player count, console output, maps, name autocomplete, or world effects may reveal someone is online. Vanish resets on relog or restart. 

**Experimental build:** This release is not verified on a running Paper/Spigot server. Back up data and build against the official Spigot API (`mvn clean verify`) before production use.


## Features

- **Stats:** native total playtime, lifetime deaths, player/mob kills, and the most recent death's date/time, coordinates, damage cause and attacker when recorded by this plugin.
- **Latest death inventory:** saves only the **most recent** detailed death record and a full item snapshot (36 main/hotbar slots, 4 armor, offhand, ender chest) before vanilla death handling clears dropped inventory. The plugin does **not** change item drops or keep-inventory rules.
- **Read-only inspection:** online players' current inventory or offline players' most recently saved snapshot; browse inventory, armor, offhand, ender chest, last-death inventory, and item durability.
- **Nested inspection:** click shulker boxes and bundles to inspect their contents, with depth limited to six nested levels. The contents of nested containers are **view-only**; the outer items can be moved using the live editor.
- **Live editor:** modify another **online** player's actual inventory and ender chest using an admin GUI. Left-click transfers full stacks, combines stacks, or swaps; right-click picks up half or places one. Use your own bottom inventory as the item source/destination. Armor and offhand slots are supported.
- **Complete restores:** replace inventory + armor + offhand from the last-death snapshot, the latest manual/safety backup, or an older backup by ID. Ender chest can also be restored separately or using the explicit `all` scope.
- **Safeguards:** requires actual OP status plus granular permissions, confirms destructive restores, rejects unexpected live-slot changes, disallows shift-click/drag transfer into the virtual editor, **waits for a persisted safety backup before opening the editor or restoring**, records admin actions, and closes the editor when its target leaves.
- **Backup history:** versioned write-once backup IDs with independent per-player retention limits (default 10 manual / 20 safety), restore transaction IDs, crash-ambiguity markers and legacy 1.1 backup compatibility.
- **Data:** YAML per-player snapshots, most recent death, versioned manual/safety archives, restore-result markers and `admin-actions.log` in `plugins/PlayerInspector/`. File writes are serialized through a single disk worker.

## Commands

`/playerinspect` supports aliases `/pinspect` and `/pi`.

| Command | Description |
|---|---|
| `/pi <player>` | Overview GUI: playtime, deaths, last death, inventory menu |
| `/pi <player> stats` | Text statistics including most recent death details (works from console) |
| `/pi <player> death` | Last-death details and inventory-at-death viewer |
| `/pi <player> inventory` | Read-only inventory + durability and nested shulker/bundle contents |
| `/pi <player> ender` | Read-only ender chest |
| `/pi <player> shulkers [all\|inventory\|ender\|death]` | Browse shulker boxes including nested contents (read-only) |
| `/pi <player> bundles [all\|inventory\|ender\|death]` | Browse bundles including nested contents (read-only) |
| `/vanish [on\|off\|status]` | Toggle OP invisibility and fake leave/join messages |
| `/pi <player> edit` | **Live** inventory editor: take, give, swap or move items |
| `/pi <player> editender` | **Live** ender chest editor |
| `/pi <player> backup` | Save a NEW immutable manual revision (whole inventory + ender chest) |
| `/pi <player> backups [page]` | List saved backup revisions and IDs (12 per page) |
| `/pi <player> restore death` | Request restore of latest death inventory + armor + offhand |
| `/pi <player> restore manual` | Request restore of saved manual inventory |
| `/pi <player> restore safety` | Request restore of latest safety inventory |
| `/pi <player> restore <backup-ID>` | Request restore from a particular historical revision (8+ char unique prefix or full ID) |
| `/pi <player> restore death ender` | Request restore of the ender chest as it was at death |
| `/pi <player> restore manual all` | Request restore of inventory **and** ender chest |
| `/pi <player> confirm` | Confirm pending restore within 60 seconds |

`restore` defaults to scope `inventory`. Available scopes: `inventory` (36 slots + armor + offhand), `ender`, `all`. The `manual` and `safety` aliases select the most recent snapshot of that type. Use `backups` and a specific ID to restore an earlier revision. Prefixes of 8+ hex characters are accepted only when unambiguous. **A restore replaces current items**; it does not merge. Do not restore a pre-death inventory while someone is collecting the dropped items unless you intend to duplicate those items. The plugin explicitly warns before confirmation.

To move items, open `/pi TargetPlayer edit`, pick up an item by clicking a target slot, then place it in your own lower inventory; or pick up something from your own inventory and place it into a target slot. Left/right clicks are supported; number keys, shift-clicks and drag-to-target are not. The live editor will not open until its safety backup has been successfully committed to disk. If the target's inventory changes while the backup is being written, the editor opening is cancelled, and you can retry. The editor refuses to overwrite a target slot that changed in the meantime.

### Permissions

All five permission nodes default to OP, **and the plugin separately checks `Player#isOp()`**. Giving a node to a non-OP does not grant access.

- `playerinspector.use`: inspect players (GUI + text)
- `playerinspector.edit`: use live inventory editor and make manual backups
- `playerinspector.restore`: request/confirm destructive restores
- `playerinspector.vanish`: OP-only self vanish command
- `playerinspector.vanish.see`: see other vanished players; OPs always see them

Console can inspect text stats, save manual backups and restore (if player is online); it cannot open GUIs.

## Installation / build

**Recommended: rebuild against the real Spigot API, then test on a staging server.**

1. Install **Java 21** and **Maven 3.9+** on your build computer.
2. In this project's folder, run `mvn clean package`. Maven resolves the official **oldest supported API** `org.spigotmc:spigot-api:1.21-R0.1-SNAPSHOT` . An internet connection is required for the first Maven build.
3. Copy `target/PlayerInspector-1.0.1.jar` to the server's `plugins/` folder.
4. Restart your **Paper or Spigot 1.21.0–1.21.11** server with **Java 21**; check server logs for errors.
5. `/op YourMinecraftName` and try `/pi YourMinecraftName` on a **test server**.
6. Back up your world and `plugins/PlayerInspector/` data before using destructive inventory restoration on production.


**Builds:** Use `mvn clean package` to compile against the official Spigot API. A successful compilation does not establish runtime compatibility. Use a backed-up staging server before production.

## Snapshot behavior and limitations

- Minecraft's native `DEATHS` and `PLAY_ONE_MINUTE` statistics provide lifetime counts / playtime. The game does **not** supply historical death-time inventory snapshots; only deaths after installing this plugin have one.
- Death details from the previous v1.0.0 `deaths/` format can be displayed as metadata only; inventory-at-death is not retroactively available.
- Offline-player inventory and ender-chest **editing is not supported**; offline inspection uses the most recent plugin snapshot. The plugin saves on join/quit, periodically (5 minutes by default), and after live edits. A player who never joined after installing the plugin may not have an inventory snapshot.
- Restore is blocked if the target is holding an item on their inventory cursor; have them place it in a slot first. Crafting grid and inventory cursor slots are not included in stored snapshots.
- The plugin stores **one latest-death snapshot**, but manual and safety backups are now **versioned**, independently retained, and restorable by ID. Existing `backups/<uuid>.manual.yml` and `backups/<uuid>.safety.yml` are still available as `legacy-manual` / `legacy-safety` revisions.
- `backups.keep-manual` and `backups.keep-safety` in `config.yml` set the per-player count caps for versioned backups. New writes do not overwrite previous revisions. Older backups are pruned only after the new revision is fully written. **Legacy and unresolved pre-restore backups are exempt from automatic deletion.** This can exceed configured limits.
- A manual backup reports success **only after the file is written**. Live editing requires an on-disk pre-edit backup before opening; restore requires an on-disk pre-restore backup before replacing items. Both reject changes to the target's inventory during backup writing.
- Each restore gets a 32-hex-character transaction ID and a dedicated safety snapshot. Successful/aborted operations write a status marker under `restore-transactions/<uuid>/<tx>.txt`. If the server stops between backup creation and recording the outcome, `/pi <player> backups` flags the record as `UNRESOLVED` (or it may still be in progress): inspect the player's current inventory and the saved backup before taking further action. Unresolved safety revisions are protected from retention cleanup.
- Restores are locked per target to prevent simultaneous restores. If applying an inventory snapshot throws an exception, the plugin attempts to roll back using the durable pre-restore snapshot; a failed rollback is reported as an emergency and requires manual recovery.
- Even with safeguards, restoring pre-death items can duplicate recovered drops. Inventory interactions with other plugins, server crashes, or other inventory formats can cause unexpected results; test first and maintain server backups.
- Item durability displayed uses vanilla material maximum; custom max-durability item components or modded items may differ.
- If the saved ender chest contains occupied slots beyond the current server's ender-chest size, restoration of that chest is rejected rather than silently truncating it.
- Malformed or incomplete inventory YAML is refused rather than interpreted as an empty inventory. File/metadata compatibility must still be checked on a real Paper server.
- Backups and restore results use temporary files, file sync calls and atomic publication where the filesystem supports them. These substantially reduce common partial-write failure modes but **do not guarantee recovery from every power failure, hardware fault, or cross-plugin interference**. Keep independent world/plugin-data backups.
- **Backups across server-version changes:** Bukkit YAML item serialization is version-dependent. The source code accepts all 1.21.0–1.21.11 runtimes, but **moving a newer-version backup into an older-version server is not supported**; newer materials/components may be lost or rejected. Even when upgrading within 1.21.x, test restoring a backup copy containing enchanted gear, shulkers and bundles before relying on recovery.
- Inventory editing and snapshot restoration must be checked on a staging Paper/Spigot server before production use; compiling against the 1.21 API alone is not sufficient.
- The plugin is **not Folia thread-scheduler compatible**: it assumes a traditional single main Bukkit server thread. Do not deploy to Folia without an appropriate scheduler redesign and verification.
- Date and time is displayed in UTC.

## Files / architecture

- `src/main/java/dev/playerinspector/PlayerInspectorPlugin.java`: events, supported-server version guard, startup and scheduler
- `ServerVersion.java`: standalone server-version parsing for Minecraft 1.21.0–1.21.11
- `InspectCommand.java`: command router, player lookup, OP checks and text stats
- `InspectionMenus.java`: read-only viewers, item durability and nested content browsing
- `RemoteInventoryEditor.java`: live editor session and guarded click handling
- `InventoryEditRules.java`: isolated transfer/stack behavior
- `PlayerSnapshot.java`: defensive copies and restore scope logic
- `BackupRevision.java`: backup IDs, file naming, retention candidate selection
- `AtomicFiles.java`: write-once/publish-atomically file I/O separate from Bukkit logic
- `DeathRecord.java`, `LastDeath.java`: death metadata and most recent death inventory
- `RestoreService.java`: two-step restore confirmation, pre-restore backup, consistency checks
- `PlayerDataStore.java`: YAML serialization and ordered background disk writes
- `COMPATIBILITY.md`: support matrix, validation workflow and release-specific edge cases
- `.github/workflows/build.yml`: builds a JAR with Java 21 and official Spigot API (no test files in the repository).

No source code was copied from OpenInv, InvSee++, InventoryRollbackPlus, or other referenced plugins. Feature comparison does not imply compatibility with, or endorsement by, these third-party projects.
