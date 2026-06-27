# BlowAway

BlowAway is an Android app that dismisses only the temporary heads-up notification banner when the user blows into the microphone. The notification remains in the shade, unread, actionable, and present in notification history.

## Build

Open the folder in Android Studio or run:

```powershell
gradle test assembleDebug connectedDebugAndroidTest
```

The project compiles against Android 15/API 35 because the current AndroidX/Compose dependencies require it, but targets and supports Android 10/API 29 for Galaxy S9 testing. compileSdk is a build-time API stub and does not make the app require Android 15 at runtime.


## Lightweight Requirement

BlowAway should stay as lightweight as possible in code size, dependency footprint, build tooling, runtime CPU, memory, battery, and foreground-service work. Prefer platform APIs, simple Kotlin implementations, calibrated heuristics, and measured thresholds before adding heavier libraries, background processing, or ML runtimes. Any new dependency or always-running work must have a clear user-visible benefit and should be validated against APK size, build time, and idle runtime impact.

## Permissions

BlowAway requires microphone permission, notification listener access, accessibility service access, and foreground service permission. Audio is processed on-device only; no recordings are stored and no analytics or cloud processing are included.

## Architecture

The app uses Kotlin, Jetpack Compose, MVVM, Hilt, Coroutines/Flow, Room, and DataStore. See [docs/architecture.md](docs/architecture.md) for the diagram and permission flow.

## Detection

Phase 1 ships a heuristic detector using adaptive RMS, zero crossing rate, noise floor tracking, clipping rejection, cooldown, and speech rejection features. TensorFlow Lite is intentionally excluded until the lightweight heuristic and calibration flow are stable; the future training plan is documented in [docs/tflite_training_pipeline.md](docs/tflite_training_pipeline.md).
