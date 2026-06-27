# BlowAway

BlowAway is an Android app that dismisses only the temporary heads-up notification banner when the user blows into the microphone. The notification remains in the shade, unread, actionable, and present in notification history.

## Build

Open the folder in Android Studio or run:

```powershell
gradle test assembleDebug connectedDebugAndroidTest
```

The project targets Android 16/API 36 and supports Android 10/API 29 and newer.

## Permissions

BlowAway requires microphone permission, notification listener access, accessibility service access, and foreground service permission. Audio is processed on-device only; no recordings are stored and no analytics or cloud processing are included.

## Architecture

The app uses Kotlin, Jetpack Compose, MVVM, Hilt, Coroutines/Flow, Room, and DataStore. See [docs/architecture.md](docs/architecture.md) for the diagram and permission flow.

## Detection

Phase 1 ships a heuristic detector using adaptive RMS, zero crossing rate, noise floor tracking, clipping rejection, cooldown, and speech rejection features. Phase 2 adds a TensorFlow Lite detector implementation and a training pipeline plan in [docs/tflite_training_pipeline.md](docs/tflite_training_pipeline.md).
