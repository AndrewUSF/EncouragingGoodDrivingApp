# SteadyDrive Android App

This repository is the public Android distribution home for SteadyDrive, the participant-facing app
used in the Encouraging Good Driving study.

## What Lives Here

- The standalone Android Studio / Gradle project for the SteadyDrive app
- The GitHub Actions workflow that builds signed release APKs
- Public GitHub Releases that host installable APK files for participants

The researcher portal, Supabase schema, mobile API routes, and Box upload integration stay in the
private `EncouragingGoodDriving` repository.

## Participant Distribution Flow

Participants should usually install from the study install page on the Vercel-hosted portal, not by
browsing this repository directly. The install page can point to the latest public GitHub Release
asset from this repository.

## Local Android Setup

1. Open this repository in Android Studio.
2. Create `local.properties` from `local.properties.example` and set `sdk.dir` to your Android SDK.
3. Confirm `PORTAL_BASE_URL` in `gradle.properties` points at the deployed portal.
4. Build and install the app on an Android device.

## Release Setup

Add these GitHub repository secrets before running the release workflow:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

The workflow publishes:

- `steady-drive.apk`
- `steady-drive-<version>.apk`

## Creating A Release

Run the `Android Release` workflow in GitHub Actions and provide:

- `release_tag`
- `release_name`
- `version_name`
- `version_code`
- `prerelease`

`version_code` must increase with every installable Android update.

## Portal Integration

In the Vercel project for the admin portal, set:

- `ANDROID_GITHUB_REPOSITORY=AndrewUSF/EncouragingGoodDrivingApp`
- `ANDROID_GITHUB_APK_ASSET_NAME=steady-drive.apk`
- `ANDROID_INSTALL_SUPPORT_EMAIL=<study contact email>`

That lets the portal install page generate a stable latest-download link for this public repository.
