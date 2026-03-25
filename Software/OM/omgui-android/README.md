# OmGUI Android

Android tablet port of the AX3 `omgui` workflow.

## What Is Included

- USB host discovery for AX3 devices (`VID 0x04D8`, `PID 0x0057`)
- CDC serial control path for:
  - device status snapshot
  - identify LED
  - time sync
  - recording configuration
  - clear / wipe
- USB mass-storage download of `CWA-DATA.CWA`
- app-managed working folders:
  - `openmovement/downloads`
  - `openmovement/exports`
- `.CWA` metadata parsing and filename templating
- native `omconvert` bridge via JNI for:
  - WAV
  - CSV
  - SVM
  - WTV
  - cut-point / PAEE
  - sleep exports
- Compose tablet UI for devices, files, jobs, and settings

## Build

```bash
cd Software/OM/omgui-android
./gradlew assembleDebug
```

Debug APK:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Notes

- The Android port keeps the original repo’s conversion logic in native code, but rebuilds the device transport with Android USB APIs instead of the desktop `omapi` device finder.
- The app uses an app-specific working root instead of arbitrary desktop folders.
- The mass-storage and CDC control paths both require USB host permission on-device.
- The project builds locally, but real tablet validation is still required against physical AX3 hardware for:
  - CDC timing/baud expectations
  - composite-device permission behavior on target tablets
  - long download stability
  - converter output parity against desktop `omgui`
