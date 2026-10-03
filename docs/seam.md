# The seam

The interface between Soil and the Sprout apps. It has one call for now.

## The rules

- An app never touches a file. It asks Soil for rows and hands rows back.
- An app never sees a key, and never asks for one. When the library is locked, the person
  unlocks it in Soil.
- Trust rests on one signing key. There is no per-app permission model.

## The call

```
SeamHello hello()
```

| Field | Meaning |
|---|---|
| `seamVersion` | The version of the seam this Soil speaks |
| `libraryUnlocked` | Whether Soil holds the key right now |

### Export

`ACTION_EXPORT` opens Soil's export screen for one of the app's items (`EXTRA_ITEM_ID`,
`EXTRA_PAGE_ID` to offer that page as a scope, `EXTRA_RETURN_TO_APP` when the app closed the
item first). `ACTION_RENDER` is the app's side: a `<service>` guarded by Soil's permission,
`META_KIND` naming the kind, answering `IItemRenderer` (pages, render into a descriptor,
relabel statements). `docs/export.md` has the whole.

### The side bars

`barKey(keyCode, action, eventTime, repeatCount)`: a bar key the app's paper screen received in
its window, sent as it came. Soil's shell turns its own system-wide key filter off while an app's
paper is in front (the filter let a resting palm cut the pen's stream; `shell.md`), so this is
how a swipe reaches Soil's menu there. The app consumes nothing; `PaperScreenActivity` in
`:paper` already forwards, so a Sprout app only overrides `onBarKey`.

## The version

`Seam.VERSION` is 1. It does not change during development. It is frozen at the first release
build that is actually put to use, and goes up from there.

## The two guards

| Guard | Where | What it stops |
|---|---|---|
| A signature permission on the service | Android, at the bind | Any app not signed with Soil's key, before any of Soil's code runs |
| `SeamCallerCheck.enforce` | Soil, first thing in every call | The same, checked again at the moment of the call |

The permission is named after the install,
`<package>.permission.SEAM`, so a debug Soil and a release Soil never declare the same name. An
app says which Soil it talks to when it is built.

## For an app

```xml
<uses-permission android:name="com.symmetricalpalmtree.soil.permission.SEAM" />
<queries>
    <package android:name="com.symmetricalpalmtree.soil" />
</queries>
```

```kotlin
val intent = Intent().setClassName(soilPackage, Seam.SERVICE_CLASS)
bindService(intent, connection, Context.BIND_AUTO_CREATE)
```

Depend on `:seam`. Install Soil first: it declares the permission.

## Walked on the Nomad, 2026-09-28

| Caller | Result |
|---|---|
| Signed with Soil's key, library not yet open | Answered: version 1, unlocked false |
| Signed with Soil's key, library open | Answered: version 1, unlocked true |
| Signed with another key | Refused at the bind. Android did not grant it the permission |

The check is `:seam-stranger`; see its README.

## Open

The stranger never reaches the second guard, because the first stops it. The second is covered
by a JVM test of its decision and by reading. Whether it needs a walk of its own is undecided.
