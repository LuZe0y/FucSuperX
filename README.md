# FucSuperX

[![Build](https://github.com/LuZe0y/FucSuperX/actions/workflows/build.yml/badge.svg)](https://github.com/LuZe0y/FucSuperX/actions/workflows/build.yml)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)

OriginOS 7 的 LSPosed 模块：原子岛玻璃材质、系统液态玻璃调节、通知中心大号时钟与横屏分栏。

> [!WARNING]
> 只适配 OriginOS 7。在其他系统或版本上大概率无效，甚至可能导致系统界面异常。

## 功能

### 原子岛
- 玻璃材质：强 / 中 / T0 三档，跟随深浅色切换
- 背景过亮时自动反相文字、音乐按钮和进度条，展开的原子岛也生效
- 材质刷新率跟随屏幕，也可固定 60 / 90 / 120 / 144 Hz，减轻玻璃动画撕裂
- 去掉音乐卡片、充电动效的黑底，让玻璃透出来

### 系统液态玻璃
- 调节原子岛以外的系统玻璃材质：控制中心、通知中心、通知、锁屏通知等，实时生效
- 背景模糊：调节下拉通知 / 控制中心背后的全屏模糊
- 按键调出的音量面板保持系统原样

### 通知中心
- 大号居中时钟：日期在上、时间放大在下，字号倍数和下移距离可调
- 时间字体：系统默认、跟随锁屏，或 14 种系统内置时钟字体，字重可调，带预览卡片
- 收起动画里飞向状态栏的时钟和状态栏时钟同步使用所选字体，过渡没有字形跳变
- 横屏分栏：时间日期在左半屏，通知在右半屏

### 设置
- 在系统「设置」首页顶部加入 FucSuperX 入口，模块界面为 OriginOS 7 风格

## 要求

- OriginOS 7
- 已 root，并安装支持 libxposed API 101 及以上的 LSPosed

## 安装

1. 从 [Releases](https://github.com/LuZe0y/FucSuperX/releases) 下载 `FucSuperX-版本号.apk` 并安装（开发版可在 [Actions](https://github.com/LuZe0y/FucSuperX/actions) 的构建产物中下载）
2. 在 LSPosed 中启用模块，作用域勾选：
   - 系统界面 `com.android.systemui`
   - `com.vivo.systemuiplugin`
   - 设置 `com.android.settings`
3. 重启手机
4. 从「设置」首页顶部的 FucSuperX 入口，或 LSPosed 的模块设置进入调节

大部分选项修改后即时生效。配置只通过 LSPosed 模块通道读写，打开模块界面时如果提示「模块未激活」，请按页面指引在 LSPosed 中启用模块、勾选作用域并软重启。

## 从源码构建

不使用 Gradle，`build.sh` 直接调用 javac → d8 → aapt2 → zipalign → apksigner。

需要：JDK 17 及以上、Android SDK（`build-tools;35.0.0`、`platforms;android-35`）、Python 3。

```bash
export ANDROID_HOME=~/android-sdk
./build.sh          # 输出 build/FucSuperX.apk
```

签名可以用环境变量指定：

| 变量 | 默认值 |
| --- | --- |
| `KEYSTORE_PATH` | `keystore.jks`（不存在时自动生成） |
| `KEYSTORE_PASSWORD` | `android` |
| `KEY_ALIAS` | `land` |
| `KEY_PASSWORD` | 同 `KEYSTORE_PASSWORD` |

`*.jks` 已在 `.gitignore` 中，不会被提交。自己构建的 APK 签名和正式版不同，覆盖安装前需要先卸载。

## CI

[`.github/workflows/build.yml`](.github/workflows/build.yml)：

- 推送到 `main` 和提交 PR 时构建，APK 作为构建产物上传
- 推送 `v*` 标签时自动创建 Release 并附上 APK

正式签名来自仓库的 Actions Secrets：

| Secret | 内容 |
| --- | --- |
| `SIGNING_KEYSTORE_BASE64` | base64 编码后的 keystore 文件 |
| `KEYSTORE_PASSWORD` | keystore 密码 |
| `KEY_ALIAS` | 密钥别名 |
| `KEY_PASSWORD` | 密钥密码 |

没有这些 Secrets 时（例如来自 fork 的 PR）会用临时生成的密钥签名。

发布新版本：修改 `AndroidManifest.xml` 里的 `versionCode` / `versionName` 并提交，然后：

```bash
git tag -a v3.4 -m "FucSuperX 3.4

- 更新内容一
- 更新内容二"
git push origin v3.4
```

标签说明（第一行之后的内容）会作为 Release 的更新日志，后面自动附上完整提交对比链接。
说明里要用 `## 小标题` 时，加上 `--cleanup=verbatim`，否则 git 会把 `#` 开头的行当注释删掉。

## 目录结构

```
src/        模块源码（Hook 入口、各功能、模块界面）
stubs/      隐藏 API 的编译期桩，只参与编译，不打进 APK
res/        资源
xposed/     META-INF/xposed 配置（入口类、作用域、API 版本）
libs/       libxposed API / service 库
tools/      设备端调试脚本
docs/       架构说明
```

架构说明见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)。

## 致谢

- [LSPosed](https://github.com/LSPosed/LSPosed)
- [libxposed](https://github.com/libxposed)

## 免责声明

本模块会修改系统界面进程，使用风险自负。因使用本模块造成的任何问题，作者不承担责任。

## 许可证

本项目基于 [GPL-3.0](LICENSE) 开源。`libs/` 下的 libxposed 库遵循其各自的 Apache-2.0 许可证。
