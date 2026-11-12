# Mace SMP: datapack + MaceSMPTracker plugin

Two parts that work together:

| Part | File | What it does |
|---|---|---|
| Datapack | `mace_datapack_26.3.zip` | The Mace recipe, head drops, advancements, edible heads, recipe-book lock |
| Plugin | `MaceSMPTracker-1.0.0.jar` (you build it, see below) | Tracks every Mace, enforces the limit on Maces that **exist**, blocks Crafters, adds `/macesmp` |

The limit is **not** a lifetime limit. It is "at most N Maces exist right now" (default 3).
When a Mace breaks, burns, despawns or is otherwise destroyed, its slot is free again.

---

## READ THIS FIRST: what has and has not been verified

I could not download the Paper 26.3 API in my build environment (no network), so:

* **The plugin jar is NOT included and has NOT been compiled against the real Paper 26.3 API.**
  The Bukkit-facing code was compile-checked against hand-written stubs, which only proves it is
  internally consistent. Any API mismatch will show up as a compile error on your first real build.
* The plain-Java core (registry, NBT reader, region-file reader, disk audit) **was** compiled and tested
  for real: 28 checks pass, including real Anvil region files, truncated files and unsupported compression
  (`verification/check.sh` re-runs them).
* I confirmed from the Javadoc that `EntityRemoveEvent` exists in Paper 26.3 and that `CrafterCraftEvent`
  (with `getResult()` and `setCancelled()`) exists up to 1.21.11. I did not confirm the exact Paper 26.3 Maven
  version string, so `build.gradle.kts` uses `26.3.build.+`, and `plugin.yml` uses `api-version: '26.1'`
  so it is not rejected for being too new.
* **Nothing has been run on a live server.** Test it on a copy of your world first.

---

## Installation

### 1. Build the plugin (you need a jar)

Easiest, no software to install: **GitHub Actions**.
1. Create a free GitHub repository and upload everything in this folder (keep `.github/`).
2. Open the **Actions** tab, open the latest "build" run, download the **MaceSMPTracker** artifact (the jar).

Or build locally with JDK 25: `gradle build` (jar in `build/libs/`) or `mvn package` (jar in `target/`).
If the build cannot resolve `paper-api:26.3.build.+`, look up the exact 26.3 version at
`https://repo.papermc.io/service/rest/repository/browse/maven-public/io/papermc/paper/paper-api/`
and put it in `build.gradle.kts` (or `pom.xml`). Compile errors that mention a Bukkit/Paper class or method
mean the API changed; send me the error and I will fix it.

### 2. Install on BaoHost

1. Stop the server.
2. **Files → `world/datapacks`**: delete the old `mace_datapack_26.3.zip` and `mace_locked_26.3.zip` (the lock pack
   is no longer used), then upload the new `mace_datapack_26.3.zip`. Keep that exact file name.
3. **Files → `plugins`**: upload `MaceSMPTracker-1.0.0.jar`.
4. Start the server. In the console you should see `MaceSMPTracker enabled. Limit 3, active 0.`
5. Run `/macesmp status`. The line `Datapack: detected` confirms the two parts are connected.
6. If your world already contains Maces, run `/macesmp recount`. Maces without a tracker id are adopted
   (see `untracked-mace-policy`) so they count toward the limit.

---

## Operator commands

Permission: `macesmp.admin` (default: operators). Alias: `/mace`.

| Command | Effect |
|---|---|
| `/macesmp status` | Active Maces and the limit, where they were last seen, datapack link, last sweep/audit |
| `/macesmp limit 5` | Sets the global limit to 5 and saves it to `config.yml` |
| `/macesmp limit 3` or `/macesmp limit reset` | Restores the default limit (3) |
| `/macesmp recount` | Full audit (see below) |
| `/macesmp reload` | Reloads `config.yml`; a bad value falls back to its default and is reported, the old settings stay active if the reload itself fails |
| `/macesmp list` | Every active Mace: short id, who forged it, age, last known place |

Lowering the limit below the number of active Maces removes nothing. Crafting is simply blocked until the
count drops below the new limit.

