# User Instruction Memory

This file records user instructions, preferences, and teachings for reference in future interactions.

## Entries

[Project Knowledge Summary]
- Date: 2026-09-18
- Context: Discovered by Agent while converting the HTML barcode app into a native Android project
- Category: Build Methods | Environment Configuration
- Instructions:
  - This repository is now a native Android project (Kotlin, ViewBinding); application module is `:app`, package `com.barcode.factory`.
  - Build debug APK with `./gradlew :app:assembleDebug`; output is `app/build/outputs/apk/debug/app-debug.apk`.
  - Toolchain required: JDK 17 plus Android SDK `platforms;android-34` and `build-tools;34.0.0`. The SDK location is set in `local.properties` via `sdk.dir` (gitignored).
  - `index.html` at the repo root is the legacy pre-conversion page and is no longer used by the app.
