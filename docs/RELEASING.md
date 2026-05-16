# Releasing Bluetrack

Tagging `vX.Y.Z` triggers `.github/workflows/release.yml`, which
builds a release APK + AAB, signs them if the keystore secret is
present, and publishes a GitHub Release with notes pulled from
`CHANGELOG.md`. This document describes the one-time keystore setup
and the per-release commands.

## One-time keystore setup

The signing keystore lives only in GitHub Actions secrets — never in
the repository, never in the laptop's working tree under version
control. To prepare the keystore:

1. Generate the keystore locally with `keytool` (JDK ships it):

   ```bash
   keytool -genkeypair \
     -keystore $HOME/bluetrack-release.jks \
     -alias bluetrack \
     -keyalg RSA -keysize 4096 -validity 36500 \
     -storetype JKS
   ```

   `keytool` prompts for the keystore password, the key password, and
   the certificate fields. Pick high-entropy passwords; record them
   somewhere only you can read. Lose the keystore or the passwords
   and you cannot ship upgrades — the Play Store identifies an app
   by its signing certificate.

2. Back the keystore up off-machine. The simplest approach: encrypt
   `bluetrack-release.jks` with `age` and store the ciphertext in a
   private location (USB key, password manager, encrypted cloud).

3. Base64-encode the keystore for the GitHub secret:

   ```bash
   base64 -i $HOME/bluetrack-release.jks | pbcopy
   ```

4. Add four secrets to the `aiwaki/bluetrack` repository (Settings →
   Secrets and variables → Actions → Repository secrets):

   | Secret                       | Value                                                    |
   | ---------------------------- | -------------------------------------------------------- |
   | `ANDROID_KEYSTORE_BASE64`    | base64 of `bluetrack-release.jks`                        |
   | `ANDROID_KEYSTORE_PASSWORD`  | keystore password (the one `keytool` asked for first)    |
   | `ANDROID_KEY_ALIAS`          | `bluetrack` (whatever was passed to `-alias`)            |
   | `ANDROID_KEY_PASSWORD`       | key password (typically the same as the keystore password) |

   `app/build.gradle.kts` reads them out of environment variables at
   build time; absent secrets simply produce an unsigned APK, which is
   useful for testing the workflow without leaking signing material.

## Cutting a release

1. Pick the next version. Bluetrack uses semver: protocol changes
   bump `MAJOR`, additive features bump `MINOR`, bugfix-only PRs bump
   `PATCH`. The next planned release is `v3.0.0` (everything since
   `v2.0.0` has been protocol-shaping).

2. Update `android/app/build.gradle.kts`:

   ```kotlin
   defaultConfig {
       versionCode = 3        // monotonic counter, bump per release
       versionName = "3.0.0"  // semver
   }
   ```

3. Update `CHANGELOG.md`:

   - Rename the `## Unreleased` section to `## 3.0.0 — YYYY-MM-DD
     (versionCode 3)`.
   - Add an empty `## Unreleased` placeholder above it so the next
     batch of PRs has somewhere to go.

4. Open a "Release 3.0.0" PR with the version bump + CHANGELOG move.
   Merge it once Android CI and Host CI are green.

5. From `main`, after the release PR lands:

   ```bash
   git checkout main && git pull --ff-only
   git tag -a v3.0.0 -m "Release 3.0.0"
   git push origin v3.0.0
   ```

6. The release workflow fires on the tag push:

   - Decodes the keystore from `ANDROID_KEYSTORE_BASE64` (or skips
     signing if the secret is absent).
   - Runs `:app:assembleRelease :app:bundleRelease` with the signing
     env vars threaded in.
   - Copies the APK + AAB into a release-artifacts directory.
   - Extracts the `## 3.0.0 …` section out of `CHANGELOG.md` for the
     release body.
   - Publishes a GitHub Release at `v3.0.0` with the artifacts
     attached.

7. Verify the Release page:

   - `app-release.apk` (or `app-release-unsigned.apk` if the secret
     was absent) is attached.
   - `app-release.aab` is attached.
   - Body contains the changelog section, not the fallback blurb.

If anything failed:

- A workflow with the wrong keystore password fails at
  `assembleRelease` with a clear error. Update the secret and re-run
  the workflow via the Actions tab; the tag stays untouched.
- If the wrong CHANGELOG section was extracted, hand-edit the GitHub
  Release body — the workflow does not re-publish on edits.
- To re-run for an existing tag without re-tagging, use the manual
  `workflow_dispatch` entry on the Actions tab and pass the tag
  string.

## Rolling back

GitHub Releases can be deleted from the UI; the tag stays. To pull a
release fully:

```bash
gh release delete v3.0.0 --yes
git push --delete origin v3.0.0
git tag -d v3.0.0
```

The signing key cannot be rolled back — once shipped, every future
release with the same `applicationId` must be signed with the same
keystore. If the keystore is compromised, the only path is a new
`applicationId` (effectively a new app on the Play Store).

## Pre-release / RC builds

A tag with a hyphen (`v3.0.0-rc1`, `v3.0.0-beta`, …) is marked as a
pre-release on GitHub automatically (the workflow honours the `-`
substring). Pre-releases use the same workflow; they are not promoted
to the Play track.
