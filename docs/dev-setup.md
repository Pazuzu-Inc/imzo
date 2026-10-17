# Developer setup (macOS, Intel)

Everything below was run on this Mac (x86_64, Darwin 24). Two things are installed with Homebrew-level convenience, two are manual downloads kept inside `~/dev`, nothing else is system-wide:

| Tool | Location | How it was installed |
| --- | --- | --- |
| Android command-line tools (`sdkmanager`) | `/usr/local/share/android-commandlinetools`, symlinked as `/usr/local/bin/sdkmanager` | `brew install --cask android-commandlinetools` (the cask creates the symlink). This is the only system-wide install. |
| JDK 17 (Temurin 17.0.20.1, x64) | `~/dev/jdk-17` (the JDK home is `~/dev/jdk-17/Contents/Home`) | **Manual download**, see below. |
| Gradle 8.9 | `~/dev/gradle-8.9` | **Manual download**, see below. Only used to generate the wrapper; the project builds with `./gradlew`, which is pinned to 8.9 and verifies its own download with `distributionSha256Sum`. |
| Android SDK | `~/Library/Android/sdk` | `sdkmanager`, see below. |

## Manual downloads into `~/dev`

Downloaded files are kept in `~/dev/dl`. Always check the checksum before unpacking.

```bash
mkdir -p ~/dev/dl && cd ~/dev/dl

# JDK 17: Temurin 17.0.20.1 (the Adoptium release, https://adoptium.net/temurin/releases/?version=17, is hosted on GitHub)
curl -fL -O 'https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.20.1%2B1/OpenJDK17U-jdk_x64_mac_hotspot_17.0.20.1_1.tar.gz'
echo 'c01975da12ed4235250ff891fe8bba73a9e73037d444b269c9d0922b5dbc8e0a  OpenJDK17U-jdk_x64_mac_hotspot_17.0.20.1_1.tar.gz' | shasum -a 256 -c -
mkdir -p ~/dev/jdk-17 && tar -xzf OpenJDK17U-jdk_x64_mac_hotspot_17.0.20.1_1.tar.gz -C ~/dev/jdk-17 --strip-components=1
~/dev/jdk-17/Contents/Home/bin/java -version        # openjdk 17.0.20.1

# Gradle 8.9: the checksum comes from Gradle itself, not from the zip we are checking
curl -fL -O https://services.gradle.org/distributions/gradle-8.9-bin.zip
curl -fsSL https://services.gradle.org/distributions/gradle-8.9-bin.zip.sha256 > gradle-8.9-bin.zip.sha256
echo "$(cat gradle-8.9-bin.zip.sha256)  gradle-8.9-bin.zip" | shasum -a 256 -c -    # d725d707...ecab
unzip -q gradle-8.9-bin.zip -d ~/dev && ls ~/dev/gradle-8.9/bin/gradle
```

Resulting layout:

```text
~/dev/
  android-env.sh          the environment file (below)
  dl/                     the downloads
  jdk-17/Contents/Home    JAVA_HOME
  gradle-8.9/             Gradle (bin/gradle)
~/Library/Android/sdk/    ANDROID_HOME
```

## Android SDK

```bash
brew install --cask android-commandlinetools
sdkmanager --sdk_root=$HOME/Library/Android/sdk "platform-tools" "platforms;android-35" "build-tools;35.0.0"
sdkmanager --sdk_root=$HOME/Library/Android/sdk --licenses     # accept the licences
```

`sdkmanager` needs a JDK: load the environment file below first (or set `JAVA_HOME` for that command).

## Environment

[`docs/android-env.sh.example`](android-env.sh.example) is a copy of the file used here. Install it and load it in every new shell:

```bash
cp docs/android-env.sh.example ~/dev/android-env.sh      # once, from the repo root
source ~/dev/android-env.sh                              # in every new shell
```

It sets `JAVA_HOME` to the JDK 17, `ANDROID_HOME` (and `ANDROID_SDK_ROOT`) to the SDK folder, and puts the JDK, `platform-tools` (adb), `build-tools/35.0.0` (aapt2) and Gradle on the `PATH`.

The Android Gradle plugin takes the SDK path from `sdk.dir` in `android/local.properties` and only falls back to the `ANDROID_HOME` environment variable. Write the file so builds do not depend on which shell started them (it is machine-specific and git-ignored). From the repo root, after sourcing the environment file:

```bash
echo "sdk.dir=$ANDROID_HOME" > android/local.properties
```

## Build the Android app

```bash
source ~/dev/android-env.sh
cd android
./gradlew :app:assembleDebug        # APK at app/build/outputs/apk/debug/app-debug.apk
```

The first build downloads dependencies from Google Maven and Maven Central; later builds work with `--offline`. If the Gradle daemon misbehaves, add `--no-daemon`.

