# The shell

Soil as the device's home screen, with its own side menu in every app. How the Supernote's side
bars work, and every route that was tried, is in g-paper's `launcher-demo/README.md`.

Turning it on and off is in `building.md`.

## The parts

| Part | What it does |
|---|---|
| `HomeActivity` | The home screen. Offers itself as the device's HOME |
| `SoilBarService` | An accessibility service. Sees the side bars' keys in every app |
| `FirmwareMenu` | Holds the firmware's own side menu shut |
| `MenuOverlay` | Soil's menu, drawn over whatever is in front |
| `AppList` | Every installed app with a launcher entry |

## The home screen

Two views under one top bar, chosen with the two buttons at its start.

| View | Button | Shows |
|---|---|---|
| Library | Tabler `books` | The library. The home screen opens on it |
| App drawer | Tabler `apps` | The installed apps that are not hidden, with their icons, in fixed pages |

The view that is showing wears a border. The drawer turns its pages on a sideways swipe, the
same swipe the Scratch Pad uses, and by the pager on the bottom bar.

## Hiding apps

Many installed apps have a launcher entry and were never meant to be opened by hand. Which are
worth seeing is decided one app at a time; nothing is hidden to begin with.

The hidden apps have a drawer of their own, behind the crossed-eye button on the app drawer's top
bar. The two drawers work alike:

| | App drawer | Hidden apps |
|---|---|---|
| Tiles, in fixed pages | Yes | Yes |
| Pages turn on a swipe, and by the pager | Yes | Yes |
| Tap | Opens the app | Opens the app. Hidden is out of sight, not out of reach |
| Press and hold | Asks whether to hide it | Asks whether to show it |

- The app itself is never changed.
- The list is an ordinary preference, not part of the encrypted library, because the drawer works
  while the library is locked.
- A hidden app that is uninstalled stays on the list, so it is still hidden if it
  comes back.

## Reading the bars

The bars are keys. They carry no position and no direction. Direction comes from the firmware:
on a swipe up of the right bar it refreshes the screen and announces it.

| The right bar was held | Refresh announced | Read as |
|---|---|---|
| Under 250 ms | | A tap. Nothing happens |
| Longer | Yes | A swipe up. The firmware's refresh, left alone |
| Longer | No | A swipe down. Soil's menu opens |

The keys are watched and never consumed, so the firmware still sees every swipe.

### Over paper, the keys come through the window

The service's key filter sits in the system's input pipeline for every event, not only keys, and
with it in place a palm landing on the edge strip while the pen is down reaches Android and
cancels the pen's stream: the rest of that stroke is lost (found 2026-10-03, five pages written
on the Nomad, `docs/design.md` has the record). Without the filter the firmware's own palm
rejection keeps those touches out. So the filter follows the paper (`PaperFront`):

| In front | Key filter | How the bars reach the shell |
|---|---|---|
| The home screen, Settings, any other app | On | The filter, as above |
| The Scratch Pad | Off | Its own window hands each bar key to the service |
| A Sprout app's paper | Off | Its window hands each key to its app, which sends it over the seam (`barKey`) |

The reading is the same from either source. Keys carry the system's own time, so a held bar
measures the same. The firmware's lock is unchanged: it was shown innocent by the same test.
What a third-party app does with the pen under the filter is its own affair.

## The menu

- Home, the Scratch Pad and Settings, then a row for each Sprout app installed (Notesprout,
  Docsprout, Biblesprout, Calsprout), found by what it answers (`ACTION_OPEN_ITEM`, or
  `ACTION_OPEN_BIBLE` and `ACTION_OPEN_CALENDAR` for the apps with no items), not by name. A row
  opens the app at the item last open, or the reader and the calendar where they were left.
- The other installed apps are **not** listed. They are in the app drawer.
- A tap outside the panel closes it. The panel has no title and no close button.

## What stays the firmware's

- The refresh on a swipe up of the right bar, and its flash.
- The pull-down status bar. It has a lock of its own, which Soil never sets.
- The unlock screen at boot.

## After boot

About fifteen seconds after boot the firmware puts its Notes app over the home screen. Within
three minutes of boot, Notes arriving in front without the person having opened it is taken to
be that push, and Soil puts its home screen back.

## Without the shell

The home screen does not touch the bars or the firmware's menu. With the service off, Soil is an
ordinary app beside the firmware's menu, and everything but the side menu works.

While the service is off, the library view says "The side menu is off."

## Risks

| Risk | Consequence |
|---|---|
| The lock rests on the firmware launcher's internals | A firmware update could break it. The firmware's menu would simply open again |
| The key filter is off over paper | A Sprout paper screen that never attaches its client is under the filter and can lose a stroke to a palm on the edge |
| The Scratch Pad opens over apps that hold the e-ink panel | Not yet walked |
| The menu over Soil's own paper | The pad lets the panel go first. Not yet walked |

## Walked on the Nomad by hand, 2026-09-29

The side menu over other apps, the Scratch Pad opened from it and Back to the app underneath,
the status bar, a reboot, and the app drawer's swipe.

## Walked with the bar service off, 2026-09-29

The service was turned off and Soil stayed the home screen. The library, the app drawer, the
hidden apps, an app opened from the drawer and Home back from it, the Scratch Pad and Back from
it, and the Encryption screen all worked. The service was turned back on and reconnected.

