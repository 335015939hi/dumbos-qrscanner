# QR Bin Runner

Tiny Java Android app:

- scans a QR code
- has a manual text fallback
- runs a native/system binary with the scanned/manual text as exactly one argv argument
- shows a spinner while waiting
- displays stdout and exit code, plus stderr if present so failures are not invisible

## Important edit

Edit this line in `MainActivity.java`:

```java
private static final String BINARY_PATH = "/system/bin/REPLACE_ME";
```

Set it to the absolute path of your binary.

This app uses `ProcessBuilder(BINARY_PATH, payload)`, not a shell, so the QR content is passed as a single argument instead of being interpreted as shell syntax.

## Build

Open in Android Studio, or run:

```sh
./gradlew assembleDebug
```

This project does not include a Gradle wrapper. Let Android Studio generate/use one, or run it with your system Gradle.
