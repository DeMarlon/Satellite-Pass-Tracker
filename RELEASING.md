# Releasing SPT

`com.mdeutsch.spt` — Satellite Pass Tracker. Signed manually from Android Studio; there is no CI.

## 1. Build and verify

```bash
./gradlew :app:testDebugUnitTest :app:lintRelease :app:bundleRelease
```

All three must pass. `lintRelease` is not run by `bundleRelease` — `lintVitalRelease` only checks
fatal-severity issues, so run it explicitly.

Then confirm R8 actually ran and its output reached the bundle. A green build is **not** sufficient
evidence for either of these:

```bash
ls -la app/build/outputs/mapping/release/mapping.txt
unzip -l app/build/outputs/bundle/release/app-release.aab | grep BUNDLE-METADATA
```

Expect `com.android.tools.build.obfuscation/proguard.map` in that listing. Its presence is what
clears Play's "no deobfuscation file" warning — there is nothing to upload by hand.

## 2. Sign

Android Studio → **Build → Generate Signed App Bundle**, keystore alias `key0`, output into
`Releases/V<n>/`.

Bump `versionCode` in `app/build.gradle.kts` first — Play rejects a duplicate.

## 3. Archive

Copy the mapping file next to the AAB you just signed:

```
Releases/V<n>/
  app-release.aab
  mapping.txt        <- from app/build/outputs/mapping/release/
```

Play keeps its own copy for the crash console, but you need this locally to decode a stack trace a
user emails you directly. It is specific to that build and cannot be regenerated once the build
directory is gone. `Releases/` is deliberately gitignored — these are large binaries, and the AAB
plus mapping is ~50 MB per release.

## 4. Upload

Closed testing track first, always. Two things to confirm on the upload screen:

- The **deobfuscation warning is gone**. If it is not, R8 did not run or the mapping did not reach
  the bundle — see step 1.
- The **native debug symbols warning is still expected** — see below.

## Known: the native debug symbols warning persists

`app/build.gradle.kts` sets `ndk { debugSymbolLevel = "FULL" }`, which is correct. But extraction
runs `objcopy` from the NDK, and **with no NDK installed AGP's `extractReleaseNativeDebugMetadata`
task succeeds while silently producing nothing** — no `debugsymbols` entry reaches the bundle and
Play keeps reporting the warning. The build gives no indication. Check it directly:

```bash
unzip -l app/build/outputs/bundle/release/app-release.aab | grep debugsymbols
```

To fix: Android Studio → SDK Manager → SDK Tools → **NDK (Side by side)**.

Worth knowing before spending the ~1–2 GB download: this app has no first-party native code. The
only `.so` files come transitively from `compose-ui-graphics` and `datastore`, and AndroidX ships
them pre-stripped, so the extracted symbols would carry little beyond exported function names. This
clears a warning more than it buys real debugging.

## Reading logcat

Most `E`-level lines during a session come from *other* processes. Filter to the app's own PID
before concluding anything:

```bash
adb logcat --pid=$(adb shell pidof com.mdeutsch.spt) *:E
```

Three that appear in an unfiltered log and are **not** app problems:

- `Finsky ... ItemStore: getItems RPC failed` — the Play Store (`com.android.vending`) cannot
  resolve catalog metadata for a sideloaded package. Stops once installed from a Play track.
- `InputDispatcher ... Channel is unrecoverably broken` (`system_server`) and
  `SurfaceFlinger ... writeReleaseFence failed. error 32` (`surfaceflinger`) — both logged *after*
  `PROCESS ENDED`, when the input channel and BufferQueue are torn down without an orderly
  unregister. `error 32` is `EPIPE`. Normal on any process kill, including the Studio stop button.

What a genuine failure looks like: `FATAL EXCEPTION`, any `AndroidRuntime` E-line, or
`ANR in com.mdeutsch.spt` from ActivityManager.

**A debug build proves nothing about R8.** If class names appear unobfuscated in JIT/GC logs (e.g.
`com.example.eps_sgtracker.ui.PassListScreenKt.PassRow$lambda$14`), R8 did not run. Anything
R8-sensitive — the trajectory-unit round-trip especially — has to be tested on a release build.

## Keystore

The `.jks` lives in Google Drive (path recorded in `.idea/workspace.xml`, which is gitignored so the
path never enters history). Alias is `key0`.

**The passwords exist only in this machine's IDE credential store.** Put the keystore password,
alias and key password into a password manager. Play App Signing makes an upload-key loss
recoverable through a support reset, but that is days of downtime rather than minutes.

## Room schema

`AppDatabase` has `exportSchema = true`; the JSON lands in `app/schemas/` and **is committed**.
Once v1 has shipped it can never be regenerated, and without it a future version bump has no
baseline to author or test a `Migration` against. Do not gitignore that directory.
