# PlayerInspector changelog

## 1.0.1 (experimental)

- New OP-only `/vanish [on|off|status]` command with configurable fake join/quit messages, player/tab hiding for non-staff, staff visibility exceptions, and session-scoped state.
- Additional default vanish protections for public chat, item pickup, interaction, block break/place, and combat.
- New `/pi <player> shulkers [all|inventory|ender|death]` and `/pi <player> bundles [all|inventory|ender|death]` commands and singular aliases.
- Read-only browsing of shulker boxes and bundles, including nested contents, with source filters for inventory, ender chest, and last-death snapshot.
- Updated plugin.yml, config.yml, README, Modrinth description, source build workflow, and artifact names to version 1.0.1.
- Existing inventory inspection, live editing, latest death record, versioned backups, and confirmation-based restoration remain available.
- The companion experimental staging JAR is **not an official Spigot/Paper API build**; real-server testing remains required.

For details and test steps, see `1.0.1-RELEASE-NOTES.md` and `VERIFICATION.md`.
