# Encryption

Everything Soil writes is encrypted from the first byte. The key model is Notesprout SN's,
carried over; this page records what Soil does and where it differs. The reasoning behind the
model is in Notesprout SN's `encryption.md` (see `references.md`).

## The keys

| Secret | Where it lives |
|---|---|
| The global passphrase, which is the recovery key | Keystore-backed storage (`soil_secure`), and in memory while the library is open |
| One raw key per file, derived from the passphrase | Memory, and Keystore-backed storage (`soil_dkeys`) |

- A recovery key is `SOIL-` and eight groups of four characters: 160 bits.
- A raw key is derived with PBKDF2-HMAC-SHA512, 256,000 rounds, salted with the file's first 16
  bytes. It takes about nine seconds on the Nomad, once per file; after that a file opens in
  about half a second.
- SQLCipher 4 with its defaults. No cipher settings are ever changed.
- An item with a passphrase of its own is designed for and not built: there are no items yet.

### The prefix and the look-alike fold

A key typed by hand is folded before it is tried: upper case, `O` to `0`, `I` and `L` to `1`.
`SOIL-` is spelled with exactly those letters, so the fold puts the prefix back as it is spelled
and folds only what follows. `soil-`, `S0IL-` and `SO1L-` all read as `SOIL-`.

## The one door

Every open goes through `SoilCrypto`. Its rules exist because a wrong key reads as corruption,
and a database library's answer to corruption can be to delete the file.

- Every open passes a handler that **keeps the file**. None is ever left to the default.
- Every open requires the file to exist and not be empty. Only `createRaw` creates, and it
  refuses a file that is already there.
- A file is probed by its header and opened only if it reads as encrypted. It is never opened to
  find out.
- There is no open without a key. A missing key means "not resolved yet", never "plaintext".
- There is no open helper. A helper chooses paths, creates what it does not find, and can delete
  a file it judges too old. `SoilDb` opens the file and runs the schema steps itself.

## The library's state

`SoilIndex` opens the index off the main thread and publishes what it found. Soil is the home
screen, so no screen waits for it: each one renders or routes by the state.

| State | Meaning |
|---|---|
| `PREPARING` | Being opened. Seconds on a first launch |
| `READY` | Open. The library is unlocked |
| `NEEDS_UNLOCK` | The file is there and no key on this device opens it |
| `FOREIGN_FILE` | A plaintext database where the index should be. Never opened |
| `DAMAGED_FILE` | Unreadable. Never created over, never deleted |
| `UNAVAILABLE` | Storage out of reach, or the open failed for another reason |

`KeyGate` turns the state, and two flags kept beside the key, into what a screen that needs the
key must do: open, wait, show the recovery key, unlock, or finish a rotation. The app grid and
the side menu never ask, which is what keeps the device usable while the library is locked.

## Changing the passphrase

A rotation re-keys every file the global key opens: items, then stores, then the index last.

- It is **journaled**. A marker is written before any file is touched and rewritten after each.
- Each file is copied under the new key beside the original, checked (it opens, it is intact, it
  kept its schema version), and only then renamed into place. `PRAGMA rekey` is never used.
- A death anywhere is found on the next launch. The home screen says a change was interrupted,
  every screen that needs the key leads to the Encryption screen, and its banner resumes.
- The index is closed for its own turn and opened again when the rotation ends.

## Forget on this device

Removes the passphrase and every raw key from the device, closes the index and the stores, and
locks the library. No file changes. Unlike Notesprout SN, the process is not killed: Soil can
close its index.

## While the Scratch Pad is open

Nothing on the Encryption screen runs while the Scratch Pad is open, shown or left behind another
app: its store cannot be re-keyed or closed under a live page. The person is asked to close it.

## Backup

A rotation clears every backup stamp before the index closes (`GlobalRotation.start`), so the
next run copies every file again under the new key. A restore installs the backup's key as this
device's global, acknowledged. See `docs/backup.md`.

## Walked on the Nomad, 2026-09-28

| Walk | Result |
|---|---|
| First launch creates the index | About 12 s, then half a second on relaunch |
| A wrong passphrase, a wrong raw key, a read under a wrong key | Each left the file byte for byte as it was |
| The recovery key gate | Refused without the tick, accepted with it |
| Rotation to a generated key | Two files, about 4 s |
| Kill while the index was being re-keyed | Original intact; resumed from the banner; no leftovers |
| Forget, three wrong keys, lockout, unlock | As designed |

Not walked: a passphrase that is typed rather than generated.

The debug build has a screen for the second row:

```
adb -s SN078D10012852 shell am start -n com.symmetricalpalmtree.soil.dev/com.symmetricalpalmtree.soil.dev.KeepFileCheckActivity
adb -s SN078D10012852 logcat -s SoilKeepFile
```
