# Release Keystore

This directory contains the **release signing keystore** for NasGameHub Android client.

## Files
- `nasgame-release.p12` — PKCS12 keystore (Android-compatible)
- `nasgame-release.p12.b64` — base64-encoded, used by GitHub Actions via `base64 -d`
- `FINGERPRINT.txt` — SHA-256 fingerprint

## Credentials
- **Alias**: `nasgame`
- **Password**: `nasgamehub2026` (also stored in `signingConfigs.release` in build.gradle.kts)
- **Validity**: ~25 years from 2026-09-30 (9125 days)
- **Algorithm**: RSA 2048 + SHA-256
- **Subject**: `CN=NasGameHub, O=NasGameHub, C=CN`

## Fingerprint (SHA-256)
```
96:55:EC:6A:F0:BE:DA:00:98:87:DD:57:18:5E:0A:3A:B8:DC:F7:71:0C:5E:EB:59:BB:A3:3A:0A:95:28:6B:10
```

## Why committed to repo?

This is a **personal/open-source hobby project**. Committing the keystore makes
CI builds reproducible (same signature = upgrade installs work).

For Play Store distribution, you'd want to **regenerate** this with a secure offline
keystore + use GitHub Secrets. But for sideloading (e.g. via this repo's Releases page),
this is fine — the worst case of a leaked keystore is someone can publish fake updates
to your users, which you can counter by bumping `versionCode` + pushing a new keystore.

## To rotate (if compromised)

```bash
cd client-android/keys
keytool -genkey -v -keystore nasgame-release.p12 -alias nasgame \
  -keyalg RSA -keysize 2048 -validity 9125 \
  -storepass nasgamehub2026 -keypass nasgamehub2026 \
  -dname "CN=NasGameHub, O=NasGameHub, C=CN"
base64 -w 0 nasgame-release.p12 > nasgame-release.p12.b64
# Then update FINGERPRINT.txt and commit
```

To verify the published APK matches this keystore:
```bash
apksigner verify --print-certs app-release.apk
# Should match SHA-256 fingerprint above
```
