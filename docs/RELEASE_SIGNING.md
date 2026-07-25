# Monster VPN release signing

Android accepts an update only when the new APK is signed with the same key as the installed APK. The release keystore is therefore a permanent project asset.

## One-command setup

From the repository root:

```bash
./scripts/setup-release-signing.sh
```

The assistant:

1. generates a 4096-bit RSA JKS release key;
2. exports the public certificate and SHA-1/SHA-256 fingerprints;
3. optionally uploads the four required values to GitHub Actions using `gh secret set`;
4. never writes passwords into the repository.

Required GitHub Actions secrets:

- `APP_KEYSTORE_BASE64`
- `APP_KEYSTORE_PASSWORD`
- `APP_KEYSTORE_ALIAS`
- `APP_KEY_PASSWORD`

## Backups

Keep at least two encrypted backups in different locations. Back up:

- `monster-release.jks`;
- the keystore password;
- the key alias;
- the key password;
- `monster-release-fingerprints.txt`.

Do not regenerate the key after publishing the first signed APK. A newly generated key cannot update installations signed by the previous key.

## Build and verification

The GitHub workflow restores the keystore only inside the temporary runner directory, builds the signed universal APK, verifies the APK signature with `apksigner`, and publishes its SHA-256 checksum and signing-certificate fingerprints as build artifacts.

## Publishing a GitHub Release with one command

After signing secrets are configured, run this from the repository root:

```bash
./release.sh 2.2.7
```

It checks Git and GitHub CLI, increments `versionCode`, updates `versionName`,
creates an annotated `v2.2.7` tag, pushes the commit and tag atomically, starts
the signed workflow, waits for completion, and prints the GitHub Release URL.

Optional release notes can be used as the annotated tag message:

```bash
./release.sh 2.2.7 release-notes.md
```

The workflow is manual-only to prevent the branch push and tag push from
building the same release twice.
