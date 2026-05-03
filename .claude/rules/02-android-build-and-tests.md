# Android Build And Tests

- Use Android Studio's bundled JBR on this Mac:

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
```

- Validate meaningful changes with:

```bash
cd android
./gradlew testDebugUnitTest assembleDebug
cd ..
python3 -m py_compile android/tools/ble_encrypt_sender.py
```

- Keep JVM tests focused on pure Kotlin behavior: report formatting, transport
  buffering, touch prediction, crypto packet handling, host classification, and
  state reducers.
- Do not stage broad unrelated files. Stage explicit paths.
- Re-check GitHub Actions after pushing to PR #3.
- Debug APK path after a successful build:

```text
android/app/build/outputs/apk/debug/app-debug.apk
```
