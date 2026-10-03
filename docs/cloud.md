# Cloud storage

A second place for exports to go and imports to come from: an account at a cloud provider,
reached through one extension. Soil has no INTERNET permission and never sees a token; the
extension has no storage of its own and keeps what it wins in a store Soil lends it.

## The parts

| Part | Where | What it does |
|---|---|---|
| The cloud point | `:ext-api` | `ICloudStorage` (status, disconnect, beginConnect, endConnect, list, ensureFolder, upload, download, delete), `CloudEntry`, `CloudStatus`, `CloudContract` |
| The lent store | `:ext-api` | `IExtStore`: `exec` and `query` over the seam's row encoding, the only way an extension persists anything |
| `CloudStoreLease` | `:soil` | An `IExtStore` over one of Soil's app stores (`ext_<package>` in the garden), minted for one lending, bound to the extension's uid, dead once revoked |
| `CloudClient` | `:soil` | One bind, one call, one unbind per operation; the store leased on IO before the bind; budgets from `CloudTimeouts` |
| `CloudConnectClient` / `CloudConnectEntry` | `:soil` | The connect showing: a held bind with `beginConnect`/`endConnect` around the extension's sign-in screen |
| `CloudBrowserDialog` | `:soil` | Soil's own folder and file list over the provider's tree, paginated; picks a folder (export) or a file (import) |
| `ExportDestination`, `ImportSource`, `CloudImportRules` | `:soil` | The pure rules: when the Destination row shows, what a tap on the cloud radio does, when the import tap asks, how a download is corroborated |
| `:ext-cloud` | extension | Google Drive: the OAuth sign-in in a WebView (PKCE, `drive.file` scope), the REST v3 calls, the token in the lent store. The only module with INTERNET |

## The store Soil lends

Every call on the point carries an `IExtStore`. Soil opens the store before the bind (a cold
open is a key derivation, seconds on the Nomad, and never sits inside a call budget), wraps it
in a lease for the extension's uid, hands it to the call and revokes it in `finally`. The store
is an ordinary app store under the global key: one table, `account(key, value)`, made by Soil
from `CloudContract.STORE_CREATE`; it is re-keyed with everything else by a rotation. Every
statement the extension sends passes the seam's checker; an extension cannot send DDL.

The connect showing is the one place the store outlives a call. The sign-in is the extension's
screen, so it has to write the token itself, after Soil has handed the bind back to the window
manager: Soil leases the store, holds the bind, calls `beginConnect(store)`, launches the screen
for a result with nothing on the Intent, and on the result calls `endConnect`, unbinds and
revokes. The screen answers `RESULT_OK` only once the token is in the store; Soil learns nothing
from the result and re-reads `status`, which never touches the network.

## What the extension is given, and never

Folder names, an opaque entry id, a MIME type and file descriptors cross. No device path, no
URL, no secret, in either direction. The account's label comes back inside `CloudStatus` and is
shown on the person's own screen only; no log line on either side carries it. Soil's log lines
carry booleans, counts and durations.

The extension's OAuth client id and secret are compiled into its APK from two shell variables
at build time, `DRIVE_CLIENT_ID` and `DRIVE_CLIENT_SECRET` (the same client Notesprout SN uses,
decision 2026-10-03). A build without them reports `configured = false`, and Soil says "not set
up in this build" instead of opening a sign-in that cannot work.

## The tree

The root folder is `Soil` on a release build and `Soil Dev` on a debug one, so a dev build never
mingles its test files under the real tree. Exports go under `Exports/`; the export browser
opens there and Up stops there. Backups (phase 13) will go under `Backups/`. The import browser
opens at the root, so both are one tap away, and filters nothing by extension: which importer
reads a file is decided afterwards, by its name, exactly as for a picked document.

## Export to the cloud

With a provider installed the export screen grows a Destination row: this device, or the
provider by the name it gives for itself. The row is GONE without a provider, never disabled,
and a standing cloud answer falls back to local whenever the row leaves the screen. A tap on
the cloud radio with no account connected offers Connect; a build without credentials says so.

On the cloud leg the exporter writes into a file in Soil's cache, verified as on the local leg,
and that file is uploaded. An upload replaces by name, so a folder already holding the name
gets a *Replace?* question first, the stand-in for the picker's overwrite confirmation. The
provider's reported size is corroboration: a disagreement says "check the file" and deletes
nothing. A failure before the upload says "Nothing was uploaded"; a provider that did not answer
is told as "may or may not have arrived". Per-page exports confirm the folder once ("files with
the same names will be replaced"), then upload one file per page; a failure stops and says how
many went.

## Import from the cloud

With a provider installed the Import button first asks *Import from*: this device, or the
provider. The browser's file pick is downloaded into the import cache after an importer has
accepted its name, so a file no importer reads costs no bytes. What the provider says it wrote,
what landed and what the listing claimed are corroborated by `CloudImportRules.downloadVerdict`;
the rest is the ordinary import. No account connected is the one failure that offers Connect.

## Settings

Settings has a *Cloud storage* row: the provider's status line (`<provider>: not connected`,
`<provider>: <account>`, `not configured`, `unavailable`), or "No cloud extension is installed".
A tap connects, or on a connected account asks before disconnecting. Disconnect revokes the
token with the provider (best effort) and forgets it locally either way.

## Budgets

A Binder call cannot be cancelled, so a budget that runs out leaves the provider still working
while Soil has already spoken: a timeout undoes nothing, and nothing is retried on its own.
Every operation is replace-by-name or idempotent, so "try again" is safe to offer. The numbers
in `CloudTimeouts` carry their Nomad measurements from Notesprout SN; each is 5–30× its
measurement. Uploads over 5 MiB are budgeted per 20 MiB slice.

## Not in this phase

- A second provider. The point is generic; the extension is Google Drive.
- Remembering a chosen cloud folder across exports (that was the presets' folder row; presets
  are in `BACKLOG.md`).
- Swipe between the browser's pages; the pager buttons flip them.
