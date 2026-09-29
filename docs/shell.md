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

The view that is showing wears a border. The drawer's pager is on the bottom bar.

## Hiding apps

Many installed apps have a launcher entry and were never meant to be opened by hand. Which are
worth seeing is decided one app at a time; nothing is hidden to begin with.

- **Hide:** press and hold an app in the drawer. It asks first.
- **Show again:** the hidden apps screen, behind the crossed-eye button on the drawer's top bar.
- A hidden app leaves the drawer and the side menu alike. The app itself is not changed.
- The list is an ordinary preference, not part of the encrypted library, because the drawer and
  the menu work while the library is locked.
- *Proposed:* a hidden app that is uninstalled stays on the list, so it is still hidden if it
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

## The menu

- Home and the Scratch Pad, then every installed app that is not hidden, by name, each with
  its own icon.
- Fixed pages with previous and next. Nothing scrolls.
- A tap outside the panel, or on the cross, closes it.

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

*Proposed:* while the service is off, the library view says "The side menu is off."

## Risks

| Risk | Consequence |
|---|---|
| The lock rests on the firmware launcher's internals | A firmware update could break it. The firmware's menu would simply open again |
| The Scratch Pad opens over apps that hold the e-ink panel | Not yet walked |
| The menu over Soil's own paper | The pad lets the panel go first. Not yet walked |

## Still to walk, by hand

Only a real swipe on the bar tests any of this.

- The menu over the firmware's Notes, over Notesprout SN, over the Scratch Pad.
- The Scratch Pad opened from the menu over each; Back returns to the app underneath.
- With the library locked, the Scratch Pad row leads to unlock.
- The pull-down status bar still opens.
- A reboot: Soil is home, and takes it back after the firmware's push.
