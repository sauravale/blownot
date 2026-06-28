# Android installation and security notes

BlowAway supports Android 10 and newer through `minSdk 29`. The app targets the current Android SDK so newer phones apply the modern permission and foreground-service rules.

## Why sideloaded builds can be blocked

Newer Android versions may mark manually installed APKs as risky when they request Accessibility or Notification Listener access. This is an Android security protection for sideloaded apps, not a compile failure in BlowAway.

For testing on a personal phone, the practical options are:

1. Install with Android Studio or `adb install`. This is usually the cleanest developer test path.
2. If installed from the phone's file manager, open the app's system App info screen and use the three-dot menu to allow restricted settings, then enable Accessibility and Notification access.
3. For broader testing, distribute through Google Play Internal Testing or another trusted managed installer. That is the best route for newer Android security prompts.

Debug APKs are signed with a debug key and will look less trusted than a properly signed release build. Before public distribution, create a release signing key and distribute a release APK/AAB through a trusted channel.

## Compatibility approach

- `minSdk 29`: keeps Android 10 support.
- `targetSdk 35`: opts into modern Android security behaviour.
- Runtime permissions: microphone and notification posting are requested in app.
- Special access: Accessibility and Notification Listener access must still be granted by the user in Android settings.

## Recording dataset

The `records/` folder is intentionally not ignored. It can hold exported recording-lab zip files for detector analysis.
