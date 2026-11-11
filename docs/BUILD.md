# Build instructions

## 1. Requirements

| Thing | Version | Notes |
| --- | --- | --- |
| JDK | **17** (Temurin recommended) | AGP 8.7 rejects JDK 8/11; JDK 21 works but 17 is what CI uses |
| Android SDK Platform | `android-35` | `compileSdk = 35`, `targetSdk = 35` |
| Build-tools | `35.0.0` | CI uses it for `aapt dump badging`; any recent one works locally |
| Gradle | **8.11.1**, via the wrapper | do not install Gradle; run `./gradlew` |
| AGP / Kotlin | 8.7.3 / 2.0.21 | pinned in `gradle/libs.versions.toml` |
| Compose BOM | 2024.12.01 | Material 3 and the two glyphs the shell needs come from it |
| minSdk | 26 (Android 8.0) | adaptive icons, `onRenderProcessGone` and the back callback are all assumed available |

Nothing else. No plugins to install, no NDK, no KSP, no Room, no API keys, no `google-services.json`,
no `secrets` step: the app has no keys to hold — it calls no API and stores nothing (10 runtime
dependencies plus the Compose BOM, every one of them justified in docs/PERFORMANCE.md; 1 test
and 5 instrumentation lines).

## 2. Point the build at your SDK

```bash
# Either environment variable...
export ANDROID_HOME="$HOME/Android/Sdk"

# ...or a local file that is never committed (.gitignore already excludes it):
echo "sdk.dir=/path/to/Android/sdk" > local.properties
```

## 3. Commands

```bash
./gradlew :app:assembleDebug          # debug APK, standard debug key
./gradlew :app:assembleRelease        # minified, INTENTIONALLY UNSIGNED (see §5)
./gradlew :app:testDebugUnitTest      # 53 JVM unit tests
./gradlew :app:lintDebug              # report: app/build/reports/lint-results-debug.html
./gradlew :app:installDebug           # requires a device/emulator
./gradlew :app:connectedDebugAndroidTest   # instrumentation; needs a device (docs/TESTING.md §4)
./gradlew assembleDebug assembleRelease :app:lintDebug --continue   # everything, in one go
```

Artifacts:

```
app/build/outputs/apk/debug/app-debug.apk
app/build/outputs/apk/release/app-release-unsigned.apk
app/build/reports/tests/testDebugUnitTest/index.html
```

The debug variant installs alongside a real Arena app: `applicationIdSuffix = ".debug"` makes it
`com.kartikkamboj.arena.debug`, so the two never collide and their private directories (and therefore
their cookie jars) are separate from each other by package.

## 4. Getting the APK from CI instead of building

1. Actions → **Android CI** → a green run on `main` (or on your PR).
2. Under **Artifacts**: `arena-ai-debug.apk`. Also available: `unit-test-results`, `lint-report`.
3. Install: `adb install -r arena-ai-debug.apk`.

The workflow uploads only on success and uses `if-no-files-found: error`, so a missing artifact always
means the build genuinely failed — check `gh run view <id> --log-failed`.

```bash
gh run list --workflow 'Android CI' --limit 5
gh run download <run-id> -n arena-ai-debug.apk
```

What CI runs, in order: YAML parse of the workflow itself → refusal if the tree tracks a keystore or a
key-shaped literal → `:app:testDebugUnitTest` → `:app:assembleDebug :app:assembleDebugAndroidTest
:app:lintDebug --continue` → `aapt dump badging` on the APK → uploads → a run summary containing the
parsed test counts.

## 5. Release signing (deliberately not configured)

`assembleRelease` runs R8 with `proguard-android-optimize.txt` + `app/proguard-rules.pro` and has **no
signing config**, so CI emits an unsigned APK. That is intentional: publishing a self-signed "release"
from a repository that is not yours to distribute from would look like an official build of an app that
is not official.

To produce an installable release build, add a keystore **outside** the repository and wire it up
locally. The gradle.properties keys below are read if present and are never committed:

```kotlin
// app/build.gradle.kts, inside android { }
signingConfigs {
    create("release") {
        val path = providers.gradleProperty("RELEASE_STORE_FILE").orNull
        if (path != null) {
            storeFile = file(path)
            storePassword = providers.gradleProperty("RELEASE_STORE_PASSWORD").orNull
            keyAlias = providers.gradleProperty("RELEASE_KEY_ALIAS").orNull
            keyPassword = providers.gradleProperty("RELEASE_KEY_PASSWORD").orNull
        }
    }
}
// then: signingConfig = signingConfigs.getByName("release") inside buildTypes.release
```

```bash
keytool -genkeypair -v -keystore arena-release.jks -alias arena -keyalg RSA -keysize 2048 -validity 10000
./gradlew :app:assembleRelease \
  -PRELEASE_STORE_FILE=../arena-release.jks \
  -PRELEASE_STORE_PASSWORD=… \
  -PRELEASE_KEY_ALIAS=arena \
  -PRELEASE_KEY_PASSWORD=…
```

If you add this to CI, put the keystore in a Actions secret as base64, decode it into the runner's
temp directory, pass the four properties, and make sure no step echoes them (`gh secret set`, never
`echo`). The current workflow has `permissions: contents: read` and reads no secrets at all.

`isShrinkResources` is left `false` on purpose: resource shrinking plus `android:usesCleartextTraffic`
audits and locale config is a combination worth testing on a device before trusting it.

## 6. R8 notes already handled

`app/proguard-rules.pro` keeps: Kotlin serialization (generated `$$serializer` classes, `Companion`
`serializer()`), the `@Serializable` model package, `kotlinx.coroutines` internals, and Compose's
`androidx.compose.ui.node`/`runtime` frames; it drops nothing related to reflection-based JSON. If a
release build starts throwing `SerializationException` on load, that is the first file to look at — and
reproducing it is `./gradlew :app:assembleRelease && adb install -r <apk>`.

## 7. Offline / restricted networks

The build needs these hosts on a cold cache: `dl.google.com`, `repo.maven.apache.org`,
`services.gradle.org` (wrapper download). If you are behind a proxy, use
`~/.gradle/gradle.properties` `systemProp.https.*ProxyHost=…`; nothing in this project reads
environment variables or requires credentials. Once `~/.gradle/caches` is warm, `./gradlew
--offline :app:assembleDebug` works.

## 8. Common failures

| Symptom | Cause and fix |
| --- | --- |
| `SDK location not found` | no `local.properties` and no `ANDROID_HOME` → §2 |
| `Failed to find target with hash string 'android-35'` | `sdkmanager "platforms;android-35" "build-tools;35.0.0"` (accept licenses with `sdkmanager --licenses`) |
| `Unsupported class file major version` | JDK below 17 → use 17 |
| `Cannot find a Java installation … languageVersion=17` | Gradle is picking a wrong toolchain: `./gradlew -Dorg.gradle.java.home=$JAVA_HOME …` |
| Configuration cache messages about tasks | the cache is off in `gradle.properties` deliberately; do not re-enable it without testing `:app:lintDebug` too |
| `:app:connectedDebugAndroidTest` finds no device | start an emulator or plug in a device with `adb devices` authorising the host |
| Lint red | lint is `abortOnError = false` in this project; a red lint step means something else in the Gradle invocation failed — read the actual task output |

## 9. Version bumps

Single place: `gradle/libs.versions.toml` (`agp`, `kotlin`, `coroutines`, `composeBom`, …). The app's
own version is `versionCode`/`versionName` in `app/build.gradle.kts`. There is no in-app About screen to
keep it in sync with — the shell shows nothing but the site — so the number exists only for the artifact
name and for `dumpsys package`.
