# Permanent Android release signing

BizCard Scanner beta/release APKs must use one permanent signing key so Android can update an existing installation in place.

## Required GitHub Actions repository secrets

Configure these four secrets in the repository settings:

- `BIZCARD_KEYSTORE_B64` — base64 of the PKCS12 keystore file, encoded on one line.
- `BIZCARD_KEYSTORE_PASSWORD` — keystore password.
- `BIZCARD_KEY_ALIAS` — key alias.
- `BIZCARD_KEY_PASSWORD` — private-key password.

Never commit the keystore, passwords, or the base64 value to the repository.

## Publishing

The Android CI workflow always builds/tests the debug APK. On `main`, the fixed-signed beta release job runs after the build. If the four signing secrets are unavailable it exits safely without publishing a release.

After the secrets are configured, open **Actions → Android CI → Run workflow** on `main`. The workflow will:

1. run unit tests, lint, and the debug build;
2. decode the signing keystore only inside the temporary GitHub runner;
3. build the release APK;
4. verify the APK signing certificate;
5. upload the signed APK as a workflow artifact; and
6. create the matching prerelease tag/release if it does not already exist.

## Key custody

Keep the permanent keystore in at least two private backup locations. Losing the key prevents future APKs from updating installations signed with that key. Replacing the key requires users to uninstall the old app before installing the newly signed build.
