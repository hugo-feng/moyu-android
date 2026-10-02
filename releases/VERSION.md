# 版本管理规则

本目录存放**所有**已构建的安装包。规则如下。

## 一、版本号必须递增，永不覆盖

每进行一次迭代（任何影响 APK 内容的改动）都必须：

1. 提升 `app/build.gradle.kts` 里的 `versionCode`（整数，**每次 +1，绝不重复**）
2. 提升 `versionName`（语义化版本，见下）
3. 归档到 `releases/v<versionName>/`，**不删除、不覆盖**任何历史版本

`versionCode` 是 Android 判定「哪个更新」的唯一依据。它一旦重复或倒退，
系统会拒绝升级安装，用户只能卸载重装 —— 而卸载会清掉全部阅读数据。
所以它只能单调递增。

## 二、版本号怎么定

`主版本.次版本.修订号`，语义与常见约定一致：

| 位 | 何时递增 | 例子 |
|---|---|---|
| 主版本 | 不兼容的数据结构变更（老版本数据无法沿用） | 1.0.0 → 2.0.0 |
| 次版本 | 新增功能 | 1.0.1 → 1.1.0 |
| 修订号 | 修 bug、调文案、纯内部重构 | 1.0.0 → 1.0.1 |

当前版本见文末表格。

## 三、目录结构

```
releases/
  v1.0.0/
    moyu-reader-1.0.0.apk      ← 历史版本，只读，任何人不得改写
    README.txt                  ← 该版本的安装信息与校验值
  v1.0.1/
    apk/
      moyu-reader-1.0.1.apk
  VERSION.md                    ← 本文件
```

每个版本一个**独立子目录**。这样做的目的是：任何时候都能回答
「用户装的这个包，到底是哪一版的代码」—— 而如果所有版本都叫
`app-release.apk` 并放在一起，这个问题就永远答不上来。

## 四、怎么出包

```powershell
$env:JAVA_HOME='D:\AndroidToolchain\jdk\jdk-17.0.13+11'
$env:ANDROID_HOME='D:\AndroidToolchain\sdk'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
cd D:\work\android
.\gradlew.bat --no-daemon -g 'D:\work\.cache\gradle-home' :app:release
```

`:app:release` 会自动完成三件事：构建 → 产出带版本号的文件名 → 归档到
`releases/v<版本Name>/apk/`。

两个防覆盖机制都已固化在构建里，不依赖人的记性：

- **文件名带版本号**（`moyu-reader-1.0.1.apk`）—— 覆盖在物理上不可能发生
- **归档前清空该版本目录** —— 避免上一次构建留下的、文件名不同的旧产物被一起带进来
  （AGP 不会自动删除改名前留下的产物，实测踩过这个坑）

出包后**务必核对**内部版本号，确认不是拿了旧包：

```powershell
& "$env:ANDROID_HOME\build-tools\<最新版本>\aapt2.exe" dump badging `
  releases\v1.0.1\apk\moyu-reader-1.0.1.apk | Select-String 'package:'
```

`versionName` 必须与目录名一致，否则说明构建没有真正生效。

## 五、版本历史

| 版本 | versionCode | 内容 |
|---|---|---|
| 1.0.0 | 1 | 首个可交付版本。含三个严重缺陷修复（分页漏扣天头地脚导致每页少 2–3 行字、退出丢进度丢时长、书架网格最后一排书不可见可点）及滚动模式误触、跳转丢命中位置、段间距失效等修复 |
| 1.0.1 | 2 | 阅读页工具栏图标还原为标准图标（图标形变引擎保留在代码中但不接入界面）；移除未完成的形变对照测试。功能与 1.0.0 一致，不含形变动画 |

## 六、校验值

安装前可用它核对文件是否完整、是否与预期版本一致：

| 版本 | SHA256 |
|---|---|
| 1.0.0 | `6FFBDC24607A3E9A6C596957190E24E5826498466E08E0709CF16199CDD8E418` |
| 1.0.1 | `081351514EDB4EFF1144D9BBD9B608384AF585633AA70181386E246C87B638C4` |

```powershell
Get-FileHash releases\v1.0.1\apk\moyu-reader-1.0.1.apk -Algorithm SHA256
```
