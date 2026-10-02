# 墨阅 · 本地阅读器（Android）

一个完全离线的本地阅读器。仿主流小说 App 的交互，但阅读页按**真实书籍的排版体例**来做
（天头书眉、地脚页码、版心外的进度线），而不是照搬通用 App 的页脚百分比。

## 安装包

**所有历史版本统一存放在 `releases/`**，每个版本一个独立子目录，互不覆盖。
取用最新版：`releases/v1.0.1/apk/moyu-reader-1.0.1.apk`

| | |
|---|---|
| 包名 | `com.moyu.reader` |
| 版本 | **1.0.1**（versionCode 2） |
| 大小 | **2.31 MB** |
| 最低版本 | Android 8.0（API 26） |
| 目标版本 | **Android 16（API 36）** —— 对应澎湃 OS 3 所基于的版本 |
| 架构 | arm64-v8a / armeabi-v7a / x86 / x86_64 |
| 签名 | v2 方案，`CN=MoYu Reader`（仓库内自签名密钥，见下） |
| SHA-256 | `081351514EDB4EFF1144D9BBD9B608384AF585633AA70181386E246C87B638C4` |

安装：把 APK 传到手机，用文件管理器点开安装（需允许「安装未知来源应用」）。

### 版本规则

每进行一次迭代都必须提升 `versionCode`（+1，绝不重复）与 `versionName`，
并归档到新的 `releases/v<版本>/`。**不得覆盖任何历史版本的安装包**。

完整规则、版本历史与各版本校验值见 **[`releases/VERSION.md`](releases/VERSION.md)**。

`versionCode` 只能单调递增：一旦重复或倒退，系统会拒绝升级安装，
用户只能卸载重装 —— 而卸载会清掉全部阅读数据。

出包命令（自动完成「构建 → 版本化文件名 → 归档」，两个防覆盖机制都固化在构建里）：

```bash
./gradlew :app:release
```

> **密钥说明**：`app/keystore/moyu-release.jks`（口令均为 `moyureader`，别名 `moyu`）
> 是仓库内的演示密钥，开源项目常见做法，便于任何人直接构建出可安装的 APK。
> 若要在应用商店发布，**必须换成你自己的正式密钥** —— 换密钥后已安装的版本无法覆盖升级。

## 权限：0 个

APK 里唯一的 `uses-permission` 是 `com.moyu.reader.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`，
这是应用**自己定义**的 signature 级权限（给内部广播用），不是系统权限。

- 不申请存储权限：本地书通过 SAF（存储访问框架）由用户主动授权文件夹或文件，
  Android 10+ 无需任何存储权限即可读写授权范围内的文件。
- 不申请联网权限：朗读用系统 `TextToSpeech`（由系统进程提供服务），EPUB 用 jsoup 解析、
  封面用 Coil 加载，三者都只处理本地字节流。清单里还写了 `tools:node="remove"`
  作为防线 —— 任何依赖若试图合并进网络类权限，构建会直接删掉。

## 功能

**导入与书架**
- 支持 TXT / EPUB / PDF；TXT 自动识别编码（UTF-8 / GB18030 / GBK / UTF-16LE / UTF-16BE，含 BOM）
- 选择文件、选择整个文件夹批量导入、拖放导入；支持从其他应用「打开方式 / 分享到墨阅」
- 导入去重（同书名且同字数视为重复）；失败项单独列出原因
- 书架支持网格 / 列表两种布局；按最近 / 加入时间 / 书名 / 作者 / 进度排序
- 按阅读状态筛选（在读 / 未读 / 已读完）；自定义分组；长按进操作面板
- 书籍元数据编辑（标题、作者、简介、封面）

**阅读**
- 用真实 `StaticLayout` 排版后分页（不是估算），字号、行距、段间距、页边距、
  首行缩进、两端对齐、加粗改动后即时重新分页
- 阅读页书籍体例：**天头书眉**（章首页不印，避免与章标题重复）、**地脚居中页码**、
  贴页缘的**全书进度细线**
- 翻页方式：仿真（3D 旋转）、平移、覆盖、上下滚动、无动画
- 左中右三热区点击翻页 + 水平拖拽翻页 + 音量键翻页
- 5 套主题：纸感、羊皮纸、护眼绿、夜间、墨黑；跟随系统深色模式；
  Material You 动态取色（Android 12+）；护眼色温与屏幕亮度调节；阅读时常亮
- 自动阅读（按字/秒推进）；朗读（系统 TTS，可调语速音调、切换引擎）

**笔记与检索**
- 长按选中正文 → 划线（多色）/ 复制 / 查词 / 写想法
- 书签；全书笔记页按书分组、书内笔记按天分组
- 全文搜索，结果高亮并可直接跳转到原文位置
- 内置词典 + 可导入自定义词典

**统计**
- 总阅读时长、天数、连续天数、读完本数、读书速度
- 热力图（按本地时区归日）；单日详情

## 架构

Kotlin 2.0 + Jetpack Compose（Material 3），单 Activity + Compose Navigation。
Room 存书库与笔记，DataStore 存偏好设置，手动依赖注入（`AppContainer`）。

