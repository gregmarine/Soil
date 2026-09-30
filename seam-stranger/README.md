# :seam-stranger

The check of the seam's two guards. One small app, built twice:

| Build | Signed with | Must be |
|---|---|---|
| `friend` | The key Soil is signed with | Answered: the seam's version, and whether the library is unlocked |
| `stranger` | A key of its own | Refused at the bind, before any of Soil's code runs |

## The stranger's key

The stranger needs a key that is not Soil's. It is made once on this machine and never committed
(`*.keystore` is ignored). Until it exists this module is left out of the build, so a fresh clone
still builds.

```
keytool -genkeypair -keystore seam-stranger/stranger.keystore -alias stranger \
  -storepass stranger -keypass stranger -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=Soil seam stranger"
```

## Running it

Soil is installed first: it declares the permission.

```
./gradlew :seam-stranger:assembleFriendDebug :seam-stranger:assembleStrangerDebug
adb -s SN078D10012852 install -r seam-stranger/build/outputs/apk/friend/debug/seam-stranger-friend-debug.apk
adb -s SN078D10012852 install -r seam-stranger/build/outputs/apk/stranger/debug/seam-stranger-stranger-debug.apk
adb -s SN078D10012852 logcat -s SeamCheck SeamCallerCheck
adb -s SN078D10012852 shell am start -n com.symmetricalpalmtree.soil.seamcheck.friend/com.symmetricalpalmtree.soil.stranger.SeamCheckActivity
adb -s SN078D10012852 shell am start -n com.symmetricalpalmtree.soil.seamcheck.stranger/com.symmetricalpalmtree.soil.stranger.SeamCheckActivity
```

Both ask the debug Soil, `com.symmetricalpalmtree.soil.dev`. To ask another, build with
`-PsoilPackage=com.symmetricalpalmtree.soil`.

Remove them afterwards:

```
adb -s SN078D10012852 uninstall com.symmetricalpalmtree.soil.seamcheck.friend
adb -s SN078D10012852 uninstall com.symmetricalpalmtree.soil.seamcheck.stranger
```
