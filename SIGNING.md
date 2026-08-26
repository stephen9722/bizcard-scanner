# BizCard Scanner beta signing

Beta APKs must use one fixed private signing key so Android can install later versions as in-place updates.

The private key is **never committed to this repository**. GitHub Actions reads it from four repository secrets:

- `BIZCARD_KEYSTORE_B64` — base64 of the PKCS#12 keystore
- `BIZCARD_KEYSTORE_PASSWORD` — keystore password
- `BIZCARD_KEY_ALIAS` — signing alias
- `BIZCARD_KEY_PASSWORD` — private-key password

When all four secrets are available, the `release-beta` CI job decodes the keystore into the runner's temporary directory, builds `assembleRelease`, verifies the APK certificate with `apksigner`, uploads the signed APK artifact, and publishes the versioned beta Release. The temporary keystore disappears with the runner.

If the secrets are missing, normal debug CI still runs and the signed release job reports that publishing was skipped instead of falling back to an ephemeral debug key.

## Important migration note

Versions `v0.1.0-beta` and `v0.1.1-beta` were debug-signed by ephemeral GitHub runners. The first fixed-signed build therefore cannot update those installations in place. Back up the existing cards to ZIP once, uninstall the old beta, install `v0.1.2-beta`, and restore the ZIP. From `v0.1.2-beta` onward, keep the same signing key and future beta APKs can update in place.
