# Soil — first build

**Finished and merged into `main` on 2026-09-29.** This page is now a record: the plan as it was
granted, and what was decided. Do not resume from it. Each part as built has a page of its own,
and those are what is current.

Every step was built and walked on the Nomad. Not walked: a passphrase that is typed rather than
generated. Everything marked *proposed* below was accepted as built. What differs from the plan:

- There is no `SoilOpenHelper`. `SoilDb` opens a file through `SoilCrypto` and runs the schema
  steps itself, which gives the same guarantees with nothing underneath that can create or
  delete a file.
- The fold of a typed key puts the `SOIL-` prefix back, because the prefix is spelled with the
  letters the fold rewrites.
- Steps 4 and 5 were built together, and step 9 before step 8, so that everything which does not
  change the device's settings was walked first.
- Unlock and lockout were walked with step 7, which is what can lock the library.
- The home screen became two views, the library and an app drawer, and apps can be hidden. The
  side menu lists Home and the Scratch Pad only; whether it carries apps is to be explored.
  See `shell.md`.

## Context

Soil has been at design stage: `docs/design.md` records the decisions and the Nomad probes, and
there is no code. Greg has now asked for the first build: the plumbing, the home screen, the side
menu, the Scratch Pad and the encryption work, on a branch that is merged once he is happy.

The aim is a Soil that runs on the Nomad as the shell, guards an encrypted library that is still
empty, and has one working writing surface. The Sprout apps grow from there.

Abbreviations: `SN` = `~/git/Notesprout/apps/notesprout_sn`, `GP` = `~/git/g-paper`.

## Decisions (Greg, 2026-09-28)

| Topic | Decision |
|---|---|
| Scope | Plumbing, home screen (HOME role), side menu, Scratch Pad, encryption core, Encryption screen, library index, seam service |
| Package | `com.symmetricalpalmtree.soil`; debug builds add `.dev` |
| Databases | SQLCipher directly. No Room, no KSP |
| Recovery key | Prefix `SOIL-`; otherwise as SN |
| Menu | Home, Scratch Pad, then every installed app with a launcher entry |
| App list look | Each app's own icon and name, in fixed pages with previous/next. No scrolling |
| Scratch Pad | All of SN's pad except Send. Fixed black pen, no shade picker |
| Seam | Handshake only: bind, signature permission, certificate check, one call |
| Modules | `:soil`, `:seam`, `:paper`, `:seam-stranger` |
| Signing | `~/.android/debug.keystore` for debug and release |
| Home screen | The library ("Nothing here yet") with the app grid below. Top bar: title, Scratch Pad, Encryption |
| Storage root | External files folder, as SN |
| Key gate | Until the key is acknowledged, Home asks for it and the pad and Encryption lead to the key screen. Grid and menu always work |

Deferred: per-item passphrases, Send from the pad, storage calls on the seam, backup, tags, links.

## Dependencies

All at SN's versions. Listed for Greg's approval under the no-new-dependency rule.

| Dependency | Version | Module |
|---|---|---|
| `gpaper-ratta` (brings `gpaper-core`), from `mavenLocal()` | 0.1.61 | `:paper` (api) |
| `net.zetetic:sqlcipher-android` | 4.6.1 | `:soil` |
| `androidx.sqlite:sqlite` | 2.4.0 | `:soil` |
| `androidx.security:security-crypto` | 1.1.0-alpha06 | `:soil` |
| `kotlinx-serialization-json` | 1.7.3 | `:soil` |
| `kotlinx-coroutines-android` | 1.8.1 | `:soil`, `:paper` |
| `androidx.lifecycle:lifecycle-runtime-ktx` | 2.8.7 | `:soil`, `:paper` |
| `androidx.core:core-ktx`, `androidx.appcompat:appcompat` | 1.13.1, 1.7.0 | all |
| `junit` (test only) | 4.13.2 | all |

Toolchain: Gradle 8.14, AGP 8.11.1, Kotlin 2.2.20, serialization plugin 2.2.20, SDK 35/29/35,
Java 17, `arm64-v8a`, `viewBinding` + `buildConfig`, `android.nonTransitiveRClass=false`.

## Layout

```
settings.gradle.kts  build.gradle.kts  gradle.properties  gradle/wrapper/
soil/            the app: crypto/ data/ bootstrap/ encryption/ home/ shell/ pad/ seam/
seam/            ISoilSeam.aidl, SeamHello, Seam (VERSION = 1), SeamCallerCheck
paper/           core/ chrome/ ink/ store/ + theme, styles, dimens, icons
seam-stranger/   one activity, signed with another key, expects refusal
```

## What is copied, adapted, written new

### `:paper` (from `SN/sn-screen` and `SN/ext-ink`)

