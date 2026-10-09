# Backup and restore

A copy of the whole library off the device, and the way to put one back. Backup is reached from
the library's bottom bar; Restore is a row on the Backup screen.

## The parts

| Part | Where | What it does |
|---|---|---|
| `BackupActivity` | `:soil` `backup/` | The screen: the folder, Back up now, the cloud section, the Restore door, the last run's line |
| `BackupEngine`, `CloudBackupLeg` | `backup/` | One run, two legs; headless IO that never throws |
| `BackupPredicates`, `CloudBackupRules` | `backup/` | The pure rules: needs-backup, the work list, the file names, which legs, what ends a leg, what the report says |
| `BackupConfig`, `BackupStore` | `backup/` | What backup remembers, as one JSON value under the `backup` key of the index's `meta` table |
| `SafBackupWriter`, `SafBackupReader` | `backup/` | The local folder, written atomically over `DocumentsContract` and read back the same way |
| `SelfContainedSnapshot` | `backup/` | A cache copy with its WAL folded in, for the cloud, which has no atomic swap |
| `RestoreActivity` | `restore/` | The screen: the source, the backups found, the confirmation, the key prompt, the endings |
| `RestoreEngine` | `restore/` | Preflight, stage, validate, prove the key, prune orphans, commit; and the launch-time recovery |
| `RestoreManifest`, `RestoreRows`, `RestoreRecovery`, `RestoreDestination`, `RestoreStaging` | `restore/` | The pure rules, each pinned by test |
| `SafRestoreSource`, `CloudRestoreSource` | `restore/` | The two places a backup is read from |

## What a backup is

A folder holding `<id>.soil` for every alive item, `<name>.db` for every app store (the Scratch
Pad's pages, the cloud extension's account, `app_<package>` for an app's own store over the
seam such as Biblesprout's and Calsprout's, anything else in the garden), and `soil.db`, the
index, last. File names are ids, so a copy replaces in place; display names travel inside each
file. Every copy is a ciphertext byte copy under the global key, never decrypted. A debug build
writes into a `dev/` subfolder of the chosen folder, since debug and release coexist on the
Nomad; the cloud needs no such split, its root is already "Soil Dev".

Two destinations, run by one tap: the chosen folder on the device, and the cloud under
`Backups/<this device's folder>/` through the extension of phase 12. The cloud leg exists when
the "Back up to" tick is on, a provider is installed now, and the device's folder has been named.
The folder's name is typed by the person at first use (decision 2026-10-03), the model name as
the suggestion; a different name resets the cloud stamps, since a stamp is a statement about one
destination. Picking a different local folder resets the local stamps for the same reason, and
releases the previous folder's grant.

## The run

A run is refused while a rotation marker stands: the library is in two keys then, and a copy
taken under either could not be told apart.

1. The work list over every alive item and the stamp map: copy when not excluded and either
   never stamped or `updatedAt` is newer than the stamp. Equal means backed up. Excluded and
   up-to-date items are counted, not visited. The exclude bit is set from the library sheet and
   never bumps `updatedAt`.
2. Per item: a file an app holds open is skipped and counted. A live WAL is folded into the file
   through one open under the cached key, so the main file alone is a complete copy; a file that
   will not open is still copied as the bytes it is, its WAL alongside. A stale `<name>-wal` in
   the folder is deleted before the main file is written, and a delete that fails skips the
   write: a stale WAL beside a new main file would be replayed into it. The copy is atomic, and
   the stamp is written per success, immediately, with the `updatedAt` the work list read.
3. Every app store, every pass, no stamps: checkpointed if this process holds it open, then
   snapshotted, probed and copied like the index.
4. The index last: checkpointed, snapshotted into the cache, probed as encrypted and the live
   length, and streamed; only a failed snapshot streams the live file. A non-empty WAL after the
   checkpoint travels alongside.
5. The last-run figures move only when at least one write landed; the stamp map is pruned of
   items that no longer exist.

On the cloud leg every uploaded file is self-contained: a stale `<name>-wal` found in the folder
is deleted before the stamp, the one remote delete. The leg stops where it stands on a
not-connected, a network failure or a no-answer, keeping every stamp earned. A reported size that
disagrees with what was sent is counted failed and retried; nothing in the cloud is ever deleted
for it.

