# Extensions

An extension is a `<service>` in its own APK that answers one of the actions in `:ext-api`,
declares the contract's version in its meta-data, and is signed with Soil's key. Soil binds it
on an app's behalf; the app never sees it, and an extension never sees an app, a file or a key.
Soil's manifest declares a `<queries>` entry for each action, without which another package's
service is invisible.

| Point | Action | Extension |
|---|---|---|
| Recogniser | `ext.RECOGNIZER` | `:ext-mlkit` (ML Kit digital ink, English) |
| Exporter, importer | `ext.EXPORTER`, `ext.IMPORTER` | `:ext-soilfile`, `:ext-pdf`, `:ext-image` (`export.md`) |
| Cloud storage | `ext.CLOUD_STORAGE`, `ext.CLOUD_SCREEN` | `:ext-cloud` (Google Drive; `cloud.md`) |

## How Soil calls one

`Extensions.find` lists the installed services of a point, kept when exported, at the
contract's version, signed with Soil's key and of Soil's build; looked for each time, never
remembered. `ExtensionBinder` binds, re-checks the signature at the bind, waits a bounded time,
runs each call on a thread of its own under a timeout, and unbinds on close: one bind per call,
or held across a per-page loop, never on Main. The extension's own gate, `HostCallerCheck`,
refuses any caller but Soil.

Descriptors and replies validate in their constructors, so a malformed answer fails at unmarshal
and never reaches a screen. An extension that writes nothing to disk itself is lent a store
(`IExtStore`, `cloud.md`).

## Recognition

`IRecognizer` is asked in a language tag. Soil relays the app's four calls over the seam to the
recogniser chosen in Settings, the lone installed one by default: the status, a prepare that
starts the model's download, the recognition of one writing area with its size and a
pre-context, and of a page. The consent and download flow lives in `:paper`, in Soil's words,
and the app shows it at the first H, Make text or Tag on ink. Nothing recognised is ever logged.

## Settings

Reached from the side menu: the recogniser and its language, the paper library, the cloud
account, Encryption. Rows are built in code, each a label over its current answer.

Walked on the Nomad, phase 10, 2026-10-03.
