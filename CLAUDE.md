# Soil — instructions for Claude Code

Soil is a hub app for handwriting-first e-ink devices, with the Sprout apps (Notesprout,
Sketchsprout, Docsprout, Biblesprout, Calsprout) built over it. It replaces Notesprout SN.
Supernote Nomad and Manta first.

## Status

**Design stage. No code, no plan.** Do not write a plan, open a phase, or scaffold a build
until Greg asks for it.

## Read first

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
