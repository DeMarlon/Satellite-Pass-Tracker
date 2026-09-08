# Releasing SPT

`com.mdeutsch.spt` — Satellite Pass Tracker. Signed manually from Android Studio; there is no CI.

## 1. Build and verify

```bash
./gradlew :app:testDebugUnitTest :app:lintRelease :app:bundleRelease
```

All three must pass. `lintRelease` is not run by `bundleRelease` — `lintVitalRelease` only checks
fatal-severity issues, so run it explicitly.

**R8 is disabled** (`isMinifyEnabled = false`), so there is no mapping file and no
`BUNDLE-METADATA` obfuscation entry to look for. Its absence is expected, not a build failure.

Versions 1.5 (versionCode 6) and 1.5.1 (7) both reached production unable to start, because R8
broke Commons Logging — twice, in two different ways. The full analysis sits in the comment above
`isMinifyEnabled` in `app/build.gradle.kts`; the short version is that `PassPredictor` initialises
a logger in a static field initialiser, and a failed `<clinit>` marks a class permanently
erroneous, so the app could not be reopened rather than merely crashing once.

Re-enabling R8 needs a real fix for Commons Logging — most plausibly excluding it and supplying a
tiny no-op `Log`/`LogFactory` in this app's own source, so no reflection is involved at all — plus
a release build verified **on a device** across TRACK, PLAN, the sky plot, reminders and a reboot.
Both bad releases shipped on the strength of a green build alone; neither was run on a phone.

## 2. Sign

Android Studio → **Build → Generate Signed App Bundle**, keystore alias `key0`, output into
`Releases/V<n>/`.

Bump `versionCode` in `app/build.gradle.kts` first — Play rejects a duplicate.

## 3. Archive

```
Releases/V<n>/
  app-release.aab
```

`Releases/` is deliberately gitignored — an AAB is ~9 MB per release and has no business in git.

There is no `mapping.txt` to archive while R8 is disabled. If R8 is ever re-enabled, copy it from
`app/build/outputs/mapping/release/` into the same folder: Play keeps its own copy for the crash
console, but you need a local one to decode a stack trace a user emails you directly, and it is
specific to that build and cannot be regenerated once the build directory is gone.

## 4. Upload

Closed testing track first, always. Two warnings are expected on the upload screen, and neither
blocks the release:

- The **deobfuscation / missing-mapping warning** — expected, because R8 is disabled. There is
  nothing to upload by hand.
- The **native debug symbols warning** — also expected; see below.

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

Unobfuscated class names in JIT/GC logs (e.g.
`com.example.eps_sgtracker.ui.PassListScreenKt.PassRow$lambda$14`) are now normal in **every**
build, release included, because R8 is disabled — they no longer tell you anything. That changes
the moment R8 is re-enabled, at which point anything R8-sensitive (the trajectory-unit round-trip
especially) has to be tested on a release build rather than a debug one.

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
