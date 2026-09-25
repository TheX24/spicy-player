# Before making this repository public

This repository is still private. Complete these checks before changing its visibility:

- Identify the licenses and required notices for every ported component, especially Spicy Lyrics, mild-lyrics, `@kawarp/core`, the cubic-spline and spring code, and the lrcmux adapter. The local Spicy Lyrics reference checkout has an AGPL-3.0 license file; the local mild-lyrics checkout has no tracked license file. Confirm the precise upstream versions and permissions, add source links and required license text, and resolve any incompatible terms before publishing.
- Identify each bundled font in `app/src/main/res/font/`, record its source and license, and include required notices. Do not infer a font's license from its filename.
- Review the entire reachable Git history for committed credentials, private data, and full copyrighted lyric fixtures. The targeted scan found no obvious secret pattern, but it was not a comprehensive audit.
- Confirm the intended public default branch. GitHub currently defaults to `main`; active development is on `dev`.
- Decide how to handle the repository URL. The app's public name is Spicy Player, but `TheX24/Spicy-Player` is already the original project's repository, so this repository still uses the `spicy-player-next` slug.
- Install and test the release-signed APK on a device. Confirm notification-access onboarding and lyrics behavior, and document known app compatibility limits.
- Decide the project's own license after upstream obligations are known. Do not add a license that purports to relicense third-party code.
