# CloneForge

Android-first app cloning application inspired by App Cloner.

## Features
- **Installed App Discovery**: Browse and search installed launchable apps with package, version, and launcher icon inspection.
- **Compatibility & Notification Preflight**:
  - Inspect APK structure (`AndroidManifest.xml`, multi-DEX bytecode, `resources.arsc`, native libraries, split APK bundles) and scan DEX/plain-text package references before building.
  - Inspect notification permissions (`POST_NOTIFICATIONS`, C2DM) and push messaging receivers (FCM/C2DM) with preflight guidance and one-tap system Notification Settings access for installed clones.
- **On-Device APK Clone Pipeline**:
  - **Split APK (App Bundle) Merging**: Automatically merges installed `base.apk` + `split_config.*.apk` files (native libraries, density resources, assets, and secondary DEX files) into a standalone clone APK and clears `isSplitRequired` flags.
  - **Binary `AndroidManifest.xml` Rewriting**: Rewrites package, label, custom `versionName`/`versionCode`, permissions, provider authorities, `taskAffinity`, and package-prefixed components.
  - **Binary `resources.arsc` Package Rewriting**: Rewrites the 128-character UTF-16LE `ResTable_package` header so dynamic resource and notification icon lookups (`Resources.getIdentifier`) resolve under the cloned package.
  - **Structured Multi-DEX Rewriting**: Rewrites both DEX type descriptors (`L<pkg>/...;`) and DEX string literals (`<pkg>`, `<pkg>.*`, `content://<pkg>.*`) across `classes.dex`, `classes2.dex`, `classes10.dex`, etc., so notification broadcasts, `PendingIntent`s, dynamic receivers (`DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`), and `FileProvider` URIs route to the cloned app.
  - **Clone Icon Customization**: Supports visual launcher icon tinting (`Original`, `Cyan`, `Amber`, `Emerald`, `Violet`, `Rose`) for `res/mipmap*` and `ic_launcher*.png` icons.
  - **Safe Plain-Text Reference Rewriting**: Updates textual config/asset references while protecting binary payloads.
  - **Android 11+ (API 30–35) Packaging & Signing**: Stores `resources.arsc` and `lib/**/*.so` uncompressed (`ZipEntry.STORED`) and 4-byte aligned, signs with v1/v2/v3 via `apksig` and Android Keystore RSA identity, and verifies structure, descriptors, and cryptographic signatures.
- **Clone Library & Integrity Management**:
  - Searchable, filterable library of built and imported clones with storage and integrity summaries
  - Dedicated Clone Details screen with SHA-256 fingerprinting, on-demand full integrity verification, direct app launching, and one-tap Notification & App Settings
  - Install/update via Android `FileProvider` and team APK sharing
  - Local APK import and atomic ZIP backup/restore with path-safety validation and automatic rollback

CloneForge does not claim arbitrary APK compatibility; strict hardware DRM, Google Play Services certificate pinning, or native JNI signature verification may still prevent some apps from running when re-signed.

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

The test task builds `:fixture:assembleDebug` automatically and then exercises manifest, `resources.arsc`, DEX, icon, and text-reference rewriting, repacking, and final APK verification.

No GitHub Actions or paid build service is required.
