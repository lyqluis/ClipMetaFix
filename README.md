# ClipMetaFix

解决小米澎湃OS相册剪辑视频后丢失拍摄元数据（GPS、拍摄时间）的本地工具。

- 包名 `com.clipmeta.fix`
- 原理：字节级拷贝原片 `mvhd/tkhd/mdhd` 时间、`udta©xyz`、`moov.meta` 到剪辑版，丢弃 `mcvr` 缩略图，流式重写 `mdat`。
- 纯本地、无网络、无第三方依赖。

## 工程

```
app/          UI + 流程编排 (Compose, minSdk 26)
mp4engine/    纯Kotlin MP4 解析重写引擎 (可单测)
.github/workflows  CI: push main -> debug APK, tag v* -> release + GitHub Release
```

## 使用

1. 选择原片 A：默认走**相册直选**（可读 GPS；备选“文件方式”，直达 DCIM/Camera）。选完显示时间/GPS/机型防呆，务必肉眼确认没选错。
2. 选择剪辑版 B：照片选择器或文件方式（B 不需要 GPS）。
3. 执行修复：优先原位更新 B（并改名为 `<原片基名>_cutfixed.mp4`），被拒则在 DCIM/Camera 新建同名条目。
4. （可选）修复成功后删除原文件：二次确认后执行；原位更新时只删 A（B 即成果，保留），新建条目时删 A + 旧 B。

## 权限与 GPS 排查

- 首次选 A 时会申请**位置权限**（GPS 解脱敏用），删文件时还会申请**媒体库访问**（允许全部效果最好）。
- 若 A 显示 GPS 为空：先看文件信息下方的诊断小字（`©xyz:…/perm:…/src:…`），确认权限已给；A 请用相册直选或文件方式重选（照片选择器通道注定无 GPS）。
- 位置权限与“照片和视频”是两个独立开关，都要允许。

## CI

- `build-debug.yml`: Gradle 8.10 + JDK 17 + Android SDK, `./gradlew assembleDebug` + `:mp4engine:test`
- `release.yml`: tag `v*` 触发，keystore 从 Secrets 还原 (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`)

## 验证

等价于 `exiftool -TagsFromFile A.mp4 "-all:all" B.mp4` 的语义，但去除 `mcvr`。
