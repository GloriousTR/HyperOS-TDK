# HyperOS TDK

HyperOS TDK is an Android diagnostic toolkit for investigating Xiaomi HyperOS Theme Manager behavior, especially the local `.mtz` import path.

## Current milestone — v0.1.0 Foundation

The first milestone intentionally observes the system before attempting any Theme Manager import bypass or modification.

### Included

- Device / Android / HyperOS property summary
- Xiaomi Theme Manager package detection
- Theme Manager version and package component inventory
- Storage Access Framework `.mtz` picker
- MTZ metadata, SHA-256 and ZIP entry inspection
- Shareable plain-text diagnostics report

### Not included yet

- Automatic MTZ import
- Private Xiaomi API calls
- Shizuku / ADB integration
- Root / KernelSU features
- Theme Manager modification

These are intentionally deferred until diagnostic reports reveal the actual import surface on target HyperOS builds.

## Project configuration

- Kotlin 2.3.21
- Android Gradle Plugin 9.3.0
- Gradle 9.5.0
- compileSdk / targetSdk 36 (Android 16)
- minSdk 26
- Jetpack Compose + Material 3

## First device test

1. Open the project in a current Android Studio release with JDK 17.
2. Install Android SDK Platform 36 and Build Tools 36.x.
3. Build and install the debug APK on the target Xiaomi / HyperOS device.
4. Tap **Theme Manager Tara**.
5. Tap **MTZ Seç** and choose a known theme file.
6. Tap **Tanılama Raporu Oluştur** and share/save the generated report.
7. Use the report as the input for the next milestone: ThemeManager intent/import probing.

## Repository note

`gradle/wrapper/gradle-wrapper.properties` is included and targets Gradle 9.5.0. The generated wrapper JAR/scripts should be committed after running the wrapper task in Android Studio or a local Gradle installation.

## Safety / scope

v0.1.0 is read-only with respect to Theme Manager. It does not overwrite theme files, change system packages, request root, or alter device settings.

## Build-system note

The project uses AGP 9 built-in Kotlin support. `org.jetbrains.kotlin.android` is intentionally not applied; Compose compiler remains pinned to Kotlin 2.3.21.
