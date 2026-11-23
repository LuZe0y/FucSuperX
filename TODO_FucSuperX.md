# FucSuperX 任务清单（只适配 OriginOS 7）

- [x] 1. 改名：应用名 → FucSuperX（strings.xml app_name、manifest label、build.sh 输出 APK 名）
- [x] 2. 作用域：xposed scope 加 com.android.settings（保留原有作用域）
- [x] 3. 从手机提取 Settings.apk 并反编译（jx_dash / jx_top / res_dump.txt 已有）
- [x] 4. 定位首页注入点：DashboardSummary → 适配器 h0，列表构建 a.java，Tile / DashboardCategory，点击走 h0 的 tile 点击路径
- [x] 5. Hook：在 h0 列表最顶部插入一个 FucSuperX 入口（图标 + 标题 + 摘要），点击启动模块 Activity；避免影响 m0 分类器和 Tile equals
- [x] 6. 模块界面：OS7 风格 Activity
  - 页面背景、大标题栏、圆角白卡片、列表项高度/字号/颜色、分割线、开关（vmovebool OS7 配色）
  - 日间/夜间两套颜色取自 res_dump.txt 的 vos7 token
  - 把现有 persist.land.* 配置项迁移到新界面
- [ ] 7. 构建、安装、重启 com.android.settings，截图对比设置首页 / Wi-Fi 页，修正间距
