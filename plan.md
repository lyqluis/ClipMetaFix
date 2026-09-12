好的，我把从头脑风暴到 M0 收尾的全部调研结论、字节级规格、技术决策整合成一份完整的交接文档，新对话拿到它就能直接开工写代码。
计划文档已生成，下面是完整内容，直接复制喂给新对话即可。文档也已存为文件，方便你留存。

---

# MetaFix 项目完整开发计划（交接文档）

> 本文档包含全部前期调研结论与技术决策。阅读者无需任何前置对话即可直接开始编码。

## 1. 项目背景与目标

- **用途**：开发者自用 Android 工具，解决一个具体问题——小米澎湃OS 相册剪辑视频后，导出的新视频丢失全部拍摄元数据。
- **iOS 相册剪辑会保留原视频 GPS/拍摄时间，小米澎湃OS 不保留**，本 app 负责补回。
- **核心功能**：用户手动选择两个视频——原片 A 与剪辑版 B——把 A 的元数据写入 B，保存。
- **工作方式**：只在本地写代码，push 到 GitHub 后由 GitHub Actions 编译打包出 APK。
- 纯单机工具：无网络、无账号、无第三方依赖。

## 2. 实测环境（M0 调研结论，全部已验证）

| 项         | 值                                                           |
| ---------- | ------------------------------------------------------------ |
| 设备       | Xiaomi 机型（已脱敏）                                        |
| 系统       | 澎湃OS，Android 16                                           |
| 相机输出   | MP4（major brand mp42，兼容 isom/mp42），H.264 + AAC，双轨（视频+音频） |
| 样本原片   | VID_20260908_xxxxxx.mp4，~5MB，~5s，1280x720（旋转矩阵 90°，竖拍） |
| 样本剪辑版 | VID_20260908_xxxxxx.mp4，~2MB，~3s，720x1280（编辑器已把旋转烧入画面） |

### 2.1 澎湃OS 剪辑后丢失了什么（已用 exiftool 对比确认）

丢失：
- `udta` 中的 `©xyz`（GPS 坐标）
- `udta` 中的 `mcvr`（573KB 封面缩略图 JPEG）
- `moov.meta` 中的全部小米信息（AndroidMake/Model、XiaomiProductMarketname、AndroidCaptureFPS、XiaomiNormalVideo、XiaomiExifInfo），只剩 AndroidVersion 和 VideoFileType
- `mvhd` / `tkhd` / `mdhd` 中的创建/修改时间，全部被改为剪辑时刻

保留：视频流与音频流本身（重编码，参数变了但结构正常）。

### 2.2 小米相册读取来源验证（关键结论）

用 exiftool 修复剪辑版后传回手机，**小米相册正确显示原拍摄时间与 GPS 定位**。证明：
- 相册读的是**文件内部元数据**（mvhd 的日期 + udta 的 GPS），不是 MediaStore 数据库字段
- 因此 app **不需要更新 MediaStore 的 DATE_TAKEN**

验证用的修复命令（也是本项目的"答案卷"）：
```
exiftool -TagsFromFile VID_20260908_xxxxxx.mp4 "-all:all" VID_20260908_xxxxxx.mp4
```
app 的输出文件，dump 后应与该命令的结果**语义一致**（布局可以不同）。

### 2.3 产品决策：不复制封面缩略图

`mcvr` 封面图取自剪辑前首帧，可能包含已被剪掉的画面，且会让输出文件膨胀约 0.5MB。决定**丢弃 mcvr**，让相册基于 B 的真实首帧自动生成封面。

### 2.4 已排除的方案

- 不做自动配对（文件名规律不可靠，用户手动选 A/B 即可）
- 不引入 FFmpeg / 第三方 MP4 库（包体积与可控性考虑，自实现 box 解析）
- 不做字段级语义解析（日期换算、GPS 解码都不需要——字节级拷贝即可）
- 不复制 mvhd 等头部中除日期外的任何字段（时长、timescale 等必须保留 B 自己的）

## 3. MP4 结构规格（实测字节级参考）

### 3.1 顶层布局（两样本一致）

