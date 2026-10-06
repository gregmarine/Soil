# Soil

**Soil is the ground the Sprout apps grow in.** It is a home screen, a library and a storage
engine for handwriting-first e-ink devices, starting with the Supernote Nomad and Manta.

Soil keeps every notebook, sketchbook and document as an encrypted `.soil` file and is the only
app that reads or writes them. The apps you work in are separate installs that ask Soil for
their pages:

| App | What it is for | Its files |
|---|---|---|
| **Soil** | Home screen, library, side menu, keys, clipboard, backup, tags, links, Scratch Pad, export | The library index |
| **Notesprout** | Handwritten notebooks | Notebooks |
| **Sketchsprout** | Raster pencil and ink sketching | Sketchbooks |
| **Docsprout** | Written documents in Markdown | Documents |
| **Biblesprout** | Bible reader; its position and recents in Soil's app store | None of its own |
| **Calsprout** | Calendar and events | None yet; one calendar |

Soil replaces Notesprout SN and its extensions. It is the successor, not a companion.

## Status

**The first build is done**, and runs on the Supernote Nomad as its home screen. None of the
Sprout apps exist yet.

| Part | State |
|---|---|
| Home screen: the library (empty) and the app drawer, with apps that can be hidden | Built, walked on the Nomad |
| Encryption: recovery key, unlock, lockout, change passphrase, forget | Built, walked on the Nomad |
| Library index | Built; holds nothing yet |
| Scratch Pad | Built, walked on the Nomad by hand |
| Seam, handshake only | Built, walked on the Nomad |
| Side menu and home screen role | Built, walked on the Nomad by hand |

- [`docs/first-build.md`](docs/first-build.md) — the plan for the first build as it was granted, and what was decided. A record.
- [`docs/building.md`](docs/building.md) — building, installing, turning the shell on and off.
- [`docs/encryption.md`](docs/encryption.md), [`docs/shell.md`](docs/shell.md),
  [`docs/scratchpad.md`](docs/scratchpad.md), [`docs/seam.md`](docs/seam.md) — each part as built.
- [`docs/design.md`](docs/design.md) — the design as decided so far, with the device probes behind it.
- [`docs/references.md`](docs/references.md) — where the supporting probes and documents live.
- [`BACKLOG.md`](BACKLOG.md) — ideas deliberately set aside for later.

## Related repositories

- [Notesprout](https://github.com/gregmarine/Notesprout) — the current apps, and the source of most code Soil will reuse.
- g-paper — the drawing engine, used as a published dependency. The device probes live there.

## License

MIT. See [`LICENSE`](LICENSE).
