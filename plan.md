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

## 5. 技术栈与工程结构

- 语言：Kotlin；minSdk 26+，target/compile 最新稳定版；单 module 应用 + `mp4engine` 模块（纯 Kotlin/JVM，无 Android 依赖，可独立单元测试）。
- 无任何第三方依赖。
- 版本控制：GitHub **私有仓库**；包名建议 `com.<用户名>.metafix`。
- CI（GitHub Actions）：
  - push 到 main → 构建 debug APK → 上传 Artifact
  - 推送 tag `v*` → 构建 release APK（签名信息走 GitHub Secrets）→ 创建 GitHub Release
  - 签名：自用阶段 debug 签名即可；release 签名将 keystore base64 + 密码存 Secrets，workflow 中还原。

建议目录：
```
repo/
├── .github/workflows/
│   ├── build-debug.yml
│   └── release.yml
├── app/                  # UI 与流程编排
├── mp4engine/            # box 解析/重写引擎（纯 Kotlin + JUnit 测试）
│   └── src/test/resources/  # 样本 fixture
└── docs/                 # 本文档与 dump 参考
```

## 6. 应用层设计

### 6.1 UI 流程（单 Activity 即可，顺序固定防呆）

1. 点「选择原片 A」→ 系统相册选择器（`ActivityResultContracts.PickVisualMedia`）→ 用 MediaMetadataRetriever 显示 A 的摘要：拍摄时间、GPS 坐标、机型。**这是防呆关键，用户肉眼确认没选错。**
2. 点「选择剪辑版 B」→ 显示 B 的文件名、时长、大小。
3. 点「执行修复」→ 进度提示（大文件重写需数秒）→ 成功/失败提示。
4. 成功后提示用户去相册验证。

### 6.2 元数据读取（展示与验证用）

`MediaMetadataRetriever`：METADATA_KEY_DATE（日期）、METADATA_KEY_LOCATION（GPS 字符串）、METADATA_KEY_DURATION。仅用于展示与修复后验证，不参与核心逻辑。

### 6.3 存储与替换策略（Android 11+ 作用域存储注意）

- 输入：PickVisualMedia 返回 content Uri，用 ContentResolver.openFileDescriptor 读。
- 中间产物：app 私有目录临时文件。
- 替换 B：优先尝试原位更新 B 的 MediaStore 记录（B 是相册创建的，Android 14 上更新他人创建的记录可能被拒——若失败则降级为：写入新 MediaStore 条目（同名，放 DCIM/Camera），由用户手动删除旧 B）。自用场景两种都可接受，实现时先 update、失败 fallback insert。
- 删除旧文件若走系统确认框，自用可接受。

## 7. 单元测试与验收标准

fixture：用 M0 的真实样本对（原片 + 剪辑版）作测试资源。

引擎单测断言：
1. 解析器正确定位所有 box（moov 位置前后两种布局都测）。
2. 输出文件 box 树合法（可解析、size 自洽）。
3. 输出 mvhd/tkhd/mdhd 的日期字节 == A 的对应字节。
4. 输出 udta 含 ©xyz、不含 mcvr。
5. 输出 meta == A 的 meta 字节级一致。
6. 输出 mdat 与 B 的 mdat 字节级一致（流未动）。
7. MediaMetadataRetriever 能读出正确日期与 location。

端到端验收（真机）：
1. 处理一个真实剪辑视频 → 小米相册中时间线排在拍摄当天、详情页有定位、可正常播放。
2. 输出与 exiftool 修复版 dump 语义一致。

## 8. 实施顺序（里程碑）

1. **M1 骨架**：工程 + CI，空 UI，push 出可安装 APK。
2. **M2 引擎**：mp4engine 解析/重写 + 全部单测（核心工作量）。
3. **M3 流程**：UI + 执行流程 + 存储替换 + 真机验收。
4. **M4 打磨**：失败回滚、边界报错（非 MP4 明确提示）、可选的读回校验增强。

## 9. 风险与注意事项清单

1. meta box 的 4 字节 version+flags 头（最易踩坑）。
2. tkhd flags = 0x000007，按 box 类型+版本定位，不写死绝对偏移。
3. moov 子 box 顺序不保证，按类型查找。
4. 支持 moov 前置与后置、32/64 位 box size。
5. ©xyz 的 © 是 0xA9，按字节比较。
6. 大文件全程流式，mdat 通道拷贝。
7. 临时文件验证通过前不得触碰 B。
8. 第二组样本（不同地点、普通竖屏直出）尚未验证——非阻塞，M3 期间顺手补测，若丢失模式不一致需复核字段清单。

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

两份建议，让新对话产出更稳：

1. **把样本文件也准备好**：新对话写单测需要 fixture，建议把原片和剪辑版样本（可脱敏）放到仓库 `mp4engine/src/test/resources/`，并在对话里说明路径。dump 文件（raw.txt、cut.txt、raw_v3.txt）放进 `docs/` 供随时比对。
2. **开工顺序建议**：让新对话先做 M2 的 mp4engine（纯 Kotlin 模块，不依赖 Android，本地就能跑单测验证核心逻辑），再做 M1 骨架和 M3 UI——核心引擎先用单测验证，比在 Android 上调试快得多。