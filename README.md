# 快扫PDF · QuickScanPDF

一个只做一件事的安卓扫描 App：**拍照或从相册选图 → 自动裁边、拉正、去阴影 → 清理边缘、背景变白 → 合成多页 PDF**。拍歪的表格也能自动摆正。无水印、无广告、无账号，安装包约 **700 KB**。

A tiny (~700 KB) Android app that turns photos of paper into clean, watermark-free multi-page PDFs, straightening skewed forms on the way.

## 功能

- **自动扫描**：基于 Google ML Kit 文档扫描器，自动找纸张边缘、透视校正，支持滤镜（自动 / 灰度 / 黑白）、去污渍、多页、从相册导入
- **白纸优化**（可开关，默认开启）：
  - 清理自动裁剪没对齐时残留的桌面、阴影条——每条边分 16 段分别检测，斜着歪的也能跟着擦
  - 单独处理纸张圆角露出的背景，以及贴边的纸边线、相邻页边缘
  - 整页背景压成纯白，保留墨迹颜色（红笔、蓝笔不会变黑）
- **表格校正**（可开关，默认开启）：
  - 找到表格（相互连通的最大一片线条），四条外边框各拟合一条直线，忽略超出边框的手写字；拍照时边框被截掉也会改用最外侧完整的格线
  - 透视变换把表格拉回矩形，标题、表头一起摆正，再统一排版到 A4
  - 没有表格的页面（比如笔记）保持原样
- **校正预览**：生成前逐页看「校正前 / 校正后」，不准的页面可以手动拖动四个角（带放大镜）、重新自动检测、旋转 90° 或整页不校正
- **表格校正设置**：纸张 A4 / A5 / 原尺寸，页边距窄 / 标准 / 宽，表格靠上 / 居中，统一各页表格宽度，加深表格线条，带实时预览
- **直接生成 PDF**：自带极简 PDF 写入器，JPEG 原样嵌入，不引入任何 PDF 库
- **本次扫描记录**：首页列出这次打开应用后保存的文件，可打开、分享、原地重命名
- **有确认感的交互**：按下回弹、弹簧开关、滑动选项、底部提示、进度条 → 打勾动画、振动反馈；系统开了「减少动画」时自动关闭动效
- **零权限**：不申请相机、存储等权限；相机由 Google Play 服务调用，文件保存到系统「下载/QuickScan」（Android 10+）或由你选择位置（Android 7–9）

## 下载

到 [Releases](../../releases) 下载最新的 APK 安装。

**要求**：Android 7.0+，手机装有 **Google Play 服务**（国行手机没有谷歌服务的无法使用扫描功能），内存 ≥ 1.7 GB。首次扫描时 Play 服务可能需要联网下载扫描模块。

## 使用

1. 点「开始扫描」，对准文档自动拍摄，或从相册导入
2. 在扫描界面裁剪、旋转、选滤镜、加页，点「保存」
3. 在「校正预览」里检查每一页，需要的话点「调整」手动对准表格边框
4. 点「生成 PDF」，保存后可「打开」「分享」「重命名」

## 编译

需要 JDK 17+ 和 Android SDK（platform 36、build-tools 35.0.0）。

```bash
./gradlew assembleRelease
```

产物：`app/build/outputs/apk/release/app-release.apk`。release 包默认用调试证书签名，方便直接安装；正式分发请换成自己的签名配置。

`settings.gradle.kts` 优先使用阿里云 Maven 镜像（国内网络更快），之后回退到 Google / Maven Central。`gradle.properties` 中的 `android.overridePathCheck=true` 用于允许项目放在含中文的路径下编译。

## 代码结构

| 文件 | 作用 |
|---|---|
| `MainActivity.java` | 首页：调起扫描、处理选项、本次扫描记录、提示条 |
| `ReviewActivity.java` | 校正预览：逐页分析、前后对比、生成并保存 PDF |
| `AdjustActivity.java` / `QuadEditorView.java` | 手动调整表格边框：拖角、放大镜、扫描动效、弹簧归位 |
| `SettingsSheet.java` / `LayoutPreviewView.java` | 表格校正设置底部面板和实时版面预览 |
| `PaperCleaner.java` | 边缘清理 + 背景白化，纯 Java（不依赖 Android），可在桌面 JVM 上测试 |
| `TableRectifier.java` | 表格检测、透视校正、A4 排版，纯 Java |
| `PageProcessor.java` | 解码、旋转、调用上面两步，生成缩略图和最终页面 |
| `JpegPdfWriter.java` | 流式写 PDF，每页一张 JPEG（DCTDecode） |
| `SpringSwitch` / `SegmentedControl` / `CheckMarkView` / `ProgressLineView` | 自绘控件，带动效 |

## 许可

[MIT](LICENSE)
