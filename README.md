# Overdrive

Overdrive is a media volume booster for GrapheneOS and other AOSP-derived builds. It attaches a
gain stage and a brickwall limiter to Android's global audio output mix, and optionally lifts the
headphone output ceiling without keeping any process running at all. There is no root requirement,
no MediaProjection, no screen capture, and no permanent notification once the silent keep-alive is
granted. The signed release APK weighs 1.8 MB.

## Available approaches on current Android

Boosting another application's audio on a stock Android build without root comes down to three
mechanisms, of which only one remains viable on current releases.

The first is attaching an `AudioEffect` to audio session 0, which AudioFlinger treats as the global
output mix. Every application's media playback is routed through whatever effect is placed there.
The only permission involved is `MODIFY_AUDIO_SETTINGS`, a normal permission granted at install
time with no user prompt. AOSP has flagged session 0 as deprecated for years and the framework logs
a warning on every attach, but the code path has never been removed and continues to function on
Android 16. Overdrive uses this approach.

The second is `AudioPlaybackCaptureConfiguration`, which captures the system mix, amplifies it in
userspace, and pushes the result back out. It performs worse on every axis. Since Android 14 it
requires a foreground service of type `mediaProjection` that must be started before the projection
is acquired, otherwise the platform throws `SecurityException`. It also brings a permanent screen
capture indicator, additional latency, an opt-out any application can set on its own audio, and a
hard exclusion of DRM-protected content. Nothing is gained over session 0.

The third is not a boost but a ceiling removal. Android clamps output on wired and Bluetooth
headsets for IEC 62368-1 and EN 50332 compliance, driven by the secure setting
`audio_safe_volume_state`. Clearing that setting lifts a real limit on headphone output and
requires no running process whatsoever. The `WRITE_SECURE_SETTINGS` permission it depends on is
never granted at install time, but it can be granted once over adb and then persists indefinitely.
AOSP re-arms the state at boot and periodically afterwards, which is the only reason Overdrive
schedules anything.

## Effect lifetime and the keep-alive requirement

AudioFlinger tracks each effect through an `EffectHandle` binder owned by the creating process.
When that process exits, the handle destructor runs and the effect is torn down along with it. The
boost cannot outlive the application. This is why volume boosters on the Play Store ship a
persistent notification. They are not performing background work, they are paying for a foreground
service purely to avoid being killed.

The power implications are frequently misunderstood. An idle process holding a single binder handle
acquires no wakelocks, schedules no timers, and consumes no CPU. It amounts to roughly twenty
megabytes of RAM and no measurable current draw. The DSP cost of the effect itself runs inside
audioserver and only while audio is already playing, where it amounts to a gain multiply and a
limiter against a decode path and an amplifier that are already active. A foreground service costs
a notification row rather than battery life, and those are different problems with different
solutions.

## Two keep-alive hosts

Overdrive ships both and selects between them at runtime.

The preferred host is an `AccessibilityService` that subscribes to nothing. Android binds
accessibility services persistently, restarts them when they die, and re-binds them at boot before
first unlock, which is the exact lifetime a global audio effect requires, and it carries no
notification. The service zeroes its own event types in `onServiceConnected`, sets no flags,
declares `canRetrieveWindowContent="false"` in its manifest config, and passes an empty package
filter. It cannot read the screen and the platform delivers it nothing to read. The single event
type present in the XML exists only because the platform rejects a configuration declaring none.
This is an abuse of the accessibility API and Play Store review would reject it outright, but the
application is distributed by sideloading and the alternative is a notification the user did not
ask for.

The fallback host is a foreground service of type `specialUse` on an `IMPORTANCE_MIN` channel with
no sound, no badge, and `VISIBILITY_SECRET` on the lockscreen, which is the quietest a mandatory
foreground notification is permitted to be. The type selection is functional rather than cosmetic.
Android 15 forbids `BOOT_COMPLETED` receivers from starting several foreground service types
including `mediaPlayback`, while `specialUse` is absent from that list, so the boost can restore
itself after a reboot even without the accessibility grant.

`HostManager` ensures only one host is ever active. The foreground service is torn down whenever
the accessibility service is connected. When the user revokes accessibility, `onUnbind` attempts a
handoff to the notification host on the way out, and the UI reports the live host rather than
assuming the handoff succeeded.

## Signal path

`LoudnessEnhancer` provides the gain, retargeted in place in millibels rather than reattached, so
dragging the slider does not fragment the audio. `DynamicsProcessing` follows it as a brickwall
limiter, configured with the multiband compressor and both equalizer stages disabled. The limiter
runs 20:1 above -0.5 dBFS with a 1 ms attack and 60 ms release, with both channels sharing link
group 0 so they duck together and the stereo image does not shift when one side hits the rail. The
limiter is best effort. On a device whose audio HAL ships no software effect bundle the boost still
applies and simply clips earlier.

Health checking carries more weight than it appears to. An effect handle can outlive a playback
stop as a Kotlin object while AudioFlinger has already dropped or reassigned it, and control can be
taken by a higher priority client such as an OEM equalizer, after which writes are accepted and
then ignored. Both `hasControl()` and `enabled` are checked on every playback transition, and a
failure of either triggers a full rebuild, which is the only reliable repair.

