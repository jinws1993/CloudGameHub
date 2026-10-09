# Release Keystore

This directory contains the **release signing keystore** for CloudGameHub Android client.

## Credentials

| 项 | 值 |
|----|-----|
| 文件 | `cloudgamehub-release.p12` (PKCS12) |
| Alias | `cloudgamehub` |
| 密码 | `cloudgamehub2026` (同时写在 `app/build.gradle.kts` 的 `signingConfigs.release`) |
| 算法 | RSA 2048 + SHA-256 |
| 有效期 | 2026-10-09 起 9125 天 (~25 年) |
| 主体 | `CN=CloudGameHub, O=CloudGameHub, C=CN` |
| SHA-256 | `D8:BC:89:BD:E8:25:66:BE:07:EE:4F:3B:8B:34:9F:DC:DF:51:CA:30:86:94:0F:DF:0B:72:EB:0F:49:07:9C:04` |

完整指纹见 `FINGERPRINT.txt`。

## 校验

```bash
# 密钥本身能不能打开
keytool -list -v -keystore cloudgamehub-release.p12 -alias cloudgamehub
# 出来的指纹应该和 FINGERPRINT.txt 一致

# 构建产物对不对得上
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
# SHA-256 certificate digest 应该和上面一致
```

## 为什么仓库里放着 keystore

这是个人/开源项目。把 keystore 提交进仓库能让 CI 每次构建出**同样签名**的包,
用户下载后可以直接覆盖安装, 不用先卸载。

真要上 Google Play 的话, 应该换成离线保管的密钥 + GitHub Secrets, 不入库。
但对于侧载 (比如本仓库 Releases 页分发) 够用了 —— 万一泄露, 最坏结果是
有人能发假更新, 应对办法是抬 `versionCode` + 换一把新密钥。

## 历史

| 时间 | 变化 |
|------|------|
| 2026-09-30 | 初次生成 (`CN=NasGameHub`), 指纹 `96:55:EC:...` |
| 2026-10-09 | 项目改名为 CloudGameHub, 重新生成 (`CN=CloudGameHub`), 指纹 `D8:BC:89:...` |

**换密钥 = 换签名身份。** 装过旧包 (`com.nasgame`) 的用户必须先卸载,
因为包名和签名都变了, Android 不允许用不同签名覆盖安装。

老密钥在 git 历史里, 需要的话可以翻出来:
`git show <old-commit>:client-android/keys/nasgame-release.p12`

## 轮换

```bash
cd client-android/keys
keytool -genkey -v -keystore cloudgamehub-release.p12 -alias cloudgamehub \
  -keyalg RSA -keysize 2048 -validity 9125 \
  -storepass cloudgamehub2026 -keypass cloudgamehub2026 \
  -dname "CN=CloudGameHub, O=CloudGameHub, C=CN"

# 换完记得:
# 1. 更新 FINGERPRINT.txt
# 2. 同步 app/build.gradle.kts 的 alias/密码 (如果改了)
# 3. 抬 versionCode
# 4. 告诉用户要卸载重装
```
