# 相册工坊 PhotoWorkshop

一个在手机上直接管理本地相册的 Android 应用：批量改分辨率、按标签重命名、删除，全部基于系统 MediaStore，不联网、不上传、数据不出本机。

Kotlin + Jetpack Compose，Material 3，支持深色模式。

## 功能

**浏览**

- 顶部三个标签：全部 / 相册 / 视频。全部页把图片和视频按修改时间倒序混排
- 相册以 2 列铺开（封面 + 数量角标），视频以 3 列铺开（首帧 + 时长角标）
- 单击任意缩略图进入全屏预览，左右滑动切换，顶栏显示序号、分辨率、文件大小、时长
- 视频在预览里内联播放，非当前页只显示首帧，避免同时挂多个播放器

**长按操作条**

长按相册或视频从底部浮出操作条，提供修改分辨率 / 重命名 / 删除。视频没有改分辨率入口。

**修改分辨率**

- 90% 高度的面板，默认宽度是原宽的 50%，高度按比例自动计算，实时显示「原始 → 输出」
- 提供 25% / 50% / 75% / 100% 快捷比例
- 解码后按 EXIF 摆正（带旋转的照片不会算出反的宽高），等比缩放再压缩
- 结果以 `原文件名gai.jpg` 写回**原文件所在目录**，原文件保留
- 完成后自动刷新列表

**重命名**

- 90% 高度的面板，底部输入框，每输入一个标签就落到上方标签区并持久保存
- 点标签即追加到文件名：`原文件名 + 标签1 + 标签2…`，选中只变色不改变尺寸（避免 FlowRow 重排）
- 长按标签可修改文字或删除
- 确认后原地改名，覆盖原文件
- 标签全局可用，预置「已压缩 / 已调整 / 备份 / 精选」

**删除**

弹窗确认后走系统删除。

## 权限说明

- Android 13+ 申请 `READ_MEDIA_IMAGES` / `READ_MEDIA_VIDEO`，以下版本用 `READ_EXTERNAL_STORAGE`
- Android 11 起修改或删除他人媒体需要用户授权，应用会通过 `MediaStore.createWriteRequest` / `createDeleteRequest` 拉起系统确认框。**这是系统强制的一次性授权，不是 Bug**
- Android 14 可以选择「仅部分照片」，应用只处理被允许的那些

## 构建

要求 JDK 17+、Android SDK 34。

```bash
git clone git@github.com:666love999/PhotoWorkshop.git
cd PhotoWorkshop
./gradlew assembleDebug
```

产物在 `app/build/outputs/apk/debug/app-debug.apk`。

工程路径包含中文也没问题（`gradle.properties` 里已开 `android.overridePathCheck`）。命令行编译如果遇到 Kotlin 编译守护进程连不上，加 `-Pkotlin.compiler.execution.strategy=in-process` 即可绕开。

## 技术要点

| 场景 | 处理方式 |
| --- | --- |
| 读媒体 | MediaStore 一次查询同时产出相册、视频、全量三个结果，图片只扫一遍 |
| 写回原目录 | Android 10+ 用 `RELATIVE_PATH` + `IS_PENDING`，9 及以下走 `DATA` 列 |
| 大图防 OOM | 按目标宽度算 `inSampleSize`，再用 `BitmapFactory` 二次精确缩放 |
| EXIF | `ExifInterface` 读方向后旋转，重编码时保留原始 EXIF |
| 格式 | JPEG 有损压缩，PNG 保持无损 |

## 目录结构

```
app/src/main/java/com/albumworkshop/
├── data/        MediaStore 查询、数据模型、标签持久化
├── media/       改分辨率落盘、重命名与删除（含授权回退）
├── ui/          主界面、相册详情、状态管理、权限
│   ├── components/  操作条、缩略图、通用组件
│   ├── sheets/      修改分辨率与重命名两个 90% 高度面板
│   ├── viewer/      全屏预览
│   └── theme/       配色与主题
└── MainActivity.kt
```

## 未实现

裁剪与滤镜、视频转码、多选批量操作、预览内双击缩放。