By default the effect is attached only while media is actually playing and released twelve seconds
after playback goes idle, so a track change or a seek does not tear it down. Playback detection
prefers the `AudioPlaybackConfiguration` list supplied by `registerAudioPlaybackCallback`, filtered
to `USAGE_MEDIA`, `USAGE_GAME`, and `USAGE_UNKNOWN`, falling back to `isMusicActive()`, which is
unreliable across transitions on its own. Calls and voice chat are never touched. All effect work
runs on a dedicated `HandlerThread` because creating an `AudioEffect` is a blocking binder
round-trip into audioserver and the callbacks that trigger it arrive at times the application does
not control.

## Running alongside a system-wide equalizer

AudioFlinger keeps one effect module per UUID within an effect chain rather than instantiating a
second one, and hands control to whichever client holds the highest priority. Every other client
can still write parameters, but the writes are accepted and then discarded, which is
indistinguishable from working. Overdrive's limiter is a `DynamicsProcessing` instance, and so is
most of what Wavelet, JamesDSP, and AudioFX do, which puts them all in contention for the same
module whenever they sit on session 0.

Wavelet's default mode attaches to the media player's own session ID rather than session 0, so the
chains are separate and nothing is contested. The player session runs first and the global mix
second, meaning the EQ curve is applied and Overdrive's gain then operates on the result, which is
the correct ordering. Wavelet's legacy mode moves to session 0 and does collide.

Overdrive resolves this by losing on purpose. The limiter is requested at priority -1 so control
always goes to the other application, and after the effect is created `hasControl()` is checked
before any parameter is written. If control was not granted, the handle is released rather than
kept as decoration, and the UI reports which of the two happened. Where a known system-wide
equalizer is installed, detected through a narrow manifest queries declaration listing those three
packages and nothing else, the limiter defaults to being skipped entirely on first run. That is a
starting value only and can be overridden.

Yielding is not the same as running unprotected. AOSP's `LoudnessEnhancer` is not a plain gain
stage. It wraps an adaptive dynamic range compressor from
`frameworks/av/media/libeffects/loudness` that compresses anything amplified beyond the platform
sample range, which is weaker than a brickwall limiter at -0.5 dBFS but is not nothing. The real
hazard when stacking is headroom rather than protection. AutoEq curves apply positive gain on
individual bands and the equalizer reserves headroom with a negative preamp to compensate, so
adding another +10 dB on top spends exactly the margin that was just set aside. Use less gain than
would be appropriate with Overdrive alone.

## Lifting the headphone limit

Grant the permission once over adb:

```bash
adb shell pm grant io.github.zqpvr.overdrive android.permission.WRITE_SECURE_SETTINGS
```

Overdrive then writes `audio_safe_volume_state` to 2 and re-applies it every six hours and after
boot on an inexact `RTC` alarm rather than `RTC_WAKEUP`, so it never wakes the device and instead
fires whenever the phone next happens to be awake. For a setting measured in hours that is
sufficient and costs nothing. The boot re-apply is delayed by ninety seconds because AudioService
settles the safe volume state roughly thirty seconds into boot and an earlier write is simply
overwritten.

This does not clear every restriction. Android 14 introduced a second and independent mechanism on
top of the old flag in the form of computed sound dose, an EN 50332-3 implementation living in
`SoundDoseManager` inside audioserver. It performs frequency-weighted analysis of the actual signal
and tracks exposure across a rolling seven day window before forcing the volume down. No
application can reach it, and in some regions it cannot be disabled from Settings either. Check
Settings, Sound and vibration, Hearing wellness before assuming Overdrive is at fault.

## Building

A JDK 17 or newer and an Android SDK with platform 36 are required. Point `local.properties` at the
SDK with a single `sdk.dir=` line. It is gitignored because the path is machine specific.

```bash
./gradlew assembleRelease
```

Release signing is driven by a `keystore.properties` file in the project root holding `storeFile`,
`storePassword`, `keyAlias`, and `keyPassword`. Both that file and any keystore are gitignored and
absent from a fresh clone, in which case the release build falls back to the debug key so the
build still completes. Prebuilt signed APKs are published under releases.

## Installing

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

On GrapheneOS the accessibility toggle for a sideloaded application stays greyed out until it is
unlocked through App info, the overflow menu, and Allow restricted settings. Battery optimization
should also be disabled for the application. GrapheneOS reaps background processes aggressively,
and while both hosts are designed to survive that, there is no reason to make the platform work
harder than necessary.

## Known limitations

DRM-protected audio and mixer bypass paths, including some offloaded and direct output routes, do
not pass through the global effect chain and cannot be boosted. Devices whose vendor audio HAL
omits the software effect bundle will fail to create the effect at all, and the application
surfaces that failure rather than silently doing nothing. Beyond roughly +10 dB the limiter is
working most of the time and the result becomes flat and loud rather than louder, which is a
property of dynamic range compression rather than a defect.

Sustained boost into headphones damages hearing, and into a phone speaker it drives the amplifier
into its excursion limit. The limiter protects the signal. It does not protect the listener and it
does not protect the driver.