```
app/src/main/java/com/moyu/reader/
├── reader/          纯逻辑层，与 Android UI 无关，可单独测
│   ├── TextEncodingDetector.kt    编码探测（字节级严格 UTF-8 校验 + 回退）
│   ├── ChapterSplitter.kt         TXT 分章（高/低置信两级，偏移无损）
│   ├── PaginationEngine.kt        StaticLayout 分页
│   ├── ReadingStatsCalculator.kt  统计口径（本地时区归日、连续天数）
│   ├── TtsController.kt           系统 TTS 封装
│   └── DictionaryProvider.kt      内置/自定义词典
├── parser/          TXT / EPUB 解析（EPUB 用 jsoup 抽正文）
├── storage/         SAF 文档访问与示例书
├── data/            Room 实体 / DAO / 仓库 / 偏好设置
└── ui/              Compose 界面（screens / components / theme / navigation）
```

**一个刻意的设计取舍**：书签、划线、笔记、搜索、进度全部共用同一个坐标系 ——
「全书字符偏移」。导入时**不改动原始正文**（首行缩进、标题去重都在渲染层做），
这样才能保证偏移永远对得上。代价是渲染层要多一点逻辑，收益是这五个功能不会各错各的。

**分章规则与 Web 验证器逐条对齐**：两端不一致的话，就没法用验证器判断 Android 端是否正常。

## 自己构建

需要 JDK 17 与 Android SDK（platform 36、build-tools 36.x）。

```bash
# gradle.properties 里已指向本机工具链，按需修改
#   sdk.dir=D:\\AndroidToolchain\\sdk
#   org.gradle.java.home=D:\\AndroidToolchain\\jdk\\jdk-17.0.13+11

./gradlew :app:assembleDebug     # 调试包（未压缩，便于排查）
./gradlew :app:release           # 发布包：构建 + 版本化文件名 + 归档到 releases/
./gradlew :app:testDebugUnitTest # 108 个单元测试（含 Robolectric，无需模拟器）```

出包一律用 `:app:release`，不要用 `assembleRelease` ——
前者会自动归档到 `releases/v<版本>/`，后者只把产物丢在 `build/` 下，
下次 `clean` 就没了。

R8 与资源裁剪都开着：调试包 13 个 dex → 发布包 1 个 dex / 2.31 MB，
且 **R8 没有报任何 missing class 警告**（未生成 `missing_rules.txt`）。

### 开发流程

提交身份、分支模型、提交信息写法、合并前必须跑的验证、凭据管理 ——
见 **[`DEVELOPMENT.md`](DEVELOPMENT.md)**。

## 已验证 / 未验证

**已实测**
- `:app:testDebugUnitTest` → **108 个测试全部通过**（含 Robolectric）
  - 编码探测 13、分章 15、阅读统计 15、分页 13、数据库 12、导入仓库 7、迁移 1
  - Robolectric 下跑的是**真实 SQLite 与真实 Room**：外键级联、事务回滚、
    `content` 不进内存的约定都是真验证的，不是 mock
  - 分页用**真实 `StaticLayout`**（Robolectric SDK 34），验证页面区间连续、拼接后与原文逐字相同
- 静态校验 APK：包名、版本、minSdk/targetSdk、启动 Activity、自适应图标、
  `apksigner verify` 通过、`zipalign` 通过、权限列表只有 0 个系统权限
- 数据库迁移 1→2：用手工构建的真实 v1 库（表结构直接取自 Room 导出的 `schemas/…/1.json`）
  验证迁移后阅读位置原样保留、孤儿行被清理、新增外键确实生效

**未验证（重要）**
- **没有在真机或模拟器上运行过。** 本机 `HypervisorPresent: False`，
  `HypervisorPlatform` 与 `VirtualMachinePlatform` 均为 Disabled（启用需改 Windows 功能并重启），
  `adb devices` 为空。因此**界面外观、SAF 选择器、TTS 实际发声、Coil 封面加载、
  真机字体度量下的分页效果都没有被观察过**。
  分页的正确性是在 JVM 的 Robolectric StaticLayout 上验证的，不等价于真机像素结果。
- R8 压缩后的运行时安全性是根据「无 missing class 警告 + 反射敏感类未被改名」推断的，
  不是运行观察到的。

**因此第一次装到真机时，建议按这个顺序过一遍**：
打开示例书 → 翻几页看分页是否丢字 → 调字号看是否重排 → 导入一个 GBK 编码的 TXT →
划线加笔记 → 搜索跳转 → 看统计是否记录 → 试朗读。

## 已知取舍

- **PDF** 只作为整页文档保存记录，不提取文字。PDF 没有重排概念，
  强行抽文字会得到断行错乱的文本，不如如实按页看。
- **页码按章内编号**（每章从 1 开始），不是全书连续页码。
  做全书连续页码需要把所有章节都分页，那会在打开大文件时卡住；
  按章编号是「不卡」与「有方位感」之间选的折中。
- 分章对「篇名 + 标题」要求中间有空白（「楔子 雪夜」）。这是为了不把
  「前言里说过…」这类普通句子误判成章节标题 —— 宁可少切，不可错切。
