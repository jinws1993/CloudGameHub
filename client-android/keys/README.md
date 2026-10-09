# Release Keystore

This directory contains the **release signing keystore** for CloudGameHub Android client.

## ⚠️ 项目改名了, 但 keystore 没改

CloudGameHub v1.3.0 之前这个项目叫 CloudGameHub。包名从 `com.nasgame` 改成了
`com.cloudgamehub`, **但签名密钥没动** —— 因为 PKCS12 的 alias 和证书主体是烧在
文件里的, 改了签名身份就变了, 所有装过旧包的用户都得先卸载才能装新的。

所以现在的实际情况:

| 项 | 值 |
|----|-----|
| 文件名 | `nasgame-release.p12` (未改) |
| Alias | `nasgame` (未改) |
| 密码 | `nasgamehub2026` (未改) |
| 证书主体 | `CN=CloudGameHub, O=CloudGameHub, C=CN` (未改) |
| **App 包名** | `com.cloudgamehub` (**已改**) |

`build.gradle.kts` 里的 `signingConfigs.release` 也保持原样指向旧 keystore。

想彻底改名的话, 按下面「To rotate」重新生成一份, 然后同步改
`build.gradle.kts` 的 `storeFile` / `keyAlias` / 密码 —— 但那样所有老用户都
得重新安装, 自己权衡。

## Files
- `nasgame-release.p12` — PKCS12 keystore (Android-compatible)
- `FINGERPRINT.txt` — SHA-256 fingerprint

## Credentials
- **Alias**: `nasgame`
- **Password**: `nasgamehub2026` (also stored in `signingConfigs.release` in build.gradle.kts)
- **Validity**: ~25 years from 2026-09-30 (9125 days)
- **Algorithm**: RSA 2048 + SHA-256
- **Subject**: `CN=CloudGameHub, O=CloudGameHub, C=CN`

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

## To rotate (改名字 / 泄露了都走这个)

```bash
cd client-android/keys
keytool -genkey -v -keystore cloudgamehub-release.p12 -alias cloudgamehub \
  -keyalg RSA -keysize 2048 -validity 9125 \
  -storepass cloudgamehub2026 -keypass cloudgamehub2026 \
  -dname "CN=CloudGameHub, O=CloudGameHub, C=CN"
# 然后改 app/build.gradle.kts 的 storeFile / keyAlias / 两个密码
# 最后更新 FINGERPRINT.txt 并提交
```

To verify the published APK matches this keystore:
```bash
apksigner verify --print-certs app-release.apk
# Should match SHA-256 fingerprint above
```
