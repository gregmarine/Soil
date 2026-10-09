# Where the supporting knowledge lives

Soil's design rests on work recorded in two other repositories. Read these before designing or
building the matching part.

## The shell: home screen and side menu

| What | Where |
|---|---|
| How the Supernote's side bars, side menu and home screen work, every route tried, and the fresh-boot sequence | `~/git/g-paper/launcher-demo/README.md` |
| The working demo: a home screen plus an accessibility service that owns the bars | `~/git/g-paper/launcher-demo/` |
| The raw side-bar key probe | `~/git/g-paper/probe-slide/` |

Set up and undone with adb; the commands are in the demo's README.

## The seam: an app reading through a hub

| What | Where |
|---|---|
| The measurements and how to rerun them | `~/git/g-paper/probe-seam-hub/README.md` |
| The probe hub and probe app | `~/git/g-paper/probe-seam-hub/`, `~/git/g-paper/probe-seam-app/` |

## What Soil replaces

All under `~/git/Notesprout/apps/notesprout_sn/docs/` unless a path says otherwise.

| Subject | Document |
|---|---|
| The interface between host and extensions, and the extension store | `extensions.md` |
| Library and search | `library.md` |
| The notebook screen | `notebook.md` |
| Links | `links.md` |
| Clipboard | `clipboard.md` |
| Encryption and keys | `encryption.md` |
| Backup | `backup.md` |
| Restore | `restore.md` |
| Export and import | `export.md`, `import.md` |
| Cloud storage | `cloud.md` |
| Documents | `document.md` |
| Tags | `tags.md` |
| Scratch Pad | `scratchpad.md` |
| Calendar and events | `calendar.md` (the calendar as built in SN; Soil's is `docs/calsprout.md`) |
| Objects: sticky notes, text, shapes | `objects.md` |
| Page templates | `templates.md` |
| Shared paper-screen code | `sn-screen.md` |
| Bible | `~/git/Notesprout/extensions/bible/docs/bible.md` (the reader as built in SN; Soil's is `docs/biblesprout.md`) |
| Sketch | `~/git/Notesprout/extensions/sketch/docs/sketch.md` (the sketch face as built in SN; Soil's is `docs/sketchsprout.md`) |
| The `.soil` file format | `~/git/Notesprout/docs/soil-file-format.md` |
| The library index format | `~/git/Notesprout/docs/global-index-format.md` |
| The e-ink design system | `~/git/Notesprout/docs/design-system.md` |

The plan files beside those documents are history. Read them to learn why something is the way
it is; never resume from them.

## The last conversion

`~/git/Notesprout/tools/og2sn/` converted the original Notesprout library into a Notesprout SN
backup. The conversion into Soil follows the same pattern.

## Traps already paid for

- **The Manta identifies as a Nomad.** Target devices by serial only.
- **A raw key given to SQLCipher's `ATTACH` must be text**, in the form `"x'…'"`. A binary key
  is treated as a passphrase and the file fails to open.
- **`sqlcipher_export` does not copy `user_version`.** Copy it across by hand.
- **sqlcipher-android's `rawQuery(String, Object...)`** takes a Kotlin `arrayOf<Any>(…)` as one
  argument. Pass the values themselves.
- **`adb shell input keyevent` never reaches an accessibility service's key filter** on the
  Supernote. Only a real swipe on the bar tests the menu.
- **`adb shell input text` is swallowed** by the Supernote's keyboard. Type by tapping keys.
- **`adb push` into an app's `Android/data` folder deletes the target.** Push to
  `/data/local/tmp` and copy from there, or stream in with `adb exec-in run-as`.
- **A grey border is invisible on e-ink.** Use black for anything that must be seen.
- **A disabled button looks the same as an enabled one on e-ink.** Hide it, or let it be tapped
  and explain.
- **SQLCipher's cursor window is 8 MiB by default.** A value larger than the window is written
  and never read back. Soil sets the window above the seam's cap where the library loads.
- **On a RASTER page the Supernote's direct path never reads the template.** Paper goes in the
  sheet. And every whole-page engine call coalesces into one rebuild only when nothing suspends
  between them: decode first, then call the engine back to back.
- **A broadcast receiver that works on Main past its timeout gets the app killed.** Post the
  work; the debug doors do.
- **A butt-capped path with round joins ends however the hand wobbled at the lift.** Trim the
  samples within half the width of the end before drawing (g-paper's `MarkerTrim`).
