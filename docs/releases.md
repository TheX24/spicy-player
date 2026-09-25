# Releases and changelog

Use GitHub Releases as the changelog for this repo. For now, every release is a **debug APK**; do not build or attach a release-variant APK or set up signing for one. Mark these GitHub releases as pre-releases so the download is clearly a development build.

When asked to make a release:

1. Bump `versionName` and increment `versionCode` in `app/build.gradle.kts`. Use a matching `v<versionName>` Git tag (for example, `v0.2.0`). Never reuse a version code or tag.
2. Write release notes describing user-visible additions, fixes, and important limitations since the previous release. Use clear headings and concise bullets; do not paste a raw commit log. The GitHub release notes are the changelog.
3. Ensure the local `.env` supplies a publishable `sl_pk_` client key, then run `.\gradlew.bat assembleDebug testDebugUnitTest lintDebug`. Check that `app/build/outputs/apk/debug/app-debug.apk` exists and installs if a device is available. Never put the key or `.env` in Git or release notes.
4. Commit the version bump and any release-related changes. Push the commit and tag, then create a GitHub pre-release from that tag with the prepared notes and `app-debug.apk` attached. Verify the published tag, notes, and downloadable APK on GitHub. Do not create a release just because code changed; do it when requested.

Do not publish an APK built from uncommitted changes or a different commit than the release tag. The debug APK is signed with the Android debug key and is for testing, not store distribution.
