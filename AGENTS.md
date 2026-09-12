# AGENTS.md — ClipMetaFix

## Commands (exact)
- No local toolchain in this env (`java`/`gradle` absent) — do not run builds locally; push to `main` and read CI logs. Get the `e: <file>:<line>` lines from the failed `:app:compileDebugKotlin` block, not the stacktrace tail.
- `gradle/wrapper/gradle-wrapper.jar` is NOT committed (only `gradle-wrapper.properties`, dist Gradle 8.10). CI debug (`build-debug.yml`: push `main` + PR) runs `./gradlew assembleDebug` + `./gradlew :mp4engine:test` with `setup-java temurin 17` + `setup-android` + `setup-gradle` (no version pin). CI release (`release.yml`, tag `v*`) uses **system** `gradle assembleRelease` with `setup-gradle gradle-version: 8.10`, keystore restored via `echo "$KEYSTORE_BASE64" | base64 -d > app/release.keystore`; secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.
- Artifacts: `app/build/outputs/apk/debug/*.apk`, `app/build/outputs/apk/release/*.apk`; test results `mp4engine/build/test-results/`.
- Do not add `npm`/`pip` — pure Gradle/Kotlin project.

## Structure
- `settings.gradle.kts` — `FAIL_ON_PROJECT_REPOS`, catalog `gradle/libs.versions.toml`; do not add `repositories {}` in subprojects.
- `:app` — Android Compose app `com.clipmeta.fix` (`compileSdk 34`, `minSdk 26`, `targetSdk 34`), depends `project(":mp4engine")`. Single `MainActivity`, `lightColorScheme()`; `isMinifyEnabled false` despite `-keep class com.clipmeta.fix.mp4.**`.
- `:mp4engine` — pure JVM `kotlin.jvm` + `jvmToolchain(17)`, only `junit:junit:4.13.2`; no Android deps. Files: `Box.kt` / `Mp4Parser.kt` / `Mp4Patcher.kt` / `XyzLocation.kt`.
- `app/.../util/`: `PickVideoViaGallery` (A primary) / `PickVideoWithLocation` (photo-picker + location extra) / `PickVideoViaFiles` (OpenDocument seeded to DCIM/Camera) / `SharedVideoReceiver` (SEND/SEND_MULTIPLE extract + GPS>duration classify) / `UriRequireOriginal` / `MediaInfoHelper` / `StorageHelper` / `Mp4Repairer` / `MediaDeleter`.
- Share entry: `singleTop` + `SEND`/`SEND_MULTIPLE` `video/*` filters; Activity `sharedInbox` consumed once via `takeInbox` in `LaunchedEffect`; `handleSharedUris` reuses `onUriPicked`-grade `queryWithGps` then slots directly; swap button (`swapAB`) visible when both set, clears `lastResult`.
- `.github/workflows` — only `build-debug.yml` and `release.yml` (see Commands for their exact commands).

## Toolchain (do not upgrade arbitrarily)
- `agp 8.5.2`, `kotlin 1.9.22`, `kotlinCompilerExtensionVersion 1.5.8`, `composeBom 2024.04.01`, `activityCompose 1.8.2` (its `PickVisualMedia` has no location field — hence the raw-intent pickers), `Gradle 8.10`, `JDK 17`, `org.gradle.jvmargs=-Xmx2048m`.

