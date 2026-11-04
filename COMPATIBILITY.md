> **v1.0.1:** The test JAR incorporates earlier fixes to the seven known bad `openInventory(Inventory)` binary references (the actual return type is `InventoryView`). The source is configured to build against the official 1.21.0 Spigot API and now has an ABI regression check. None of the 12-version matrix jobs or real server integration tests have run in this environment. The 1.0.1 experimental JAR remains UNVERIFIED on a real server; it also includes new shulker/bundle commands and staff vanish.

# PlayerInspector 1.0.1 — Minecraft 1.21 compatibility contract

## Intended server targets

| Minecraft version | Maven baseline / plugin API declaration | Runtime status |
|---|---|---|
| 1.21.0 (published as `1.21`) | 1.21.0 / `1.21` | Targeted; not tested on a running server |
| 1.21.1 | 1.21.0 / `1.21` | Targeted; not tested on a running server |
| 1.21.2 | 1.21.0 / `1.21` | Targeted; not tested on a running server |
| 1.21.3 | 1.21.0 / `1.21` | Targeted; not tested on a running server |
| 1.21.4 | 1.21.0 / `1.21` | Targeted; not tested on a running server |
| 1.21.5 | 1.21.0 / `1.21` | Targeted; not tested on a running server |
| 1.21.6 | 1.21.0 / `1.21` | Targeted; not tested on a running server |
| 1.21.7 | 1.21.0 / `1.21` | Targeted; not tested on a running server |
| 1.21.8 | 1.21.0 / `1.21` | Targeted; not tested on a running server |
| 1.21.9 | 1.21.0 / `1.21` | Targeted; not tested on a running server |
| 1.21.10 | 1.21.0 / `1.21` | Targeted; not tested on a running server |
| 1.21.11 | 1.21.0 / `1.21` | Targeted; not tested on a running server |

The 1.21.0 API is called `1.21-R0.1-SNAPSHOT` in the official Spigot repository. Targeting the **minimum** supported API (not 1.21.4 or 1.21.11) is necessary to build one binary for the whole range. `plugin.yml` declares `api-version: '1.21'`, a valid server API version that Paper/Spigot accepts on the 1.21 line. Java 21 is required. Supported runtimes are standard **Spigot and Paper**; **Folia is not supported**.

The version guard in `ServerVersion` uses `Bukkit.getBukkitVersion()` and rejects non-1.21 versions, patches after 1.21.11, and malformed values before opening data files. The guard does **not** establish binary compatibility; the official API compile and server tests still matter.

## What was checked against external documentation (30 October 2026)

- Paper's `plugin.yml` reference documents minor API version semantics and its rejection of plugins requiring a newer server API: https://docs.papermc.io/paper/dev/plugin-yml/
- The official Spigot artifact index includes both `1.21-R0.1-SNAPSHOT` and `1.21.11-R0.1-SNAPSHOT`: https://hub.spigotmc.org/nexus/service/rest/repository/browse/public/org/spigotmc/spigot-api/
- Early 1.21 `BundleMeta#getItems()` is documented: https://javadoc.mcio.dev/spigot/1.21/org/bukkit/inventory/meta/BundleMeta.html
- Paper lists Java 21 for 1.21.0–1.21.11: https://docs.papermc.io/paper/getting-started/

The source uses Bukkit/Spigot API only; it has no hardcoded `org.bukkit.craftbukkit.v1_21_R*` / NMS references and no external plugin dependencies. This reduces cross-patch breakage risk but is **not** a substitute for testing.

## Build and CI validation

Build locally (requires Java 21, Maven 3.9+ and access to official Spigot repositories):

```bash
mvn -B -ntp clean verify
```

For an automated official-dependency compile, push this source project as the **root** of a GitHub repository and run the `Build against Minecraft 1.21.0 API` workflow. GitHub Actions checks Java 21 and compiles the **release JAR against the oldest API** (`spigot-api:1.21-R0.1-SNAPSHOT`), runs JUnit 5 tests, inspects and uploads that JAR. A separate twelve-entry matrix also compiles the source against the Spigot API for **each** of 1.21.0 through 1.21.11, to catch missing or changed method signatures. The matrix **has not yet run**, and even if it passes, CI does **not** start a server or certify runtime behavior.

For version-parser-only checks without Maven or Bukkit:

```bash
sh scripts/test-supported-versions.sh
```

## Repeat on staging Paper and/or Spigot instances of each of the 12 releases

1. **Startup:** Java 21, startup message with correct server version, no `Unsupported API version`, `NoSuchMethodError`, `NoClassDefFoundError`, or `InvalidPluginException`. Plugin rejects 1.20.x, 1.21.12 and 1.22.x.
2. **Permissions:** OP can inspect; non-OP with the permission explicitly granted is still denied; console text commands work but GUIs remain player-only.
3. **Stats/death:** total playtime/deaths show; kill by mob/player/fall, inspect cause, attacker, time, location and pre-death inventory. Confirm keep-inventory true/false does not alter drops.
4. **Inspect:** inventory, hotbar, armor, offhand, ender chest; tool durability; enchanted, named, custom-component items; nested shulker boxes and bundles where supported by that game release.
5. **Edit:** move item in/out, merge, split, swap, armor/offhand/ender chest; another player changes the target slot before click; target disconnects; multiple admins; ensure no duplication/loss.
6. **Backup/restore:** create, retain multiple revisions, select by ID; cancel confirmation, restore inventory only, ender only, all; recovery after plugin reload and restart; invalid snapshot must be refused safely.
7. **Upgrade:** back up the whole world and `plugins/PlayerInspector/`, upgrade **one release at a time or on a clone**. Test backup restoration with rich item metadata before reusing backups. **Downgrades are not supported.**
8. **Failure:** read-only data folder, full disk, mid-restore interruption, concurrent inventory changes; confirm edits/restores are blocked when safety backup fails.

Always test with disposable items and copies of the world. A CI green check only proves the project compiled and its unit tests passed on the chosen API; it does not prove runtime safety on all 12 releases.
