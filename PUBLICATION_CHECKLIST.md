# Before making this repository public

This repository is still private. Complete these checks before changing its visibility:

- Confirm reuse permissions and any required notices for ported components, especially mild-lyrics, `@kawarp/core`, the cubic-spline and spring code, and the lrcmux adapter. The local mild-lyrics checkout has no tracked license file. The project license is AGPLv3, following Spicy Lyrics, but that alone does not establish permission for every upstream port.
- Review the entire reachable Git history for committed credentials, private data, and full copyrighted lyric fixtures. The targeted scan found no obvious secret pattern, but it was not a comprehensive audit.
- Confirm the intended public default branch. GitHub currently defaults to `main`; active development is on `dev`.
- Decide how to handle the repository URL. The app's public name is Spicy Player, but `TheX24/Spicy-Player` is already the original project's repository, so this repository still uses the `spicy-player-next` slug.
- Install and test the release-signed APK on a device. Confirm notification-access onboarding and lyrics behavior, and document known app compatibility limits.