---

## Configuration (`plugins/MaceSMPTracker/config.yml`)

| Key | Default | Meaning |
|---|---|---|
| `global-mace-limit` | `3` | Max Maces existing at once (0 to 1000) |
| `block-crafter` | `true` | The Crafter block can never produce a Mace |
| `track-player-inventories` | `true` | Inventory, armor, offhand, cursor of online players |
| `track-ender-chests` | `true` | Ender chests of online players |
| `track-containers` | `true` | Chests, double chests, barrels, shulker boxes (also nested), hoppers, furnaces, chest minecarts/boats, horse chests... |
| `track-dropped-items` | `true` | Item entities on the ground |
| `track-entities` | `true` | Item frames, mob equipment |
| `save-data-automatically` | `true` | Background save of `registry.tsv`. If false: only on shutdown and after `/macesmp recount` |
| `save-interval-seconds` | `30` | How often it saves when something changed |
| `untracked-mace-policy` | `REGISTER` | A Mace with no id found in a loaded area: `REGISTER` (adopt, counts), `REMOVE`, `IGNORE` |
| `destroyed-mace-reappears` | `REACTIVATE` | A Mace marked destroyed is found again: `REACTIVATE` or `REMOVE` |
| `sweep.interval-seconds` | `120` | Background sweep of online players and loaded chunks (0 = off) |
| `sweep.chunks-per-tick` | `8` | Sweep work per tick; lower it if you ever see lag |
| `audit.save-worlds-first` | `true` | Save before `/macesmp recount` reads files |
| `audit.scan-offline-players` | `true` | Read offline players' `.dat` files |
| `audit.scan-region-files` | `true` | Read all region and entities files (unloaded chunks) |
| `audit.free-missing-slots` | `false` | Free a slot for a Mace found nowhere in several audits (see warning below) |
| `audit.missed-audits-before-free` | `2` | Audits in a row it must be missing |
| `registry.prune-destroyed-after-days` | `30` | How long destroyed records are kept |
| `announce.*` | `true` | Chat messages when a Mace is forged/destroyed, admin warnings |
| `messages.*` | | All player-facing text. `%active%`, `%limit%`, `&` colour codes |

---

## How it works

**Identity.** Every Mace forged at a crafting table gets a random UUID stored in the item's persistent data
(`macesmptracker:mace_id`). It survives chests, shulkers, anvils, enchanting, trading between players,
dropping and picking up, chunk unloads, restarts and logging out. The registry
(`plugins/MaceSMPTracker/registry.tsv`, atomically written with a `.bak` copy) records every id as ACTIVE or
DESTROYED. The count of ACTIVE records is the number that counts against the limit. A Mace in an unloaded
chunk or on an offline player stays ACTIVE until something proves it is gone, so offline players and unloaded
chunks never free a slot by accident. Moving a Mace never changes its id, so it is never counted twice.

**Crafting.** At the crafting table the result is stamped with a fresh id (`PrepareItemCraftEvent`), re-checked
against the limit when taken (`CraftItemEvent`), and registered only if the craft really completes. Everything runs
on the server thread one event at a time, so two players cannot both take the last slot. Shift-click crafting and
the "combine two Maces" repair recipe are refused. `CrafterCraftEvent` is cancelled for Maces, so a Crafter can
never make one; normal crafting tables are unaffected.

**Destruction (frees a slot immediately):** durability break (`PlayerItemBreakEvent`), a dropped Mace removed by
fire, lava, explosion, cactus, the void or despawn (`EntityRemoveEvent` with cause DEATH/DESPAWN/OUT_OF_WORLD),
and a mob wearing or holding one despawning. Pick-ups, merges and chunk unloads are not destruction.

**Background sweep.** Every `sweep.interval-seconds` the plugin looks at all online players and all loaded
chunks (spread over several ticks). If the same id is in two different slots *in the same final tick*, the
extra copies are removed (duplicate-exploit protection) and admins are told. Untracked and "destroyed but
back" Maces are handled by the two policies above. A joining player is also checked.

