# Files on this device

Soil's own browser over the device's shared storage, where the Android picker was (Greg,
2026-10-10, the cleanup round). The Android picker has no Cancel of Soil's and no back button
on a Supernote, and strands a person in it; the Google Drive browser already had the shape
wanted, so the device got the same screen.

## The permission

Since Android 10 an app cannot read or write the shared storage (`Documents`, `Note`, `EXPORT`,
`Download`) by path; the Android picker is the sanctioned door, and that is the only reason
Soil went through it. Android 11 adds **All files access**, a special permission
(`MANAGE_EXTERNAL_STORAGE`, declared in the manifest) the person switches on once, on a system
screen. With it Soil reads and writes the shared storage as files. Whether it is on is asked at
every use (`LocalStorage.hasAccess`), never cached: the person can switch it off at any time,
and the Android picker stands in again. Nothing regresses while it is off.

**The first pick** with the access off offers it once (`LocalFilePick.gate`): *Browse files with
Sproutscape?*, with *Open Settings* (the system screen for this app's toggle; the list of every
app when that one is missing), *Use Android's picker* (remembered, `SettingsPrefs.localPickerDeclined`;
the Android picker from then on, silently) and Cancel. The Settings row **Files on this device**
says which way it stands and opens the system screen; tapping it forgets a decline.

**The restart.** A process started before the access was granted keeps the narrow view of the
storage it was born with, and Android does not restart Soil on the toggle (walked on the Nomad,
2026-10-10: `isExternalStorageManager()` true, the root listing empty). So when the toggle was
opened from this process and the access is seen on the way back, at the next pick or on the
Settings screen, Soil says *All files access is on. Sproutscape restarts to take it* and ends its
own process (`LocalStorage.settleAfterGrant`). A plain process death, not a force-stop: Home comes
back on its own and the side menu's service rebinds (walked). A toggle switched on from the
system Settings directly, with no pick of Soil's in between, is taken at the next launch.

## The browser

`CloudBrowserDialog` lists what a `BrowserSource` answers: `CloudSource` through the extension,
`LocalSource` through plain files under `Environment.getExternalStorageDirectory()`. The crumb
starts at *This device*; Up stops there; Cancel is the way out of the root. File mode answers a
file, folder mode has *Save here* and *New folder…*, the pager flips. An entry's id is a handle
of the source's, never the path (a path may hold a space, which an entry id may not);
`LocalSource.fileFor` answers the file. Dotfiles and `Android/` are not shown; folders come
first, then files, by name without regard to case (`LocalFiles`, tested). In file mode a filter
narrows the files by extension: the any-type or none shows every file, a family (image) its
extensions, an exact type the extensions known for it, and an extension nobody knows is shown
rather than hidden, the caller judging the bytes. A name the entry type refuses (edge
whitespace, a control character) is left off the list.

## Where it stands

Every door that opened the Android picker, each through `LocalFilePick`, with the Android picker
behind it while the access is off:

- **Import** (the library's Import button): every file shown; the pick is read as a `file:` Uri
  through the same importer path as a picked document.
- **The template library's Import** (images) **and Export** (folder mode, opened on the folder
  templates last went to, `lastLocalFolder:template`; *Replace?* when the name is there).
- **The file-pick screen** a Sprout app starts (`ACTION_PICK_FILE`, `cloud.md`): the asked types
  narrow the list; the pick is copied into the cache and answered as before.
- **Export, this device**: a **Folder** row under the local radio (shown even with no cloud
  extension, since the choice row is the cloud's), the whole path, remembered per kind of item
  (`lastLocalFolder:<kind>`), the root until picked; a tap opens the browser there and *Save
  here* only sets the row. Export writes straight into it: one file named, *Replace?* first
  when the name is there, or one file per page. Partial files are removed on a failure as the
  SAF ones were.
- **The backup folder**: *Choose…* opens the browser in folder mode on the folder as it stands.
  The folder is kept by path (`BackupConfig.localDir`), exactly one of it and the SAF tree
  standing; adopting one releases the other (a SAF grant is released), and a different folder
  resets the stamp map as before. The engine writes through `FileBackupWriter`, the SAF writer's
  twin behind one `BackupWriter`: the same atomic protocol (`.part`, `.old`, rename in), by
  `File`. The screen shows the path as a crumb, *This device › Documents › Soil*.
- **Restore, from a folder**: the browser in folder mode; the folder is read through
  `FileBackupReader` behind `BackupReader`, one level deep as the SAF reader is, so a debug
  build's `dev/` is found.

## Walked on the Nomad (2026-10-10)

The offer, the system screen (Ratta's firmware has it), the toggle, the restart, the root
listing (35 pages of it), the template import from `EXPORT/` with the image filter, an export
into `Documents` through the Folder row, the backup folder set to `Documents › Soil` by path
with a full run into `dev/`, and the restore's listing of that `dev/` backup from the path.

## Not built

- The Android picker's own memory of where it was: the Folder rows are Soil's memory instead.
- A second root (an SD card): the Nomad has none; `LocalStorage.root()` is the one volume.
- Hiding the Android picker for good: it stays behind the access, so a person who never
  switches the access on loses nothing.
