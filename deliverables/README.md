# SYmuS 0.1.0 — build artifacts

Application id `io.github.lectorsys.symus`, versionCode 1, minSdk 26,
targetSdk 37, compileSdk 37. versionName `0.1.0` for the `full` build and
`0.1.0-lite` for the `lite` build, so the About screen says which one is
installed.

## Two builds, and why

The on-device inference runtime (MediaPipe `tasks-genai` 0.10.35) ships prebuilt
native libraries for `arm64-v8a` and `x86_64` **and for no other ABI**. That is
not a limitation the app can work around at runtime, because the decision is
made before the app ever runs: Android refuses to install an APK that contains
native libraries when none of them match the device's ABI.

Measured on this machine, on a device reporting `x86_64,arm64-v8a`, using an APK
whose only `lib/` entries were `armeabi-v7a`:

```
Failure [INSTALL_FAILED_NO_MATCHING_ABIS: Failed to extract native libraries, res=-113]
```

An earlier version of this file said "on a device with neither ABI the app
installs and plays music normally". **That was wrong.** It does not install at
all. The `lite` build is the fix.

| Build | ABIs in the APK | Assistant | Everything else |
|-------|-----------------|-----------|-----------------|
| `full` | `arm64-v8a`, `x86_64` | Statistics **and** conversation | identical |
| `lite` | `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64` | Statistics only | identical |

`lite` omits the MediaPipe dependency entirely. Verified rather than assumed:
scanning every `classes*.dex` in the lite APK for the byte string `mediapipe`
returns **0 occurrences** (the full APK has 220). Nothing in the lite build can
reach a native class that is not there, and `LlmRuntime.availability()` returns
`RuntimeMissing` before any engine is constructed.

`LlmEngine.kt` is the only file that differs between the two — `src/full/` has
the MediaPipe implementation, `src/lite/` has a stub that refuses with a
sentence. No flag is threaded through the feature.

## Android version support and CPU architecture support are separate

Two different axes, stated separately because conflating them is what produced
the error above.

- **Android version:** both builds are minSdk 26 (Android 8.0) to targetSdk 37.
  Identical. Nothing about the assistant changes this.
- **CPU architecture:** `full` installs on 64-bit ARM and x86-64 only. `lite`
  installs on every ABI Android supports at API 26+.

Device RAM is a third, independent axis and is **not** inferred from the CPU.
`LlmRuntime.availability()` reads `ActivityManager.MemoryInfo.totalMem` and
compares it against the model's own `minRamBytes`; a 32-bit device is not
assumed to be small, and a 64-bit device is not assumed to be large. A zero
reading is treated as "the query failed", not as "no memory".

## Files

| File | Size | Signing | Installable |
|------|------|---------|-------------|
| `symus-0.1.0-full-debug.apk` | 87,454,617 B | This machine's Android debug key | Yes |
| `symus-0.1.0-full-release-unsigned.apk` | 61,965,367 B | **Unsigned** | No — sign it first |
| `symus-0.1.0-full-release-testsigned.apk` | 62,067,934 B | This machine's debug key — **testing only** | Yes |
| `symus-0.1.0-lite-debug.apk` | 30,425,104 B | This machine's Android debug key | Yes |
| `symus-0.1.0-lite-release-unsigned.apk` | 5,039,370 B | **Unsigned** | No — sign it first |
| `symus-0.1.0-lite-release-testsigned.apk` | 5,140,241 B | This machine's debug key — **testing only** | Yes |

SHA-256:

```
19430f6dca92f38f1f4ab8cd540fd3b53a6b6bec340e86e42d9bf7bd4562d91d  symus-0.1.0-full-debug.apk
2927c8bdc653e78369c688c5083536516de80c578bc22048a3ffdecc947d182b  symus-0.1.0-full-release-testsigned.apk
12d0608b3091923e592c7bacb4a3847ab7b4437dc0bedc1f26a50d94608ce5a1  symus-0.1.0-full-release-unsigned.apk
4ad1213bab97ba503d16bcbdbd75b0a86cd90e76546f2d920822077452aaf47c  symus-0.1.0-lite-debug.apk
a1f48d291403251a02520c180ebc5ce46f9a36ce35c5edadb3d4fdd542ba293a  symus-0.1.0-lite-release-testsigned.apk
d21b734d72eeb504143cf72203d43a00576ca4e63c672bb40002e3dfde8258d8  symus-0.1.0-lite-release-unsigned.apk
```

All six were refreshed after the last source change by building both debug and
release variants. Both debug unit-test suites passed.

## Where the size goes

Minified release, compressed bytes:

| | full | lite |
|---|---|---|
| `lib/x86_64` | 30.26 MB | 0.01 MB |
| `lib/arm64-v8a` | 26.64 MB | 0.01 MB |
| `lib/armeabi-v7a` | — | 0.01 MB |
| `lib/x86` | — | 0.01 MB |
| dex | 3.69 MB | 3.64 MB |
| resources + other | 1.21 MB | 1.20 MB |

**92% of the full release APK is two inference `.so` files.** The application's
own code, minified by R8, is 3.7 MB in both builds — which is why the lite
release is 5.0 MB rather than 62 MB.

**The model is not in either APK.** It is downloaded or imported by the user,
and nothing in the app requires one.

## Installing

```
adb install -r symus-0.1.0-full-debug.apk     # 64-bit devices
adb install -r symus-0.1.0-lite-debug.apk     # any device, no conversation mode
```

Both flavours share one `applicationId`, so `adb install -r` switches between
them in place and **keeps the library, playlists, history, downloaded audio and
any installed model**. Going from `full` to `lite` leaves an installed model on
disk untouched and unused; going back picks it up again.

Uninstalling (`adb uninstall io.github.lectorsys.symus`) erases all of it.

## About the test-signed builds

`*-testsigned.apk` are the same minified bytes as the matching unsigned file,
signed with the Android debug key on the machine that built them
(`~/.android/debug.keystore`, certificate SHA-256
`f6d3936b74db6f859ba116ce8fdce88c9449ce7a3f575f8c4dc275cb763745f0`, DN
`C=US, O=Android, CN=Android Debug`). They exist so R8 output can be installed
and exercised rather than only compiled.

They are not production releases. The debug keystore's path, password
(`android`) and alias (`androiddebugkey`) are standardised and public, so the
file offers no protection — anyone with a copy can sign anything as this
identity. It carries a placeholder DN, it exists only on the machine that built
this, and Google Play rejects debug-signed uploads outright. Note that the key
*material* is generated locally and is not shared between machines; an APK
signed elsewhere will not upgrade an install of this one, because Android
refuses a signature change.

Shipping requires a real signing identity — a key generated with `keytool`, in a
keystore whose password is not published, with a long validity and a real DN.
**This project has no such identity and none was created**, because creating one
is a decision about who is publishing the app, not a build step.

## Screenshots

`screenshots/` holds earlier captures, taken on a
`sdk_gphone64_x86_64` emulator running Android 16 (API 36) at 1080×2424, with a
library of real tracks fetched from YouTube. Portrait, landscape and 1.5×
font-scale variants are included; the filenames say which.