**`/macesmp recount` (full audit).** A sweep of everything the API can see, plus a read-only scan of the files
on disk: every offline player's inventory and ender chest, and every region and entities file (all chunks,
loaded or not, including shulkers, bundles, item frames and mob equipment; works with both the old and the 26.1+
world folder layout). Anything found anywhere counts as present; it reports what is missing, duplicated or
untracked. It never frees a slot unless `audit.free-missing-slots` is true, **every** file was read
completely, and the Mace was missing in `missed-audits-before-free` audits in a row.

### How the plugin and the datapack communicate

They share one scoreboard objective, `mace_data`.

Plugin → datapack (written about once per change, on the server thread):

| Score (fake player) | Meaning |
|---|---|
| `#plugin` | `1` while the plugin runs, `0` when it stops. Switches the datapack's own limit off |
| `#active` | Number of active Maces |
| `#limit` | Current limit |
| `#locked` | `1` when `active >= limit` |

Datapack → effect: every tick it reads `#locked`. At 1 it takes the Mace recipe away from all players
(`recipe take`); when the plugin frees a slot (`#locked` back to 0) it gives the recipe back. The datapack does
this with the game rule `limited_crafting` (it unlocks all other recipes for everyone, so crafting otherwise behaves
as normal). **The plugin also refuses the craft by itself**, so the limit holds even if the datapack is missing or
the recipe was unlocked by some other means. The plugin warns in the console if it cannot find the datapack.

If the plugin is not installed or is removed, the datapack falls back to its original behaviour: a limit on
Maces ever crafted (`/scoreboard players set #limit mace_data N`, default 3). Everything else in the datapack
(recipe, head drops, advancements, edible heads) is unchanged and does not depend on the plugin.
The advancement is now titled **"Power Comes With A Cost..."**.

---

## Known limitations (so nothing is pretended)

1. **Other plugins' storage is invisible.** Team/guild ender chests, backpacks, vaults, mailboxes keep items in
   their own files. Your console shows UltimateTeams creating ender chest backups, so a Mace stored there cannot be
   seen. That is why `audit.free-missing-slots` is `false` by default. Slots are still freed by the events above.
2. **Silent deletion is only caught by an audit with `free-missing-slots: true`.** `/clear`, `/kill`, `/fill`,
   `/setblock` removing a container with items, or another plugin deleting items produce no event.
3. **Duplicates are only detected where both copies are loaded at once.** A copy on an offline player or in an
   unloaded chunk is found by the audit (it is reported, not deleted, because offline files are never edited) and
   removed by the next sweep when both copies are loaded.
4. **Not scanned live:** items inside bundles, and items sitting in an open anvil/grindstone/etc. window. Bundles
   are covered by the disk audit; GUI items return to the player on close.
5. **Region files using LZ4 compression or external `.mcc` chunks cannot be read.** The audit then reports itself
   as incomplete and frees nothing. Your `server.properties` uses the default `deflate`, which is supported.
6. **A Mace that dies with a mob** drops unless the mob's drop chance is below 100%; a vanishing one is
   unobservable until an audit.
7. **A false "destroyed" reading** (for example an unusual removal cause) is self-healing: the next sweep or audit
   finds the Mace again and `destroyed-mace-reappears: REACTIVATE` counts it as active again.
8. A crash can lose up to `save-interval-seconds` of registry changes. The next sweep/audit re-adopts any
   Mace that still carries its id.
9. The plugin is for Paper. Folia is not supported.
10. Ops using `/give` or creative mode create untracked Maces; with the default policy they are adopted and
    count toward the limit.

---

## Files

```
src/main/java/dev/macesmp/tracker/   plugin source (MaceSMPTracker, CraftListener, LifecycleListener,
                                      Sweeper, AuditRunner, MaceRegistry, MaceNbt, RegionScanner, DiskAudit, ...)
src/main/resources/                  plugin.yml, config.yml
src/test/java/                       CoreSelfTest (plain Java, no server needed)
verification/                        API stubs + check.sh used for the compile check and self test
build.gradle.kts, pom.xml            build files;  .github/workflows/build.yml  online build
```