Check the merged APK permissions (INTERNET must never appear). The build already runs this guard for both debug and release APKs (`checkPermissionsDebug`, `checkPermissionsRelease`); to look by hand:

```bash
aapt2 dump permissions app/build/outputs/apk/debug/app-debug.apk
```

### Release build for the screenshot check (demo signing)

The release APK is built unsigned (there is deliberately no signing config in Gradle), so it cannot be installed as it is. To check the release-only behaviour (`FLAG_SECURE`: no screenshots, no recents thumbnail) on a phone, sign it with a throwaway demo key:

```bash
cd android
./gradlew :app:assembleRelease --offline
scripts/sign_release_for_demo.sh     # asks for the keystore password without echo
```

The script creates `~/dev/imzo-demo.keystore` on first use (outside the repo; password typed at the prompt without echo, never printed; the script refuses a keystore path inside the repo; `IMZO_KS_PASS` also works for non-interactive use, but prefer the prompt because a variable set on the command line ends up in the shell history), runs `zipalign` and `apksigner` from the newest build-tools, writes `app/build/outputs/apk/release/app-release-demo.apk` and runs `check_permissions.sh` on it. Install that file with `adb install -r`; if a debug build is on the phone, uninstall it first (different signature), which wipes the app's key and counter. This is a demo key, not a release process.

## Tests

Two different things are called `protocol`: the **Python reference** in `protocol/` at the repo root, and the **Kotlin Gradle module** `android/protocol`. The Python commands below run from the repo root's `protocol/` folder, the Gradle ones from `android/`.

Kotlin and app unit tests (JVM, no phone needed):

```bash
source ~/dev/android-env.sh
cd android
./gradlew :protocol:test :app:testDebugUnitTest --offline
./gradlew :app:assembleDebugAndroidTest --offline    # compiles the on-device tests; running them needs a phone
```

`:protocol:test` also writes `android/protocol/build/cross/*.json`, the answers the Kotlin approver produced; the Python cross-check reads them.

Python reference and backend (from the repo root; the first line recreates the virtual environment if it is missing):

```bash
python3 -m venv .venv && .venv/bin/pip install cryptography    # only if .venv/ does not exist
(cd protocol && ../.venv/bin/python -m unittest)               # reference, vectors, Kotlin cross-check
(cd protocol && IMZO_REQUIRE_CROSS=1 ../.venv/bin/python -m unittest)   # same, but fails instead of skipping if the Kotlin output is missing
.venv/bin/python -m unittest discover -s backend               # HTTP backend
node --test "web/test/*.test.mjs"                              # initiator page logic
(cd protocol && ../.venv/bin/python demo.py)                   # the stage story in the terminal
```

The test counts change as tests are added; trust the `OK` at the end rather than a number in a document.

## Run the demo on this Mac

```bash
.venv/bin/python backend/app.py        # from the repo root; serves the page and the API on 127.0.0.1:8080
```

Open <http://127.0.0.1:8080/> in Chrome. The backend only answers requests addressed to `127.0.0.1:<port>` or `localhost:<port>`; see [`demo-runbook.md`](demo-runbook.md) for the full stage procedure.

## Run on a phone

The phone must run Android 11 (API 30) or newer.

1. Enable Developer options and USB debugging (see the Xiaomi notes below for MIUI).
2. Connect the phone by USB, set the USB mode to File transfer, and accept the "Allow USB debugging?" prompt on the phone (tick "Always allow from this computer").
3. Check it is visible:

```bash
source ~/dev/android-env.sh
adb devices                                   # the device must be listed as "device", not "unauthorized" or "offline"
adb shell getprop ro.build.version.release    # Android version, must be 11 or higher
```

4. Install and launch (`installDebug` first runs the permission guard on the APK it installs):

```bash
cd android
./gradlew :app:assembleDebug :app:installDebug
adb shell am start -n inc.pazuzu.imzo/.MainActivity
```

Expected: the app shows the home screen with the device id; "Scan order" asks for the camera permission and then shows a live camera preview.

### Xiaomi / MIUI notes

Xiaomi phones hide a few switches that plain Android does not have, and `installDebug` fails with `INSTALL_FAILED_USER_RESTRICTED` or the app never starts until they are on.

1. Settings, About phone, tap the **MIUI version** line seven times (not "Android version"). Developer options are now unlocked.
2. Settings, **Additional settings**, Developer options. Turn on all three:
   - **USB debugging**
   - **Install via USB**
   - **USB debugging (Security settings)**
3. Set the USB mode to **File transfer** (pull down the notification shade, tap the USB notification). "Charging only" often leaves `adb` at `unauthorized` or `offline`.
4. Unlock the phone and accept the RSA key prompt ("Allow USB debugging?"). If it never appears: unplug, Developer options, "Revoke USB debugging authorisations", plug in again.
5. `adb devices` must show `device`. Then `./gradlew :app:installDebug`. If MIUI shows an install confirmation on the phone, accept it.
