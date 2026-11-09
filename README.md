# 拼多多底栏液态玻璃 · pdd-LiquidGlass

为拼多多打造的 iOS 26 风格液态玻璃底部导航栏 · LSPosed 模块（libxposed API 102）

> 当前版本：v1.0.21，APK 见 [Releases](https://github.com/lengroo/pdd-LiquidGlass/releases)。
> 本项目：[lengroo/pdd-LiquidGlass](https://github.com/lengroo/pdd-LiquidGlass)
> 已上架 LSPosed 官方仓库：[Xposed-Modules-Repo/io.github.lengroo.pddlg](https://github.com/Xposed-Modules-Repo/io.github.lengroo.pddlg)

## ✨ 特性

- 🫧 **单 pass AGSL 透镜管线**：圆角矩形 SDF 折射、边缘色散、重力传感器高光
- 💧 **玻璃滴选中动效**：切换标签时液滴滑动、回弹；**支持横向拖拽切换**，跟手拉伸、松手吸附
- 🎯 **对齐 KernelSU 管理器**：悬浮 pill 宽度贴合内容、4dp 轻模糊 + 容器色垫底、
  按压时液滴放大 `1.2×` 并淡入折射、焦点标签放大 `1.1×`
- 🔍 **背景实时跟随**：玻璃折射的是当前页面的真实内容，切页即变
- 🧩 **完整保留原生功能**：未读角标、图标、文字颜色全部走应用自己的实现，角标不参与染色
- 📱 **单宿主**：只做拼多多，差异集中在 `HostApp` 一张表里
- 🌗 **深浅色跟随**：读取应用 uiMode
- 🎛️ **数值微调**：设置界面「微调」卡片可调药丸离底边距离（0–48dp）、药丸额外高度（0–24dp）、药丸左右边距（每边 0–48dp），改完切回拼多多即生效，无需重装
- 🔥 **代码热重载**：更新模块后无需强制停止拼多多，新代码直接在运行中接管，药丸状态（当前标签、水滴位置）原样保留（需 LSPosed Vector 2.2+；首次启用仍需重启一次拼多多）
- ♿ **降级兜底**：Android 12 保留 GPU 模糊与液滴，只少了边缘折射（AGSL 需 13）；
  Android 12 以下退化为轻磨砂；native 模糊库不可用时自动切纯 Java 实现

## 📦 安装

1. 准备支持 **libxposed API 102** 的 LSPosed 环境
2. 安装 APK，在 LSPosed 中启用（作用域已限定 `com.xunmeng.pinduoduo`）
3. **强制停止**拼多多后重新打开（首次启用；之后更新模块无需强制停止，热重载直接接管）

> 已测试：拼多多 `8.29.0` / `8.30.0`
>
> 包名：`io.github.lengroo.pddlg`（1.0.19 起；旧包 `io.github.lengroo.pddliquidglass` 需先卸载再装新包，直接覆盖安装会失败）
>
> 两个版本：`PddLG-vX.Y.Z.apk`（带设置界面）和 `PddLG-vX.Y.Z-noui.apk`（无界面模块版），功能相同，按需选择。

已知问题：切换标签页时 feed 会短暂闪一下黑，页面加载完就恢复。

## 🔍 工作原理

拼多多的主界面结构和微信是双胞胎——内容区铺满全屏，底栏浮在它上面，同处一个
`MainFrameContainerView`（RelativeLayout）——所以安装器只有一套：

```
Hook Instrumentation.callActivityOnResume
  └─ 主界面 Activity：按类名定位底栏（资源 ID 被 AndResGuard 混淆，不可用）
       └─ 布局手术：把底栏搬进 LiquidGlassHostLayout
            ├─ 玻璃层 LiquidGlassView（backdrop = 底栏原本的全屏兄弟）
            ├─ 液滴层 LiquidGlassView（跟随选中项，可拖拽）
            └─ 应用原生底栏（背景已清空）
```

应用相关的部分全在 `HostApp` 里：

|  | 拼多多 |
| --- | --- |
| 包名 | `com.xunmeng.pinduoduo` |
| 主界面 Activity | `ui.activity.MainFrameActivity`（另有 `ui.activity.HomeActivity` 入口） |
| 底栏 | `ui_home_activity.widget.tab.PddTabView`（5 个 `TabRelativeLayout` 标签） |
| 切页信号 | `setOnTabChangeListener` 包装代理（`onTabSelected(int)`，方法名未混淆） |
| 图标定位 | `TabRelativeLayout.tabImageView` 字段（普通 ImageView，类名无法区分角标） |
| backdrop | 全屏内容 FrameLayout（含 CustomViewPager） |
| 额外处理 | 不动窗口与页面（拼多多自己管理 insets，页面已铺满并自带底部预留）；滚动时底栏自身的位移动画被钉在药丸内；标签异步填充时安装重试；皮肤重绘时重剥背景 |

日志 tag：`LiquidGlass`

## 🛠️ 构建

无需 Gradle / Android Studio：

```
./setup-tools.sh   # 下载 android.jar / build-tools 34 / libxposed
./build.sh         # 无界面版 → PddLG-vX.Y.Z-noui.apk
./build-compose.sh # 带设置界面版 → PddLG-vX.Y.Z.apk
```

产物使用正式 release keystore 签名（`KEYSTORE` / `KEYSTORE_PASS` / `KEY_ALIAS` 可指定）。

### 可调参数

| 参数 | 位置 | 默认 |
| --- | --- | --- |
| 折射高度 / 边缘带 | `DropletPanel` | 10dp（上限，见液滴一节） |
| 模糊强度 | `tuneGlass()` | 4dp（同 KernelSU `blur()`） |
| 每列额外留白 | `hugContentWidth()` | 32dp |
| 底部抬升 | `GlassConfig.barOffsetDp`（设置界面「微调」可调） | 24dp |
| 药丸额外高度 | `GlassConfig.pillExtraHeightDp`（设置界面「微调」可调） | 12dp（上下对称分摊） |
| 药丸左右边距 | `GlassConfig.pillSideInsetDp`（设置界面「微调」可调） | 0dp（每边） |
| 按压放大 / 焦点放大 | `DropletDragController` / `DropletPanel` | 1.2 · 1.1 |
| 液滴高度 | `syncDropletSize()` | 药丸内高 − 2 × 4dp |
| 拖拽形变上限 | `DropletDragController.STRETCH_LIMIT` | 0.2 |

### 液滴

静止时是一层 10% 的纯色块；按住/拖拽时按 `pressProgress` 淡入透镜：

```
lens(refractionHeight = 10dp × p, refractionAmount = 14dp × p,
     depthEffect = 0, chromaticAberration = 0.5)
```

药丸较扁，`depthEffect` 开启会在折射边界拉出一圈环，所以这里关掉。

液滴折射的是 **页面 + 一份单独绘制的 1.1× 标签副本**（KernelSU 的 `CombinedBackdrop`）。拖拽时看到的放大图标正是这份副本经折射后的样子，
因此液滴层必须压在真实标签**之上**，而真实标签保持原尺寸——两边都缩放会叠加两次。

### 动画

滑动/按压完全复刻 KernelSU 的 `DampedDragAnimation`：五条独立弹簧，参数照搬。

| 弹簧 | 阻尼比 / 刚度 | 驱动 |
| --- | --- | --- |
| value | 1.0 / 1000 | 位置（**以标签为单位**，非像素） |
| velocity | 0.5 / 300 | 归一化速度 → 拉伸形变 |
| press | 1.0 / 1000 | 按压进度 |
| scaleX | 0.6 / 250 | 横向缩放 |
| scaleY | 0.7 / 250 | 纵向缩放 |

两处易被忽略但决定手感的细节：位置以标签为单位追踪，速度按标签数归一化，
因此不同屏幕密度手感一致；`release()` 会**等液滴接近目标**才回落缩放，
这样甩动读起来是一个连贯动作而非"滑动 + 单独缩回"。

点击切换也走同一条路径（press → 移动 → release），与拖拽完全一致。

## 🙏 致谢

- [liuran001/WeChat-LiquidGlass](https://github.com/liuran001/WeChat-LiquidGlass) — MIT，本项目的上游与渲染引擎来源
- [sjtt2/HeyBox-LiquidGlass](https://github.com/sjtt2/HeyBox-LiquidGlass) — MIT，上游的灵感来源
- [tiann/KernelSU](https://github.com/tiann/KernelSU) — 拖拽形变的速度曲线参考其 `FloatingBottomBar`
- [libxposed/api](https://github.com/libxposed/api) — Apache-2.0

## 📄 License

MIT，与上游相同，见 [LICENSE](LICENSE)。

