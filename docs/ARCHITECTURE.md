# FucSuperX 架构图（v2.3）

包名 `io.luze0y.islandglass`，LSPosed 模块（libxposed API 102，`minApiVersion=101`），只适配 OriginOS 7。

## 1. 总览：进程与组件

```mermaid
flowchart LR
    subgraph APK["FucSuperX.apk"]
        ME["ModuleEntry<br/>(XposedModule 入口)"]
        SH["SettingsHook"]
        GP["GlassPaint"]
        CF["Conf<br/>(配置读取/订阅)"]
        SA["SettingsActivity<br/>(模块界面 OS7 风格)"]
        XP["XposedProvider<br/>(libxposed-service)"]
        META["META-INF/xposed<br/>module.prop / java_init.list / scope.list"]
    end

    LSP["LSPosed 框架<br/>+ 远程偏好存储 group=config"]

    subgraph P1["com.android.systemui"]
        SYS["SystemUI 主进程<br/>BaseDexClassLoader 构造 Hook<br/>→ 发现 SystemUIPlugin ClassLoader"]
    end
    subgraph P2["com.vivo.systemuiplugin:island"]
        ISL["原子岛<br/>DynamicCapsuleContainer<br/>ContainerAnimatorCommon"]
    end
    subgraph P3["com.android.settings"]
        SET["设置首页<br/>dashboard.a / h0 / Tile"]
    end
    subgraph P4["io.luze0y.islandglass"]
        UI["模块界面进程"]
    end

    META --> LSP
    LSP -- "java_init.list 加载" --> ME
    ME -- "onPackageReady / onPluginLoader" --> SYS
    ME -- "onPluginLoader" --> ISL
    ME -- "SettingsHook.install" --> SET
    SET -- "点击入口 launchIntent" --> SA
    SA --- UI
    XP -- "Binder" --> SA
    SA -- "XposedService.getRemotePreferences 写" --> LSP
    LSP -- "getRemotePreferences 读 + 变更回调" --> CF
    CF --> ME
    CF --> SH
    ME --> GP
```

作用域（`scope.list`）：`com.android.systemui`、`com.vivo.systemuiplugin`、`com.android.settings`。

## 2. 配置通路（Conf）

```mermaid
flowchart TB
    UI["SettingsActivity<br/>toggle / slider / 分页"] -->|"有 LSPosed 服务"| RP["远程偏好 config<br/>键 = persist.land.* 去前缀"]
    UI -->|"无服务回退"| SU["su setprop persist.land.*"]
    RP --> G["Conf.get(prop, def)"]
    SU --> SP["SystemProperties"] --> G
    D["默认值"] --> G
    G -->|"优先级：远程偏好 > SystemProperties > 默认值"| USE["ModuleEntry / SettingsHook"]
    RP -->|"OnSharedPreferenceChange"| L["Conf 监听器"]
    L -->|"cmd_restart_island"| K1["island 进程 killProcess 重启"]
    L -->|"cmd_restart_settings"| K2["settings 进程 killProcess 重启"]
    L -->|"其他键 → subscribe()"| H["onConfChanged 实时生效<br/>(深色 T0、反相、入口开关等)"]
```

## 3. 原子岛玻璃（ModuleEntry）Hook 链

```mermaid
flowchart TB
    A["onModuleLoaded<br/>Conf.init + DEBUG"] --> B["install()"]
    B --> B1["installGeometryHook<br/>模糊 / 折射参数"]
    B --> B2["View.setMaterialCustomized(10 参)<br/>→ 原子岛容器改调 setMaterial(style, ct)"]
    B --> B3["View.setMaterialRegion<br/>(截图中摄像头圆点不显示玻璃)"]
    B --> B4["BaseDexClassLoader 构造 Hook"]
    B4 --> C["onPluginLoader(cl)<br/>(每个 ClassLoader 只装一次)"]
    A -.->|"island 进程 onPackageReady"| C

    C --> C1["ContainerAnimatorCommon 构造<br/>→ mPaint 换成 GlassPaint"]
    C --> C2["updateBlurAppearance(false)<br/>→ 原子岛可见时强制 true"]
    C --> C3["installFxHook<br/>IslandAnimConfigsCommon"]
    C --> C4["drawBounds 后处理"]
    C --> C5["ensureConfigHook<br/>onConfigurationChanged"]

    C4 --> D1["syncRegion 逐帧同步材质区域"]
    C4 --> D2["updateDot / updateStroke"]
    C4 --> D3["fixOpaqueBackgrounds<br/>音乐卡片 #background_show 去黑底"]
    C4 --> D4["fixEffectLights 隐藏光效<br/>充电 PAG 去黑底"]
    C4 --> D5["updateTextInvert"]
    C4 --> D6["shouldForce 补救卡住的材质"]

    D5 --> E1["CompositionSamplingListener<br/>(隐藏 API，stubs 编译)"]
    E1 --> E2["onLuma：亮度 > hi(0.6) 反相<br/>< lo(0.5) 恢复"]
    E2 --> E3["applyInvert：文字 / 音乐按钮 / 进度条"]

    C5 --> F["onConfig → reapply → applyStyle"]
    F --> F1["材质 409/410(浅) · 411/412(深)<br/>strong / medium / T0"]
    F --> F2["tuneBlurRefraction / tuneEdgeLight<br/>applyFolderRecipe / applyFps(60/90/120/144)"]
```

`GlassPaint`：`mBlurVisible=true` 且非摄像头小圆点时，把 `setColor` / `setShader` 的黑底与渐变改为透明，让系统材质透出来。

## 4. 设置首页入口（SettingsHook）

```mermaid
flowchart LR
    S0["onPackageReady<br/>com.android.settings (first)"] --> S1["SettingsHook.install"]
    S1 --> S2["dashboard.a(a$a) 构造<br/>下标 0 插入 a$b(FucSuperX Tile)"]
    S1 --> S3["h0.B(List, List)<br/>记录适配器，供实时增删"]
    S1 --> S4["h0$h 绑定 Tile 后<br/>decorate：EntryIcon / 摘要 / 点击"]
    S4 --> S5["launchIntent → SettingsActivity"]
    CF["Conf: settings_entry"] -->|"liveToggle"| S3
```

不修改 `DashboardCategory`，`m0` 分类器与 `Tile.equals` 不受影响；入口 Tile 为单例，靠引用识别（id `-0x46535558`）。

## 5. 构建流程（build.sh，无 Gradle）

```mermaid
flowchart LR
    J1["javac stubs/<br/>(只进 classpath)"] --> J2["javac src/<br/>android.jar + libxposed api/service/interface"]
    J2 --> D8["d8 --min-api 31<br/>(service/interface 打进 dex)"]
    R["aapt2 compile/link<br/>res + AndroidManifest"] --> PK
    D8 --> PK["python 打包<br/>classes.dex + META-INF/xposed/*"]
    PK --> ZA["zipalign"] --> AS["apksigner<br/>keystore.jks"] --> OUT["build/FucSuperX.apk"]
```
