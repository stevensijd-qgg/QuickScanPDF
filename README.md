# 快扫PDF · QuickScanPDF

一个只做一件事的安卓扫描 App：**拍照或从相册选图 → 自动裁边、拉正、去阴影 → 清理边缘、背景变白 → 合成多页 PDF**。无水印、无广告、无账号，安装包约 **600 KB**。

A tiny (~600 KB) Android app that turns photos of paper into clean, watermark-free multi-page PDFs.

## 功能

- **自动扫描**：基于 Google ML Kit 文档扫描器，自动找纸张边缘、透视校正，支持滤镜（自动 / 灰度 / 黑白）、去污渍、多页、从相册导入
- **白纸优化**（可开关，默认开启）：
  - 清理自动裁剪没对齐时残留的桌面、阴影条——每条边分 16 段分别检测，斜着歪的也能跟着擦
  - 单独处理纸张圆角露出的背景，以及贴边的纸边线、相邻页边缘
  - 整页背景压成纯白，保留墨迹颜色（红笔、蓝笔不会变黑）
- **直接生成 PDF**：自带极简 PDF 写入器，JPEG 原样嵌入，不引入任何 PDF 库
- **原地重命名**：保存后可在原位置改文件名
- **零权限**：不申请相机、存储等权限；相机由 Google Play 服务调用，文件保存到系统「下载/QuickScan」（Android 10+）或由你选择位置（Android 7–9）

## 下载

到 [Releases](../../releases) 下载最新的 APK 安装。

**要求**：Android 7.0+，手机装有 **Google Play 服务**（国行手机没有谷歌服务的无法使用扫描功能），内存 ≥ 1.7 GB。首次扫描时 Play 服务可能需要联网下载扫描模块。

## 使用

1. 点「开始扫描」，对准文档自动拍摄，或从相册导入
2. 在扫描界面裁剪、旋转、选滤镜、加页，点「保存」
3. App 逐页优化后生成 PDF，可「打开」「分享」「重命名」

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
| `MainActivity.java` | 调起扫描、逐页处理、保存、打开 / 分享 / 重命名 |
| `PaperCleaner.java` | 边缘清理 + 背景白化，纯 Java（不依赖 Android），可在桌面 JVM 上测试 |
| `JpegPdfWriter.java` | 流式写 PDF，每页一张 JPEG（DCTDecode） |

## 许可

[MIT](LICENSE)
