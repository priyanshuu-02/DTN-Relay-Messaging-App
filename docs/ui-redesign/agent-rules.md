# Rules for every UI-redesign phase agent

Workspace: `d:\PROJECTS\DTN CN CAPSTONE` (Android app `com.dtn.mesh`; Kotlin 2.0.21, Compose BOM 2024.12.01,
Hilt, Room, navigation-compose 2.8.5, AGP 8.13.2, JDK 21, minSdk 26, targetSdk 36; Heltec V3 firmware in `firmware/`).

## Spec (read first)
- `docs/ui-redesign/plan.md` (locked plan: Decisions table + your phase; supersedes architecture §10–11)
- `docs/ui-redesign/architecture.md`, `docs/ui-redesign/visual-design.md`, `docs/ui-redesign/navigation.mmd`
- The previous phases' reports: `_diag/phase<N>_report.md` (what already exists, what was left for you)
- `.kiro/steering/project.md`, `.kiro/steering/wire-protocol.md` (project rules: obey them)
- Phase 1 built: `ui/theme` (DtnTheme, DtnIcons), `ui/components` (Basics, Rows, Controls), `ui/shell` (DtnApp NavHost,
  ConnectionLinks, TabPages), `ui/settings/SettingsPage.kt`, `ui/devtools/DevToolsPages.kt`, `service/DiagnosticsLog.kt`.
  Phase 6 deleted the old `MainScreen`/`HomeScreen`/`ConnectionPanel`/`LifecycleTab`/`LogTab`/`SettingsDialog` code
  (see `_diag/phase6_report.md`). Reuse the shared components; don't duplicate them.

## Rules
- Do ONLY your phase. Never break or hide an existing feature: until it's rebuilt, keep hosting the old composable.
- **No git commits, no push.** Never touch `keystore/`, `keystore.properties`, `dist/`. Never edit `docs/meshtastic-ref/**`.
- Room: never `fallbackToDestructiveMigration`. Any schema change = a real `Migration`/`AutoMigration` + the exported
  schema JSON + a case in the instrumented `app/src/androidTest/.../MigrationTest.kt`.
- Wire protocol / firmware changes must update `.kiro/steering/wire-protocol.md` and `firmware/README.md` in lockstep.
- Never log message text or key material (ids, sizes, counts only). Use `DiagnosticsLog` for new log lines.
- No main-thread network or disk I/O. Match the existing code style. Icon buttons need content descriptions; touch
  targets ≥ 48 dp. `ui/MessageStatus.kt` `deliveryState()` stays the one delivery-status model.
- Don't stop to ask questions: make the choice that best fits the docs, and list it under "Choices made" in your report.
- Leave background processes you didn't start alone (e.g. Python serial monitors on COM11 / COM3), unless your phase
  says otherwise.
- Add JVM unit tests (`app/src/test`) for the pure logic you add. Keep every existing test passing.

## Build / verify (Windows PowerShell)
A foreground shell command is killed after ~1–2 minutes. Run Gradle as a background process and poll:
1. Delete `_diag\p<N>_build_exit.txt` if present, then start (cwd = workspace root):
   `.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --console=plain *> "_diag\p<N>_build.txt"; "GRADLE_EXIT=$LASTEXITCODE" | Out-File "_diag\p<N>_build_exit.txt"`
2. Poll with sleeps of at most 55 s until the exit file exists. Errors: `^e: ` and `FAILED` lines in `_diag\p<N>_build.txt`.
3. "Could not delete …caches-jvm": `.\gradlew.bat --stop`, kill java processes whose command line contains
   `KotlinCompileDaemon`, then rerun with `:app:compileDebugUnitTestKotlin --rerun :app:testDebugUnitTest :app:assembleDebug`.
4. Count results in `app\build\test-results\testDebugUnitTest\*.xml` (tests / failures / errors).

## Install: Phase 7 ONLY
Phases 3–6: do NOT install on the phone (the user wants one install at the end). Build + unit tests only, and write
"install: skipped (Phase 7 installs)" in the report. Phase 7 follows the steps below.

## Install steps (Phase 7; only if a phone is authorized; never pause for one)
- adb: `$env:USERPROFILE\AppData\Local\Android\Sdk\platform-tools\adb.exe`. Check `adb devices`; use the one listed as
  `device` (currently `ZT95G67LINJJIJIN`, a Realme RMX3870). If none is authorized, skip and say so in the report.
- `adb -s <serial> install -r app\build\outputs\apk\debug\app-debug.apk` (NEVER uninstall or clear app data). Run it as a
  background process with output to `_diag\install_p<N>.txt` and poll: on this ColorOS phone an install can wait for a
  confirmation tap on the phone. If it hasn't finished after ~2 minutes, say so in the report and move on. Launch with
  `adb -s <serial> shell monkey -p com.dtn.mesh -c android.intent.category.LAUNCHER 1`, wait ~5 s, and confirm
  `adb -s <serial> logcat -d -b crash` has no `FATAL EXCEPTION` for `com.dtn.mesh`. Optionally dump the screen with
  `uiautomator dump` to check your pages render.

## Report
Write `_diag/phase<N>_report.md` and give the same summary in your final message: what you built, files added/changed,
choices made, test count, APK time, install/launch result, and anything left for later phases.