## Pick channels + location redaction (hard-earned, read before touching)
- Manifest declares `ACCESS_MEDIA_LOCATION` + `READ_MEDIA_VIDEO` (+`READ_EXTERNAL_STORAGE` maxSdk 32, `WRITE_EXTERNAL_STORAGE` maxSdk 28) + `queries` for `PICK_IMAGES` and `PICK`. Deletion needs broad media read (reverse-lookup by size/name); single-file picker grants are NOT enough — gate in `onDeleteClicked`, auto-continue after grant.
- A primary = `ACTION_PICK` on `MediaStore.Video` (`PickVideoViaGallery`): returns a real `content://media/external/...` URI; GPS readable via `ACCESS_MEDIA_LOCATION` + `MediaStore.setRequireOriginal`.
- PhotoPicker URIs (`content://media/picker/...`) are GPS-dead: `setRequireOriginal` throws `UnsupportedOperationException`, raw stream is redacted even with permission + unchecked strip-box. Use only for B / fallback. Detect via `UriRequireOriginal.isPickerUri` (authority `media` + path contains `/picker/`) and NEVER wrap them. Numeric tail alone ≠ standard item: picker tails are numeric too — `isStandardMediaItem` must exclude picker paths, and pre-validate the batch so one bad URI can't fail the whole `createDeleteRequest`.
- Read path (`MediaInfoHelper.query` two-phase): phase 1 raw URI for duration/date/size (never regress this); phase 2 `requireOriginal` URI for LOCATION only, failure discarded. `StorageHelper.copyUriToTempFile` tries wrapped first, falls back to raw, and reports which stream won (`CopyResult.source`) + wrapped exception (`wrappedError`) into the `©xyz:有/无/meta/orig/perm/auth/src/werr` debug line — ask the user to paste it when GPS is missing.
- Repair: `Mp4Repairer` asserts `©xyz` present in A (else loud failure, likely system redaction) → `Mp4Patcher.patch` → `tryOverwriteOriginal`, fallback `insertAsNewEntry` in `DCIM/Camera` (`IS_PENDING 1→0`, Q+). Output name in both paths: `<A基名>_cutfixed.mp4` (overwrite also renames B via `DISPLAY_NAME` update; rename denial is non-fatal).
- Delete-after-success: manual button + `AlertDialog`; order direct-first then one batch `createDeleteRequest` (R+) / sender (any API level); Document URIs via `deleteDocument` (attempted even on lookup miss); picker URIs resolved like any other via size/name lookup, manual only on miss. Reverse-lookup (`findMediaStoreItem`, all volumes): exact `SIZE` → `DURATION ±2s` tiebreak → `DISPLAY_NAME`, single-match only, never guess. Failure status carries per-URI `auth/tail` shapes + resolve outcome + `mperm`. Safety rule: `overwrite` → delete A only (B IS the result); `insert` → delete A + old B.

## MP4 Quirks (most engine errors come from here)
- `meta` has 4B version+flags before `hdlr`/`keys`/`ilst`; not a plain container.
- `tkhd` flags `00 00 07`, not `00 00 00`; locate by type+version, not offset.
- `moov` may be front or back; child order not guaranteed — search by type.
- `©xyz` compare by bytes `0xA9 78 79 7A`, not string.
- Box header: `4B BE size + 4B type`; `size==1` → 8B largesize, `size==0` → EOF.
- Never load `mdat` — stream via `FileChannel` 8192 buf (OOM otherwise).
- Patch is byte-copy: `mvhd/tkhd/mdhd` creation+modification (8B v0 / 16B v1), `udta` filtered (keep `©xyz`, drop `mcvr`), `moov.meta` full replace; `ftyp`/`mdat`/stbl untouched; discard `mcvr` (~0.5 MB).

## Compose gotcha
- Local `fun`s inside `@Composable` must be declared BEFORE use — forward reference is `Unresolved reference` (bit us in `b457404`). Keep order: state → `onDeleteDone`/`onUriPicked` → launchers → `launchWithFallback`/`launchGalleryForA`/`runDeleteDirect`/`onDeleteClicked` → `permissionLauncher` → `onPickClicked` → UI.
- Kotlin block comments NEST — never write `/*` inside `/** */` (a `video/*` MIME in a header swallowed the whole file: `Unclosed comment` at EOF + cascading `Unresolved reference`s, fixed in `0a9ebf4`). Put MIME types in `//` comments or rephrase.

## Testing
- 12 tests, all synthetic (`Mp4TestHelper.buildSampleA/B`: `ftyp(16 mp42)+mdat+moov(mvhd+udta©xyz+mcvr+meta+trak×2)`). `Mp4PatcherTest` 8: parser locates boxes, moov front+back, legal tree, dates == A, udta ©xyz-no-mcvr, meta == A, mdat unchanged, largesize/size-zero. `Mp4LocationTest` 4: ©xyz parse/skip-mcvr/corrupt.
- `mp4engine/src/test/resources` and `docs/` are empty; do not claim real fixtures exist.
- Ground truth: `exiftool -TagsFromFile A.mp4 "-all:all" B.mp4`; samples `VID_20260908_192341` / `212302` spec in `plan.md`.

## Sources of Truth
- Executable: `gradle/libs.versions.toml`, `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `mp4engine/src/main/.../mp4/*.kt`, `app/.../util/*.kt`, `.github/workflows/*.yml`.
- Prose spec: `plan.md`; overview: `README.md`. No `CLAUDE.md`/`.cursor`/`opencode.json` to preserve.
