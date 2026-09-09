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

1. 选择原片 A（显示时间/GPS防呆）
2. 选择剪辑版 B
3. 执行修复 -> 原位覆盖 B，失败则新建条目 `*_fixed.mp4` 于 DCIM/Camera

## CI

- `build-debug.yml`: Gradle 8.10 + JDK 17 + Android SDK, `./gradlew assembleDebug` + `:mp4engine:test`
- `release.yml`: tag `v*` 触发，keystore 从 Secrets 还原 (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`)

## 验证

等价于 `exiftool -TagsFromFile A.mp4 "-all:all" B.mp4` 的语义，但去除 `mcvr`。