- **Copied (package rename only):** `StrokeCodec`, `InkColorCodec`, `Slog`, `Dialogs`, `TopGuard`,
  `Immersive`, `SwipeMath`; `AnchoredBar`, `ChromeBand`, `ChromeToggle`, `CollapsedChrome`,
  `CollapsedTools`, `EraserBar`, `FloatingSelectionBar`, `InkSelectionBar`, `PageGestures`,
  `PageMath`, `PaperChrome`, `PaperToolbar`, `PenIdle`, `SelectionAnchor`, `UndoRedoStack`;
  `InkDocument`, `InkPage`, `InkUndo`, `PenIdleAwait`; the value types `Cell`, `Statement`,
  `Row`, `StoreRows` from `SN/extension-api/.../StoreCodec.kt`.
- **Adapted:** `InkStore` (takes a `RowStore`), `InkSql`, `StrokeRows`, `PaperScreenActivity`,
  `InkScreenActivity` (Send, pen shade and transfer code removed).
- **New:** `RowStore` — `exec(statements)` in one transaction, `query(statement)`.
- **Left behind:** `PaletteBar`, `PenShadeGlyph`, `ShadeIcon`, `StoreBatches`, `StrokeReadPlan`,
  `InkTransferSession`, `InkWire`, and the binder payload machinery. In-process there is no
  4 MiB limit to work around.
- **Resources:** colours, dimens (with `sw720dp`), theme and styles renamed `Theme.Soil` /
  `Widget.Soil.*`, the shapes, and the Tabler icons the copied code uses.

### Crypto (from `SN/app/.../crypto/` into `soil/.../crypto/`)

| Disposition | Files |
|---|---|
| Copied | `RawKeyDerivation`, `SecurePrefs`, `KeySession`, `AttemptLimiter`, `PassphraseRules`, `KeyFailure`, `RekeyCommit`, `RekeyFs`, `RekeyRecovery`, `RotationMarker` |
| Adapted, constants | `GlobalKey` (`SOIL-`), `PassphraseStore` (`soil_secure`), `DerivedKeyStore` (`soil_dkeys`), `KeyMaterial` (`__soil_index__`) |
| Adapted, logic | `SoilCrypto`, `KeyOpener`, `ExportKeying` → `RekeyExport`, `SoilRekey`, `RotationPlan`, `GlobalRotation` |
| Dropped | Everything for per-item passphrases and import; `NonDestructiveOpenHelperFactory` |

**Opening a file safely without Room.** This is the keyless-open data-loss family, so it is
explicit:

- One `SoilOpenHelper` over SQLCipher's own `SQLiteOpenHelper`. It always passes
  `KeepFileHandler` (logs, throws, never deletes) and never a positive minimum version, since
  SQLCipher deletes a file below that.
- Every raw open and every verify passes `KeepFileHandler`. SN passes `null` there.
- `requireExisting` before every open. `createRaw` is the only creating path and refuses a
  non-empty file. The header is probed, never opened to find out. No plaintext mode.
- `copyUserVersion` after every `sqlcipher_export`; the `ATTACH` raw key stays a TEXT literal.

**`SoilIndex`** (from `SnIndex.kt`): the same open state machine — `READY`, `FIRST_LAUNCH`,
`NEEDS_UNLOCK`, `FOREIGN_FILE`, `DAMAGED_FILE` — exposed as a `StateFlow` with a `PREPARING`
state, because a home screen cannot forward and finish as SN's bootstrap does.

**Rotation:** journal-first marker and the three resume paths are kept. Order: items (none yet),
the pad store, the index last.

### Index schema v1 — *proposed*

`soil.db`, `user_version = 1`, steps held in `IndexSchema`.

```sql
CREATE TABLE item (
    id TEXT PRIMARY KEY, kind TEXT NOT NULL, name TEXT NOT NULL,
    keyScope TEXT NOT NULL DEFAULT 'GLOBAL', flags INTEGER NOT NULL DEFAULT 0,
    createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, deletedAt INTEGER);
CREATE INDEX item_kind_alive ON item(kind, deletedAt);
CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);
```

Tags, links, covers and page counts each arrive later as their own migration step.

### Scratch Pad (from `SN/ext-scratchpad` into `soil/.../pad/`)

- `ScratchDocument`, `ScratchPages` copied; `ScratchStore`, `ScratchSql`, `ScratchSchema`,
  `ScratchToolbar`, `ScratchUndo`, `ScratchPadActivity` and its layout adapted.
- `PadStore` opens `garden/scratchpad.db` under the global key: WAL, foreign keys on (the
  page-to-stroke cascade needs it), schema by `user_version`.
- `SqlCipherRowStore` implements `RowStore`, adapted from SN's `StoreExecutor.kt`.
- *Proposed:* the pad has its own task, hidden from recents, so Back returns to the app
  underneath rather than to Home.