```
ftyp (16 bytes, major=mp42)
mdat (媒体数据，占绝大部分)
moov (在文件末尾 —— 非 faststart)
```

注意：实现时仍须支持 moov 前置的情况（通用解析），只是自家样本都是后置。moov 之后无其他数据。

### 3.2 moov 内部结构（注意：顺序非标准，必须按类型查找，不能按位置假设）

```
moov (579,005 bytes in 样本)
├── mvhd (100 bytes, version 0, flags=0x000000)
├── udta (573,426 bytes)
├── meta (2,581 bytes)
├── trak (视频轨)
└── trak (音频轨)
```

### 3.3 日期字段的精确布局（补丁目标）

mvhd（version 0）：
```
'mvhd' | version(1B)+flags(3B)=00 00 00 00 | creation_time(4B) | modification_time(4B) | ...
```
tkhd（version 0，**flags=00 00 00 07**，不能假设为 0）：creation/modification 同样是 version+flags 之后各 4 字节。
mdhd（version 0，24 bytes）：同上。

样本实测值：`e6 c5 a0 c3` = 3871711427（1904-01-01 纪元秒）= 2026-09-08 xx:xx:xx UTC（已脱敏）。

**关键简化**：不需要做任何时间换算。补丁值直接从 A 对应位置原样复制 4 字节即可。处理位置：A 的 mvhd 的 creation/modification → 写入 B 的 mvhd；A 的两个 trak 同理。

### 3.4 udta 子 box（筛选搬运的目标）

```
udta
├── ©xyz (30 bytes = 8B header + 4B (00 12 15 c7) + ASCII "+xx.xxxx+yyy.yyyy/" 已脱敏)
└── mcvr (约 500KB，FFD8 开头的 JPEG)  ← 丢弃
```

- 筛选规则：遍历 A 的 udta 子 box，**跳过类型为 `mcvr` 的，其余原样保留**（含 8 字节 header 整体拷贝，不解析 payload）。
- ©xyz 的 payload 不必解析（照抄即可），GPS 格式为 ASCII 坐标串。
- 新 udta 预计只有 ~38 字节。
- B（剪辑版）的 moov 中**没有 udta**，需要在 moov 内新建。

### 3.5 meta box（整段替换的目标）

- **注意经典坑：meta 开头有 4 字节 version+flags**（普通 box 没有），其后才是子 box。
- 结构：4B version/flags → hdlr(handler=mdta) → keys → ilst。
- 样本 A 的 meta 共 2,581 字节，含 8 个 key（com.android.version、com.android.manufacturer、com.android.model、com.xiaomi.product.marketname、com.android.capture.fps、com.video.file.type、com.xiaomi.normal_video、xiaomi.exifInfo.videoinfo）。
- 策略：A 的 meta 整段（含 header）原样替换 B 的 meta。B 的 meta 只有 AndroidVersion + VideoFileType，无保留价值。自包含、无需合并。

### 3.6 通用解析要求

- box header：4B big-endian size + 4B type；size==1 时后跟 8B largesize（64 位）；size==0 表示延伸到文件尾。
- type 含非 ASCII 字符：© = 0xA9，比较时用字节而非字符串。
- 流式处理：文件可能几百 MB，不得整体读入内存；mdat 直接通道拷贝。
- 重写算法：拷贝 B 中 moov 之前的所有字节 → 写入打过补丁的新 moov → 拷贝 B 中 moov 之后的字节（若有）。
- 新 moov 的大小变化只来自：新增小 udta（+~38B）、meta 替换（±约 2KB），需重算 moov 自身 size。

## 4. 处理规格（字段清单 v4，最终版）

| 位置                                                    | 动作                                              | 数据来源                                   |
| ------------------------------------------------------- | ------------------------------------------------- | ------------------------------------------ |
| moov.mvhd                                               | 补丁 creation_time + modification_time（各 4B）   | A 的 mvhd 原样字节                         |
| moov.trak[].tkhd                                        | 补丁 creation/modification                        | A 对应 trak 的原样字节（按 trak 顺序对应） |
| moov.trak[].mdia.mdhd                                   | 补丁 creation/modification                        | 同上                                       |
| moov.udta                                               | 新建：A 的 udta 子 box 中除 `mcvr` 外全部原样拷贝 | A                                          |
| moov.meta                                               | 整段替换（注意 4B version 头属于本 box）          | A                                          |
| ftyp / mdat / trak 的媒体结构（stbl/stsd/stco/co64 等） | **绝不修改**                                      | B 原样                                     |

