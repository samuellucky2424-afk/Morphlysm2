# Android APK build

The app currently supports Android 7.0 and newer:

- Minimum SDK: API 24 (Android 7.0)
- Compile/target SDK: API 36 (Android 16)
- Version: 4.0.2 (version code 402)
- ABIs: ARMv7, ARM64, and a universal APK containing both

## Backend URL

Vercel variables configure the server only. The APK's server URL is compiled
into `BuildConfig.BACKEND_BASE_URL`. It currently defaults to:

```text
https://morphlysm2.vercel.app/api
```

Override it for a different deployment when building:

```powershell
$env:MORPHLY_BACKEND_BASE_URL = 'https://YOUR_DOMAIN/api'
.\gradlew.bat :app:assembleDebug
```

## Shareable test APK

```powershell
.\gradlew.bat :app:assembleDebug
```

The universal debug APK is directly installable and shareable for testing. It
is debug-signed and must not be submitted to Google Play.

## Signed production APK

Set these only in the local build machine or CI secret store, never Vercel:

```text
MORPHLY_KEYSTORE_FILE=C:\secure\morphly-release.jks
MORPHLY_KEYSTORE_PASSWORD=...
MORPHLY_KEY_ALIAS=...
MORPHLY_KEY_PASSWORD=...
MORPHLY_BACKEND_BASE_URL=https://YOUR_DOMAIN/api
```

Then run:

```powershell
.\gradlew.bat :app:assembleRelease
```

All four signing values are required. The build deliberately does not
debug-sign a release artifact when they are absent.
