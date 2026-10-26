# CloneForge

Android-first app cloning application inspired by App Cloner.

## Features
- **Installed App Discovery**: Browse and search installed launchable apps with package, version, and launcher icon inspection.
- **Compatibility Preflight**: Inspect APK structure (`AndroidManifest.xml`, multi-DEX bytecode, `resources.arsc`, native libraries, split APK detection) and scan DEX/plain-text package references before building.
- **On-Device APK Clone Pipeline**:
  - Binary `AndroidManifest.xml` package, label, and package-prefixed component rewriting
  - Structured multi-DEX (`classes.dex`, `classes2.dex`, `classes10.dex`, …) package descriptor rewriting via `dexlib2`
  - Safe plain-text asset/config reference rewriting with binary payload protection
  - Clean unsigned APK repacking (stripping prior `META-INF` signatures)
  - On-device APK v1/v2/v3 signing via `apksig` and Android Keystore RSA identity
  - Post-build structural, descriptor, and cryptographic signature verification
- **Clone Library & Integrity Management**:
  - Searchable, filterable library of built and imported clones with storage and integrity summaries
  - Dedicated Clone Details screen with SHA-256 fingerprinting and on-demand full integrity verification
  - Install/update via Android `FileProvider` and team APK sharing
  - Local APK import and atomic ZIP backup/restore with path-safety validation and automatic rollback

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