### Shell (from `GP/launcher-demo`)

- `SoilBarService`: as `BarService`. Observes the keys, locks only the side menu (never the
  status bar), re-locks after each app change, takes Home back after the boot push.
- `MenuOverlay` and `AppList`: Home, Scratch Pad, then the launcher apps, listed off the main
  thread, in fixed pages. The same list feeds Home's grid.
- `HomeActivity` does not bind the firmware launcher at all, so Soil works with the service off.
  *Proposed:* a one-line "Side menu is off" note on Home when it is.
- `KeyGate`: pure routing used by the pad and the Encryption screen. Intents carry a screen
  name only, never key material.

### Seam

- `ISoilSeam.hello()` returns `SeamHello(seamVersion, libraryUnlocked)`.
- *Proposed:* the permission is `${applicationId}.permission.SEAM`, so the `.dev` and release
  installs do not declare the same name.
- `:seam-stranger` builds only when its local keystore exists, so a fresh clone still builds.

### Bootstrap screens (from `SN/app/.../bootstrap/`, `encryption/`)

`RecoveryKeyActivity`, `UnlockActivity`, `BootstrapRoute`, `EncryptionActivity`, adapted. The
keyboard is never hidden on a passphrase field. Action buttons sit on the top bar.

## Steps

Branch `first-build` (*proposed name*) from `main`. Each step is committed and pushed together,
and each ends buildable.

| # | Step | JVM tests | Walk on the Nomad |
|---|---|---|---|
| 0 | Gradle plumbing, four modules, signing, `.gitignore`, status lines in `CLAUDE.md` and `README.md` | Smoke | Debug and release build; release verifies as signed |
| 1 | `:paper` core, chrome, resources | The eight `sn-screen` tests | — |
| 2 | `:paper` store and ink | `InkDocumentTest`, `StrokeRowsTest`, `InkSqlTest` | — |
| 3 | Crypto core, `SoilOpenHelper`, `IndexSchema`, `SoilIndex` | Crypto tests, schema test | Create, reopen; a wrong-key open leaves the file unchanged |
| 4 | Home by state, app grid. Not yet HOME | `AppList` | Opens as an ordinary app; grid pages and launches |
| 5 | Recovery key, unlock, lockout, `KeyGate` | `BootstrapRouteTest`, `KeyGate` | Key shown and gated; unlock by tapping keys; lockout |
| 6 | Scratch Pad | Pad tests | Write, erase, lasso, undo and redo, pages, chrome, kill and reopen |
| 7 | Encryption screen and rotation | Rotation and rekey tests | Rotate both ways; kill mid-rotation and resume; forget, unlock |
| 8 | Bar service, menu, HOME role | `BarGesture` | Real swipes. Menu and pad over other apps; status bar; reboot |
| 9 | Seam and the stranger | `SeamHello` | Stranger refused; same-key caller answered |
| 10 | Service off | — | Steps 4 to 7 again beside the firmware menu |
| 11 | Documents: `docs/` page per part, adb setup and undo in `README.md`, `design.md` open questions updated | — | — |

The shell comes late on purpose: everything before it is tested without changing device settings.

### `.gitignore`

The current file already covers Gradle, build output, `.kotlin/`, signing files, APKs and
databases. To add: `*.hprof`, `*.log`, `.cxx/` is present. Confirm
`gradle/wrapper/gradle-wrapper.jar` is committed.

## Verification

- `./gradlew test` for all JVM tests; `./gradlew assembleDebug assembleRelease`.
- Install on the Nomad only, by serial: `adb -s SN078D10012852 install -r …`.
- Steps 8 and 10 change device settings (`pm set-home-activity`, the accessibility setting).
  I ask before running them, and the commands to return to stock go in the README first.
- Ink cannot be seen in a screenshot and bar swipes cannot be injected, so steps 6 and 8 need
  Greg's hand. I stop and ask at those points.
- The recovery key is written down before any rotation or forget walk.

## Risks

| Risk | Handling |
|---|---|
| The pad opens over apps that never released the screen pipeline. SN always released first | Walked in step 8. If ink misbehaves, stop and discuss; do not work around it in Soil |
| Whether SQLCipher's default handler would delete a file is unproven on the device | Never relied on: every open passes `KeepFileHandler`. Step 3's wrong-key walk confirms |
| A very long single stroke against the cursor window | Tested in step 6 |
| Menu overlay over Soil's own live ink | Walked in step 8 |

## Still open, asked when the step is reached

- Rotation while the pad is open: refuse with a message (*proposed*), or close the pad.
- How to prove the per-call certificate check, which the stranger never reaches.
- A launcher icon for Soil.
