# Building and installing

## Build

```
./gradlew test                          # every JVM test
./gradlew assembleDebug assembleRelease # both builds
```

| Build | Installs as | APK |
|---|---|---|
| Debug | `com.symmetricalpalmtree.soil.dev` | `soil/build/outputs/apk/debug/soil-debug.apk` |
| Release | `com.symmetricalpalmtree.soil` | `soil/build/outputs/apk/release/soil-release.apk` |

Both are signed with `~/.android/debug.keystore`. The seam trusts exactly one signing key, so
every Sprout app must be signed with the same one.

Needs: Temurin 17, the Android SDK (platform 35), and g-paper, at the version pinned in
`paper/build.gradle.kts`, in the local Maven repository, published from `~/git/g-paper`.

## Install

Install only on the device asked for. The Nomad is the default. The Manta identifies as a Nomad,
so a device is always named by its serial.

| Device | Serial |
|---|---|
| Nomad | `SN078D10012852` |
| Manta | `SN100C10023972` |

```
adb -s SN078D10012852 install -r soil/build/outputs/apk/debug/soil-debug.apk
```

Installed like this, Soil is an ordinary app: open it from the firmware's side menu. It has the
library, the Scratch Pad and the Encryption screen. It does not have the side bars.

## The shell

The shell is two settings, both made over adb. The commands below are for the debug build; for
the release build drop `.dev` from the package.

### See what the device has now

Do this first, and keep the answers: they are what "back" means for this device.

```
adb -s SN078D10012852 shell cmd package resolve-activity -c android.intent.category.HOME -a android.intent.action.MAIN | grep name
adb -s SN078D10012852 shell settings get secure enabled_accessibility_services
```

### Turn it on

```
adb -s SN078D10012852 shell pm set-home-activity com.symmetricalpalmtree.soil.dev/com.symmetricalpalmtree.soil.home.HomeActivity
adb -s SN078D10012852 shell settings put secure enabled_accessibility_services com.symmetricalpalmtree.soil.dev/com.symmetricalpalmtree.soil.shell.SoilBarService
adb -s SN078D10012852 shell settings put secure accessibility_enabled 1
adb -s SN078D10012852 logcat -s SoilBars FirmwareMenu MenuOverlay
```

Both settings survive a reboot.

### Turn it off, back to the stock device

```
adb -s SN078D10012852 shell settings put secure enabled_accessibility_services ""
adb -s SN078D10012852 shell pm set-home-activity com.ratta.supernote.background/.MainActivity
```

The firmware's side menu works again at once. Soil stays installed as an ordinary app.

### Back to the launcher demo

The Nomad ran g-paper's launcher demo as its shell before Soil.

```
adb -s SN078D10012852 shell pm set-home-activity com.symmetricalpalmtree.gpaper.launcherdemo/.HomeActivity
adb -s SN078D10012852 shell settings put secure enabled_accessibility_services com.symmetricalpalmtree.gpaper.launcherdemo/.BarService
adb -s SN078D10012852 shell settings put secure accessibility_enabled 1
```

## Where Soil keeps its files

In the app's external files folder, where adb can reach them on any build. Every one is
encrypted.

```
/sdcard/Android/data/<package>/files/soil.db                 the library index
/sdcard/Android/data/<package>/files/garden/scratchpad.db    the Scratch Pad
/sdcard/Android/data/<package>/files/garden/<id>.soil        an item (none yet)
```

**Never `adb push` into this folder: it deletes the target.** Push to `/data/local/tmp` and copy
from there. `adb pull` is safe.

## What cannot be tested over adb

- **Ink.** It does not show in a screenshot, and adb cannot inject a pen.
- **The side bars.** `adb shell input keyevent` never reaches the bar service. Only a real swipe
  on the bar tests the menu.
- **Typing.** `adb shell input text` is swallowed by the Supernote's keyboard. Tap the keys, or
  use Copy and Paste.

## The Sprout apps and the extensions

Install Soil first: it declares the seam permission the others use. Then the Sprout apps and the
extensions, debug with debug (`.dev` talks to `.dev`):

```
adb -s SN078D10012852 install -r soil/build/outputs/apk/debug/soil-debug.apk
adb -s SN078D10012852 install -r notesprout/build/outputs/apk/debug/notesprout-debug.apk
adb -s SN078D10012852 install -r docsprout/build/outputs/apk/debug/docsprout-debug.apk
adb -s SN078D10012852 install -r ext-mlkit/build/outputs/apk/debug/ext-mlkit-debug.apk
adb -s SN078D10012852 install -r ext-soilfile/build/outputs/apk/debug/ext-soilfile-debug.apk
adb -s SN078D10012852 install -r ext-pdf/build/outputs/apk/debug/ext-pdf-debug.apk
adb -s SN078D10012852 install -r ext-image/build/outputs/apk/debug/ext-image-debug.apk
adb -s SN078D10012852 install -r ext-cloud/build/outputs/apk/debug/ext-cloud-debug.apk
```

`:ext-cloud` compiles the Google OAuth client from `DRIVE_CLIENT_ID` and `DRIVE_CLIENT_SECRET`
in the shell (`~/.zshenv` on this Mac). Built without them it installs and reports "not set up".

An install closes whatever of that app is in front, and after a Soil install a stale system
screen may come forward: press Home.

## Docsprout's self-test

The rendered editor's rules need a real `Editable`, which the JVM does not have, so the debug
build carries a screen that runs them on the device and writes the result to logcat:

```
adb -s SN078D10012852 shell am start -n com.symmetricalpalmtree.soil.docsprout.dev/com.symmetricalpalmtree.soil.docsprout.selftest.SelfTestActivity
adb -s SN078D10012852 logcat -d -s DocsproutSelfTest
```

It opens no document and writes nothing to the library. `--ez pdf true` also writes export
probes into the app's cache. Run it with Soil's Home or Docsprout in front, and press Back
after.

The Supernote's file picker does not answer taps injected over adb on its rows, so an import is
walked by hand: push the file, and have it picked on the device.
