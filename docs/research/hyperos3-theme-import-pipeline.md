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

## 4. Architectural consequence

Standard public `VIEW` / `SEND` intent probing produced no MTZ handler, and the Binder service is not an MTZ import API. The next viable route is therefore code executing inside the Theme Manager process.

HyperOS TDK v0.2.0 introduces a modern LSPosed entry scoped only to:

```text
com.android.thememanager
```

The first LSPosed milestone is intentionally read-only: it resolves the exact classes, constructors and methods above using the Theme Manager process ClassLoader and logs a readiness result. It does not invoke `ThemeImportManager.v(...)`, hook behavior, import a theme, or modify Theme Manager state.

Once the in-process signatures are confirmed on the target build, a later controlled-import milestone can reproduce the Theme Manager-owned call sequence with an explicitly selected MTZ.
