# Soil — instructions for Claude Code

Soil is a hub app for handwriting-first e-ink devices, with the Sprout apps (Notesprout,
Sketchsprout, Docsprout, Biblesprout, Calsprout) built over it. It replaces Notesprout SN.
Supernote Nomad and Manta first.

## Status

**First build under way, on the branch `first-build`.** Greg granted it on 2026-09-28. The plan,
with his decisions and the step order, is `docs/first-build.md`. Work only within that plan: do
not start anything beyond it, or anything in `BACKLOG.md`, until Greg asks for it. The branch is
merged when Greg is happy with it, not before.

## Build

- `./gradlew test` — the JVM tests. `./gradlew assembleDebug assembleRelease` — both builds.
- Modules: `:soil` (the app), `:seam` (the interface to the Sprout apps), `:paper` (shared
  theme, chrome and ink), `:seam-stranger` (joins the build only where its key exists).
- Debug installs as `com.symmetricalpalmtree.soil.dev`, release as `com.symmetricalpalmtree.soil`.
  Both are signed with `~/.android/debug.keystore`.
- g-paper comes from `mavenLocal()`. Its version is pinned in `paper/build.gradle.kts` only.

## Testing on the device

- Ink does not show in a screenshot, and a pen cannot be injected. Writing is walked by Greg.
- The side bars cannot be injected. Only a real swipe tests the menu.
- `adb shell input text` is swallowed. Tap the on-screen keys, or use Copy and Paste.
- Never read a recovery key off the device: not in a screenshot, not in a view dump, not in a
  log. To move one, use the screen's own Copy and the field's Paste.
- Ask before changing the device's home screen or accessibility settings. The commands, and the
  way back, are in `docs/building.md`.

## Read first

- `docs/first-build.md` — the plan for the first build.
- `docs/building.md` — building, installing, the shell on and off.
- `docs/encryption.md`, `docs/shell.md`, `docs/scratchpad.md`, `docs/seam.md` — each part as built.

- `docs/design.md` — every decision so far, the device measurements, and the open questions.
- `docs/references.md` — where the shell and seam probes, the Notesprout SN documents, and the
  known traps live.
- `BACKLOG.md` — ideas set aside on purpose. Do not re-raise them as new; do not schedule them
  without a decision.

## How to work here

- Assume nothing. Ask clarifying questions, one at a time.
- Explain first, then ask. A long explanation and a question in the same turn gets cut off.
- Decisions are Greg's. Mark anything unconfirmed as proposed.
- Commit and push together.

## Standing rules carried from Notesprout

- Kotlin, Java 17.
- `kotlinx.serialization` only for JSON.
- No new Gradle dependency without discussion.
- No Material Components.
- Never block the main thread.
- E-ink design: black on white, no colour in the interface, no animation, no shadows,
  Tabler outline icons.
- Passphrases and keys are never logged, never put in an Intent, never written to the index.
- Install only on the device asked for. Nomad by default: `SN078D10012852`. The Manta is
  `SN100C10023972` and identifies as a Nomad, so target by serial.

## Related repositories

- `~/git/Notesprout` — the current apps and their documents. Reference, and the source of
  reused code.
- `~/git/g-paper` — the drawing engine and the device probes.