**执行流程**：
1. 解析 A：读出 mvhd 的 8 字节（creation+modification）、每个 trak 的 tkhd/mdhd 各 8 字节、udta 子 box 列表（除 mcvr）、整个 meta 字节块。
2. 解析 B：建立 box 树，定位 moov 及其子 box。
3. 生成新 moov：以 B 的 moov 为基础，按上表 patch / 替换 / 插入，重算受影响 box 的 size。
4. 写临时文件（app 私有目录）：B 的 moov 前字节 + 新 moov + B 的 moov 后字节。
5. 验证临时文件：MediaMetadataRetriever 能打开、时长正确、能读到日期与 GPS。
6. 用验证过的临时文件替换 B（见 §6.3 的存储策略）。

## 5. 技术栈与工程结构（M1 落地现状）

- 语言：Kotlin；`minSdk 26`，`targetSdk/compileSdk 34`；`app`（Android Compose）+ `mp4engine` 模块（纯 Kotlin/JVM，无 Android 依赖，可独立单元测试）。
- 包名定稿 `com.clipmeta.fix`。
- 工具链 pin（勿随意升级）：`agp 8.5.2` / `kotlin 1.9.22` / `composeBom 2024.04.01` / `activityCompose 1.8.2` / `Gradle 8.10` / `JDK 17`。
- 无任何第三方依赖。
- 版本控制：GitHub **私有仓库**。
- CI（GitHub Actions）：
  - push 到 main → 构建 debug APK（`./gradlew assembleDebug` + `:mp4engine:test`）→ 上传 Artifact
  - 推送 tag `v*` → 构建 release APK（签名信息走 GitHub Secrets）→ 创建 GitHub Release
  - 签名：自用阶段 debug 签名即可；release 签名将 keystore base64 + 密码存 Secrets，workflow 中还原。
- 注意：`gradle/wrapper/gradle-wrapper.jar` 未入库；本机无工具链时只 push 看 CI，不在本地构建。

实际目录：
```
repo/
├── .github/workflows/
│   ├── build-debug.yml
│   └── release.yml
├── app/                  # UI 与流程编排（含 util/ 各通道与存储逻辑）
├── mp4engine/            # box 解析/重写引擎（纯 Kotlin + JUnit 测试）
├── plan.md / README.md / AGENTS.md
└── docs/                 # 空（真实样本与 dump 最终未入库，见 §7）
```

## 6. 应用层设计

### 6.1 UI 流程（单 Activity 即可，顺序固定防呆；M3 后为三通道）

1. 点「选择原片 A」→ **相册直选**（`ACTION_PICK` 对 `MediaStore.Video`，返回真实 MediaStore URI，可读 GPS；备用“文件方式”经 `ACTION_OPEN_DOCUMENT` 直达 DCIM/Camera）→ 显示 A 的摘要：拍摄时间、GPS 坐标、机型、数据来源与诊断小字。**这是防呆关键，用户肉眼确认没选错。**（照片选择器返回的 `content://media/picker/…` 注定无 GPS，已移出 A 通道，见 §6.4。）
2. 点「选择剪辑版 B」→ 照片选择器或文件方式 → 显示 B 的文件名、时长、大小（B 不需要 GPS）。
3. 点「执行修复」→ 进度提示（大文件重写需数秒）→ 成功/失败提示。
4. 成功后提示用户去相册验证。
5. 成功后可点「删除原文件」（手动 + 二次确认框）：`insert`（新建文件）时删 A + 旧 B；`overwrite`（原位更新，B 即成果）时只删 A，绝不删成果。
6. 快捷入口（v0.0.1 后）：相册选中 1~2 个视频分享到本 App（`SEND`/`SEND_MULTIPLE`，`singleTop` 接住前台再分享）→ 按 GPS 自动分配（都有/都无按时长兜底）→ 状态栏说明依据，分错点「交换 A/B」（交换作废旧修复结果）。单个视频按 GPS 进一槽，另一槽手动补。