A rotation forgets every stamp before the index closes, so the next run copies everything again
under the new key. An import onto an existing id forgets that id's stamp.

## Restore

Replace-all, no undo (decision 2026-10-03): the index, every item file (a notebook's, a
sketchbook's or a document's, by id, never by kind) and every store, swapped
whole. A backup folder is an accretion, not a curated set, so a restore installs what the
backup's index names and the proven key opens, never "the folder". A writer's `<name>.old`
standing where `<name>` is absent is the last good copy a killed swap stranded, and is taken as
`<name>`. A cloud folder whose listing reaches the contract's cap of 1 000 entries may have been
truncated and is refused.

```
preflight → stage → validate(index) → prove the key → prune orphans → validate(items) → commit
```

- **Preflight** refuses while a rotation marker stands, while an app holds an item, or when the
  listing's bytes plus 64 MB of headroom will not fit the library volume.
- **Stage** fetches every manifest item into `restore_staging/` beside the garden, each through a
  `.part` renamed on completion. Any single failure fails the whole fetch. A disk that fills
  mid-fetch is named as the disk.
- **The key**: this device's cached global is tried silently, so a same-device backup never
  prompts. Otherwise the person types the passphrase or recovery key of the library the backup
  came from, verified as typed and then normalised, under the RESTORE attempt limiter.
- **Orphans**: a staged item file the staged index has no alive row for, and a store that is not
  encrypted SQLite or does not open under the proven key, are left out and named in the ending,
  never installed. The stores are verified read-only, so a staged WAL stays what the manifest
  measured. An alive, not-excluded item the staged index names but the backup does not carry
  (held open or missing when it was taken) is named in the ending, after a line saying it was not
  in this backup; its row is installed with no file. Item files are not verified against the key:
  each has its own salt, so a check is a full key derivation per item.
- **Commit**, whole under NonCancellable: the staged set is re-checked for a tear; this device's
  destination (its folder, its tick, its cloud folder) is parked outside the index; the session
  key is cleared so an extension calling into its store meets the locked library; every store and
  the index are closed; the live index and garden are renamed aside; the staged garden and index
  are renamed in, the index last as the commit marker; the proven passphrase becomes this
  device's global, acknowledged; the aside is discarded. A rename that fails renames the aside
  back and the old library is whole; when that rename back itself stops part-way, the ending says
  so and the next launch finishes it.
- **After**: the screen reopens the index itself, under whichever key is now the device's, and
  returns to Home. The parked destination is merged back over the restored row on that open, with
  both stamp maps and every last-run figure cleared: this device has never backed up this library.
- **A kill mid-commit** is settled on the next launch, before the index is looked at: the live
  index present means the commit finished and the aside is discarded; absent with the aside
  present means the swap did not complete and the aside goes back, index last. The repair stops
  at the first step that fails and tries again on the next launch, so the old index never comes
  back over a half-repaired garden; a live index whose garden is still aside is never read as a
  finished commit, and the aside is kept. While the old index still stands aside, a missing
  live index is never created fresh: the library reads as unavailable until a launch's repair
  finishes.

The restored cloud account comes back as content like any other store. Known consequence: two
devices then hold one refresh token, and a Disconnect on one revokes it for both.

## Not built

- No automatic runs, no scheduler.
- No selective restore and no merge; Import is the door for one item.
- SN's debug fault seam for the commit was not ported. The kill points are the same renames in
  the same order, and the recovery plan is the same pure rule, pinned by the same tests.

## Tests

`BackupConfigTest`, `BackupPredicatesTest`, `BackupEngineTest`, `CloudBackupRulesTest`,
`DeviceFolderTest`; `RestoreManifestTest`, `RestoreStagingTest`, `RestoreRecoveryTest`,
`RestoreDestinationTest`, `RestoreRowsTest`, `RestoreEngineTest`. Nothing that opens SQLCipher
is tested on the JVM; the swap, the proof and the prune are walked on the Nomad.
