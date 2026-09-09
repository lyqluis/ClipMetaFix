# AGENTS.md — ClipMetaFix

## Commands (exact)
- Wrapper is missing: `gradle/wrapper/gradle-wrapper.jar` not committed. CI runs `if [ ! -f gradle/wrapper/gradle-wrapper.jar ]; then gradle wrapper --gradle-version 8.10; fi` before `./gradlew`. Use `gradle/actions/setup-gradle@v4` with `gradle-version: 8.10` + `actions/setup-java@v4 temurin 17` + `android-actions/setup-android@v3`; do not assume `./gradlew` works locally.
- Debug APK + tests: `./gradlew assembleDebug --stacktrace` → `app/build/outputs/apk/debug/*.apk`; `./gradlew :mp4engine:test --stacktrace` (no SDK needed)
- Release APK: `echo "$KEYSTORE_BASE64" | base64 -d > app/release.keystore && ./gradlew assembleRelease --stacktrace` → `app/build/outputs/apk/release/*.apk`; secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`
- Single module test: `./gradlew :mp4engine:test --stacktrace`; do not add `npm`/`pip` — pure Gradle/Kotlin project

## Structure
- `settings.gradle.kts` — `FAIL_ON_PROJECT_REPOS`, catalog `gradle/libs.versions.toml`; do not add `repositories {}` in subprojects
- `:app` — Android Compose app `com.clipmeta.fix` (`compileSdk 34`, `minSdk 26`, `targetSdk 34`), depends `project(":mp4engine")`
- `:mp4engine` — pure JVM `kotlin.jvm` + `jvmToolchain(17)`, only `junit:junit:4.13.2`; no Android deps
- `.github/workflows` — only `build-debug.yml` (push `main` + PR) and `release.yml` (tag `v*`)

## Toolchain (do not upgrade arbitrarily)
- `agp 8.5.2`, `kotlin 1.9.22`, `kotlinCompilerExtensionVersion 1.5.8`, `composeBom 2024.04.01`, `Gradle 8.10`, `JDK 17`, `org.gradle.jvmargs=-Xmx2048m`

## MP4 Quirks (most errors come from here)
- `meta` has 4B version+flags before `hdlr`/`keys`/`ilst`; not a plain container
- `tkhd` flags `00 00 07`, not `00 00 00`; locate by type+version, not offset
- `moov` may be front or back; child order not guaranteed — search by type
- `©xyz` compare by bytes `0xA9 78 79 7A`, not string
- Box header: `4B BE size + 4B type`; `size==1` → 8B largesize, `size==0` → EOF
- Never load `mdat` — stream via `FileChannel` 8192 buf (OOM otherwise)
- Patch is byte-copy: `mvhd/tkhd/mdhd` creation+modification (8B v0 / 16B v1), `udta` filtered (keep `©xyz`, drop `mcvr`), `moov.meta` full replace; `ftyp`/`mdat`/stbl untouched; discard `mcvr` (~0.5 MB)

## Storage/UI
- `AndroidManifest.xml` requests no permissions; flow uses `PickVisualMedia VideoOnly` + `ContentResolver.openFileDescriptor` + `MediaStore` with `RELATIVE_PATH DCIM/Camera` and `IS_PENDING 1→0` (Q+)
- UI single `MainActivity` Compose `lightColorScheme()`: pick A (show `MediaMetadataRetriever` DATE/LOCATION for de-fool) → pick B → repair on `Dispatchers.IO` → `tryOverwriteOriginal` else `insertAsNewEntry *_fixed.mp4`; `isMinifyEnabled false` despite `-keep class com.clipmeta.fix.mp4.**`

## Testing
- `mp4engine/src/test/resources` and `docs/` are empty; do not claim real fixtures exist. Use `Mp4TestHelper.buildSampleA/B` synthetic `ftyp(16 mp42)+mdat+moov(mvhd+udta©xyz+mcvr+meta 2581B+trak×2)` for 8 tests: both moov positions, legal tree, dates == A, udta has ©xyz no mcvr, meta == A, mdat unchanged, largesize
- Ground truth: `exiftool -TagsFromFile A.mp4 "-all:all" B.mp4`; samples `VID_20260908_192341` / `212302` spec in `plan.md`

## Sources of Truth
- Executable: `gradle/libs.versions.toml`, `app/build.gradle.kts`, `mp4engine/src/main/kotlin/com/clipmeta/fix/mp4/Box.kt` / `Mp4Parser.kt` / `Mp4Patcher.kt`, `.github/workflows/*.yml`
- Prose spec: `plan.md`; no existing `CLAUDE.md`/`.cursor`/`opencode.json` to preserve