### 6.2 元数据读取（展示与验证用，两段式，M3 后定型）

`MediaMetadataRetriever`：METADATA_KEY_DATE（日期）、METADATA_KEY_LOCATION（GPS 字符串）、METADATA_KEY_DURATION。仅用于展示与修复后验证，不参与核心逻辑。

- 第一段用裸 URI 读时长/日期/尺寸（保底，永不回归）；
- location 为空且 Q+ 时，再用 `requireOriginal` URI 只补 location，失败丢弃；
- 仍为空则把文件拷到临时区，用 mp4engine 直读 `©xyz` 兜底。
- 调试小字格式：`©xyz:有/无/meta:有/无/orig:是/否/跳过/perm:有/无/auth:…/src:…/werr:…`，GPS 缺失时让用户完整粘贴。

### 6.3 存储与替换策略（Android 11+ 作用域存储注意）

- 输入：各通道返回 content Uri，一律先拷到 app 私有目录临时文件再做随机访问（流式 8192 buf，不整块进内存）。
- 替换 B：优先尝试原位更新 B 的 MediaStore 记录（B 是相册创建的，Android 14 上更新他人创建的记录可能被拒——若失败则降级为：写入新 MediaStore 条目，放 DCIM/Camera）。自用场景两种都可接受，实现时先 update、失败 fallback insert。
- **命名（两路统一）**：成品一律叫 `<A基名>_cutfixed.mp4`。覆盖分支顺手改 B 的 `DISPLAY_NAME`（被拒则降级提示手动改名，不算失败）；新建分支出新条目。
- 删除原文件：手动按钮 + 确认框。R+ 用 `MediaStore.createDeleteRequest` 一次弹框批量删；Q29 接 `RecoverableSecurityException` 的 Sender；以下直接删；Document URI 走 `deleteDocument`。删前先把非标准 URI 反查成标准 MediaStore 条目（SIZE→DURATION→名字跨卷查，绝不猜，见 §6.4）；安全规则见 §6.1 第 5 步。

### 6.4 权限与位置脱敏链（M3 后血泪补记，动之前必读）

Manifest 声明：`ACCESS_MEDIA_LOCATION`（GPS 解脱敏）+ `READ_MEDIA_VIDEO`（33+；`READ_EXTERNAL_STORAGE` maxSdk 32、`WRITE_EXTERNAL_STORAGE` maxSdk 28；删除反查全库的前提，单文件授权不够）+ `queries`（`PICK_IMAGES` 与 `PICK`）。

- 照片选择器 URI（`content://media/picker/…`）GPS 死刑：`setRequireOriginal` 必抛 `UnsupportedOperationException`，裸流即使有权限、不勾“抹去信息”依然脱敏。判定：authority 为 `media` 且路径含 `/picker/`；读侧永远跳过包装。
- 数字尾段 ≠ 标准条目：picker 尾段也是纯数字。删框（`createDeleteRequest`）验的是完整路径，只认 `content://media/…/<数字id>` 非 picker 形；批量前自检，坏 URI 不进批量。
- 反查规则：标准形直用；其余按精确 `SIZE` → `DURATION ±2s` 缩圈 → `DISPLAY_NAME` 跨卷查；多行并列一律放弃。宽泛读权限缺失时全库查询返回空游标（不抛异常），删前必须先 gate 权限、通过后自动继续。

## 7. 单元测试与验收标准（M2 落地现状）

fixture：真实样本最终未入库。现行 12 个全合成测试（`Mp4TestHelper.buildSampleA/B`：`ftyp(16 mp42)+mdat+moov(mvhd+udta©xyz+mcvr+meta+trak×2)`），`mp4engine/src/test/resources` 与 `docs/` 为空，不要声称有真实 fixture。

