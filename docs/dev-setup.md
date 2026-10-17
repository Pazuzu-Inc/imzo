# Developer setup (macOS, Intel)

Everything below was run on this Mac (x86_64, Darwin 24). Nothing is installed system-wide except the `sdkmanager` launcher (`/usr/local/bin/sdkmanager`, Android command-line tools via Homebrew).

## What is installed where

| Tool | Location | How it was installed |
| --- | --- | --- |
| JDK 17 (Temurin 17.0.20.1, x64) | `~/dev/jdk-17` | Tarball downloaded from the Adoptium GitHub releases, SHA-256 checked, extracted into `~/dev`. |
| Gradle 8.9 | `~/dev/gradle-8.9` | `gradle-8.9-bin.zip` from services.gradle.org, SHA-256 checked, unzipped into `~/dev`. Only used to generate the wrapper; the project builds with `./gradlew` (pinned to 8.9). |
| Android SDK | `~/Library/Android/sdk` | `sdkmanager --sdk_root=$HOME/Library/Android/sdk "platform-tools" "platforms;android-35" "build-tools;35.0.0"`, then `sdkmanager --sdk_root=$HOME/Library/Android/sdk --licenses`. |

Downloads are kept in `~/dev/dl`. The environment is loaded by `~/dev/android-env.sh`:

```bash
export JAVA_HOME=$HOME/dev/jdk-17/Contents/Home
export ANDROID_HOME=$HOME/Library/Android/sdk
export ANDROID_SDK_ROOT=$ANDROID_HOME
export PATH=$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/build-tools/35.0.0:$HOME/dev/gradle-8.9/bin:$PATH
```

Load it in every new shell:

```bash
source ~/dev/android-env.sh
```

`android/local.properties` (git-ignored) must contain the SDK path; Gradle does not read `ANDROID_HOME` from there:

```properties
sdk.dir=/Users/lukaaptsiauri/Library/Android/sdk
```

## Build the Android app

```bash
source ~/dev/android-env.sh
cd android
./gradlew :app:assembleDebug        # APK at app/build/outputs/apk/debug/app-debug.apk
```

The first build downloads dependencies from Google Maven and Maven Central. If the Gradle daemon misbehaves, add `--no-daemon`.

Check the merged APK permissions (INTERNET must never appear):

```bash
aapt2 dump permissions app/build/outputs/apk/debug/app-debug.apk
```

## Unit tests

Kotlin (JVM, no phone needed):

```bash
source ~/dev/android-env.sh
cd android
./gradlew :protocol:test
```

Python reference implementation (from the repo README):

```bash
cd protocol
../.venv/bin/python -m unittest -v     # 31 tests
../.venv/bin/python demo.py            # the stage story in the terminal
../.venv/bin/python imzo_ref.py > vectors.json
```

To recreate the Python environment: `python3 -m venv .venv && .venv/bin/pip install cryptography`.

## Run on a phone

The phone must run Android 11 (API 30) or newer.

1. On the phone: Settings, About phone, tap "Build number" seven times to enable Developer options.
2. Settings, System, Developer options, turn on "USB debugging".
3. Connect the phone by USB and accept the "Allow USB debugging?" prompt on the phone.
4. Check it is visible:

```bash
source ~/dev/android-env.sh
adb devices                              # expect the device listed as "device", not "unauthorized"
adb shell getprop ro.build.version.release   # Android version, must be 11 or higher
```

5. Install and launch:

```bash
cd android
./gradlew :app:installDebug
adb shell am start -n inc.pazuzu.imzo/.MainActivity
```

Expected: the app asks for the camera permission and then shows a live camera preview.
