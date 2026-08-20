# HyperOS 3 Theme Import Pipeline — Device Evidence

Target environment used for this analysis:

- HyperOS 3 / Android 16 (API 36)
- Theme Manager package: `com.android.thememanager`
- Theme Manager version: `3.0.6.8-global`
- Device-exported framework artifact: `/system_ext/app/miuisystem/miuisystem.apk`

## 1. IThemeService is not the MTZ import API

Static analysis of the device's `miuisystem.apk` shows that `miui.content.res.IThemeService` exposes only four methods:

```text
saveLockWallpaper(String): boolean
saveIcon(String): boolean
saveWallpaper(String): boolean
saveCustomizedIcon(String, Bitmap): boolean
```

The generated Stub contains these transaction codes:

```text
1 -> saveLockWallpaper
2 -> saveIcon
3 -> saveWallpaper
4 -> saveCustomizedIcon
```

Therefore the exported `ThemeService` Binder path discovered in v0.1.2 is useful for wallpaper/icon operations, but it is not a local MTZ import interface.

Device artifact SHA-256:

```text
daf4e91c6ddf52c857cc27590664c9f6f0c0874024ac1797dbc7e07841312b21
```

## 2. Theme Manager contains an internal ThemeImportManager

The Theme Manager APK contains:

```text
com.android.thememanager.basemodule.unzip.c
```

String and call-flow evidence identifies this class as `ThemeImportManager`. It emits import lifecycle broadcasts including:

```text
action_resource_import_start
action_resource_import_complete
action_resource_import_fail
action_resource_import_udpate
extra_resource
extra_import_current_bytes
extra_import_total_bytes
```

The important import entry method is:

```text
c.v(ResourceContext, Resource): void
```

## 3. Theme Manager's own local MTZ flow calls the import manager

In:

```text
com.android.thememanager.mine.local.customize.a
```

the local customize/backup task creates `backup.mtz`, then performs this sequence:

```text
controller.a.e()
    .g()
    .a()                           -> ResourceContext

new Resource()
Resource.setDownloadPath(mtzPath)

controller.a.e()
    .i()                           -> ThemeImportManager
    .v(resourceContext, resource)  -> import
```

The relevant private API types are:

```text
com.android.thememanager.basemodule.controller.a
com.android.thememanager.basemodule.controller.s
com.android.thememanager.basemodule.model.ResourceContext
com.android.thememanager.basemodule.resource.model.Resource
com.android.thememanager.basemodule.unzip.c
```

This is direct evidence that Theme Manager can import an MTZ from a filesystem path through its internal import pipeline.

## 4. v0.2.0 in-process validation passed on the target device

HyperOS TDK v0.2.0 was enabled through the device's modern Xposed framework (Vector API 102), scoped only to:

```text
com.android.thememanager
```

Two independent Theme Manager process starts produced the same result:

```text
READINESS RESULT: 10/10 checks passed. No Theme Manager method was invoked.
```

The runtime ClassLoader resolved the exact controller, ResourceContext, Resource, ThemeImportManager and local customize caller signatures discovered during static analysis. No HyperOS-TDK exception or fatal error was observed in the exported framework log around those readiness runs.

## 5. v0.2.1 controlled-import boundary

Because the in-process signatures are now device-confirmed, v0.2.1 introduces the first explicitly user-triggered import request. It does not import anything automatically at module load.

The control path is intentionally constrained:

```text
HyperOS TDK app
  -> user selects an .mtz
  -> user confirms a warning dialog
  -> signature-protected broadcast
  -> receiver registered inside ThemeApplication
  -> validate /storage/emulated/0 path + .mtz + readable file + PK ZIP signature
  -> new Resource()
  -> Resource.setDownloadPath(path)
  -> controller.a.e().g().a() -> ResourceContext
  -> controller.a.e().i() -> ThemeImportManager
  -> ThemeImportManager.v(ResourceContext, Resource)
```

The module also monitors Theme Manager's own import lifecycle broadcasts (`start`, `udpate`, `complete`, `fail`) and writes them to the `HyperOS-TDK` framework log for the first controlled test.

This milestone may change Theme Manager state because it calls the genuine private import queue. It does not write to `/system`, issue Binder transactions, or apply an MTZ automatically outside Theme Manager's own import pipeline.
