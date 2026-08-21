# HyperOS TDK v0.3.0 Architecture

HyperOS TDK is a toolkit, not a single-purpose MTZ importer. Starting with v0.3.0, the application flow is organized around four top-level sections.

## 1. Theme Tools

User-facing theme and MTZ operations live here.

Current capabilities:

- Open Xiaomi Theme Manager
- Select a local `.mtz`
- Start the controlled MTZ import flow
- Track Theme Manager import lifecycle results
- Preserve the device-validated v0.2.5 import engine

Planned additions should remain theme-operation specific, such as MTZ inspection, import history, validation and other Theme Manager tools.

## 2. Diagnostics

Read-only investigation, probing and report-generation tools live here.

Existing diagnostics engine includes:

- Device / Android / HyperOS properties
- Theme Manager package and component inspection
- MTZ metadata and ZIP inspection
- Intent Probe
- ThemeService Binder Probe
- IThemeService Reflection Probe
- MIUI framework artifact discovery/export
- Shareable diagnostics report

Diagnostics must remain separate from normal Theme Tools actions so investigation features do not dominate the primary user flow.

## 3. Module & System

LSPosed/libxposed and system-integration state lives here.

Current scope:

- Theme Manager package availability
- libxposed API target information
- Required Theme Manager scope guidance
- Import bridge / hook architecture summary
- Shortcut to start Theme Manager so the target process can initialize hooks

Future module readiness, scope verification and compatibility checks belong in this section.

## 4. Information & Settings

Application, device and future preference controls live here.

Current contents:

- HyperOS TDK version and package
- Device / Android summary
- v0.3.0 architecture summary
- Placeholder for future persistent user settings

## v0.3.0 UI fixes

The v0.3.0 application shell also addresses two UI issues found in v0.2.5:

1. Launcher icon resources are now explicitly defined, including adaptive and round icons, instead of relying on a generic/blank launcher icon.
2. Theme Tools applies status-bar and navigation-bar insets and is vertically scrollable, preventing the title/content from overlapping system UI or being cut off on smaller displays.

## Compatibility principle

v0.3.0 is primarily an application architecture and UI milestone. The proven v0.2.5 controlled import path is intentionally preserved instead of being rewritten during navigation refactoring.
