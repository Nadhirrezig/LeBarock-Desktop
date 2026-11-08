AltosPOS — counter app for Windows (Kotlin, Compose Multiplatform Desktop).

* [`shared`](./shared/src/jvmMain) holds the app (screens, caisse API, settings); tests in `shared/src/jvmTest`.
* [`desktopApp`](./desktopApp) holds `main()`, the window and the Windows installer settings.

### Run, test, package

- Run: `gradlew.bat :desktopApp:run`
- Tests: `gradlew.bat :shared:jvmTest`
- Windows installer (on Windows): `gradlew.bat :desktopApp:packageMsi` →
  `desktopApp\build\compose\binaries\main\msi\AltosPOS-<version>.msi`
  (raise `altospos.version` in `gradle.properties` for each release)

Plan, decisions and progress: `CLAUDE.md`.
