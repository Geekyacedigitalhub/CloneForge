# CloneForge

Android-first app cloning project inspired by App Cloner.

## V0.1 foundation
- Native Kotlin Android project
- Installed launchable app discovery
- App/package/version inspection
- Clone configuration screen
- Foundation for the APK transformation engine

## Next engine stage
APK extraction, manifest identity rewriting, rebuild, signing, and installation/export.

CloneForge does not claim arbitrary APK compatibility; protections, DRM, Google services, certificate checks, or other dependencies may prevent some clones from working.

## Local validation
The project includes a small real Android fixture APK used by the JVM pipeline integration test.

From the project root, with Gradle 8.9+ and the Android SDK available locally:

```bash
gradle :app:testDebugUnitTest
```

On Windows PowerShell:

```powershell
gradle :app:testDebugUnitTest
```

The test task builds `:fixture:assembleDebug` automatically and then exercises manifest, DEX, text-reference rewriting, repacking, and final APK verification.

No GitHub Actions or paid build service is required.