引擎单测断言（`Mp4PatcherTest` 8 + `Mp4LocationTest` 4）：
1. 解析器正确定位所有 box（moov 位置前后两种布局都测）。
2. 输出文件 box 树合法（可解析、size 自洽）。
3. 输出 mvhd/tkhd/mdhd 的日期字节 == A 的对应字节。
4. 输出 udta 含 ©xyz、不含 mcvr。
5. 输出 meta == A 的 meta 字节级一致。
6. 输出 mdat 与 B 的 mdat 字节级一致（流未动）。
7. largesize / size==0 与 ©xyz 解析/跳 mcvr/容错（合成覆盖）。
8. `MediaMetadataRetriever` 的读回校验只在真机做（JVM 单测无此能力）。

端到端验收（真机）：
1. 处理一个真实剪辑视频 → 小米相册中时间线排在拍摄当天、详情页有定位、可正常播放。
2. 输出与 exiftool 修复版 dump 语义一致。

## 8. 实施顺序（里程碑；M1–M4 已完成）

1. **M1 骨架**：工程 + CI，空 UI，push 出可安装 APK。✅
2. **M2 引擎**：mp4engine 解析/重写 + 全部单测（核心工作量）。✅
3. **M3 流程**：UI + 执行流程 + 存储替换 + 真机验收。✅
4. **M4 打磨**：失败回滚、边界报错（非 MP4 明确提示）、可选的读回校验增强。✅（基础项完成）
5. **M5 实战迭代**（真机澎湃OS 连续踩坑后追加）：A 主通道切相册直选（picker 通道 GPS 不可达）→ 两段式读取 + `requireOriginal` → 诊断小字 → 文件通道直达 DCIM/Camera → 修复后删除（确认框 + 安全规则）→ 成品统一命名 `<A基名>_cutfixed.mp4`。✅

## 9. 风险与注意事项清单

1. meta box 的 4 字节 version+flags 头（最易踩坑）。
2. tkhd flags = 0x000007，按 box 类型+版本定位，不写死绝对偏移。
3. moov 子 box 顺序不保证，按类型查找。
4. 支持 moov 前置与后置、32/64 位 box size。
5. ©xyz 的 © 是 0xA9，按字节比较。
6. 大文件全程流式，mdat 通道拷贝。
7. 临时文件验证通过前不得触碰 B。
8. 第二组样本（不同地点、普通竖屏直出）尚未验证——非阻塞，后续顺手补测，若丢失模式不一致需复核字段清单。
9. `@Composable` 内局部函数必须先声明后使用（向前引用即 `Unresolved reference`）；函数声明顺序：state → 回调/launchers → 业务 fun → permissionLauncher → onPickClicked → UI。
10. Kotlin 模板字符串中变量后紧跟中文会被吞成标识符（如 `"$label归一命中"`），一律写 `${label}` 花括号定界。
11. 读 `MediaStore.Video` 全库反查必须先持有宽泛读权限；无权限时本机 ROM 返回空游标而不抛异常——删前 gate，不足则申请、通过后自动继续。
12. 覆盖分支 B 即成果：删除只删 A；改名被拒不算失败（字节已修好），文案降级提示手动改名。
13. Kotlin 块注释支持嵌套：`/** */` 里出现 `/*`（如 MIME 写法 `video/*`）会吞掉后续整文件，报错是 EOF 处的 `Unclosed comment` + 连带 `Unresolved reference`——修注释即可，别被后者误导（`0a9ebf4`）。

## 10. 参考：exiftool 验证命令

```bash
# dump 全部元数据（对比用）
exiftool -a -G1 -s file.mp4
# 结构 dump（box 层级）
exiftool -v3 file.mp4
# 手动修复（答案卷）
exiftool -TagsFromFile A.mp4 "-all:all" B.mp4
```

---

两份建议的后续（M2 后记）：

1. 真实样本与 dump 最终**未入库**（`test/resources`、`docs/` 至今为空）：现行单测全部合成（`Mp4TestHelper`），真机用 `VID_20260908_xxxxxx` 样本对直接验收。若后续要补，仍按原路径放。
2. 本仓库真实约束与当初建议不同：**本机无工具链**（`java`/`gradle` 均无），不要在本地构建——push 到 `main` 看 CI；拿 CI 日志只看 `:app:compileDebugKotlin` 块里的 `e: <file>:<line>` 行。开发期行为以 `AGENTS.md` 为准（命令、通道、诊断格式的唯一口径）。