# 条码工厂 Pro HD (Barcode Factory HD)

原生 Android 应用（Kotlin + ViewBinding），由原 HTML 页面重构为安卓项目，不再依赖 HTML/WebView。

## 功能

- 批量生成条码，实时预览：EAN-13、CODE 128、CODE 39、UPC-A、ITF-14、Pharmacode、MSI
- 扫码自动记录：调用相机扫描条码后自动追加到当前列表
- 复制：点击任意条码或条目右上角复制图标可单独复制；底部「一键复制」复制全部
- 格式记忆：自动记住上一次使用的格式（以及颜色、拼图列数、序号开关）
- 导出：高清拼图保存到相册，批量打包 ZIP 保存到下载目录
- 存档记录：最多保存 30 条历史，可恢复或删除

## 构建环境

- JDK 17
- Android SDK：`platforms;android-34`、`build-tools;34.0.0`
- SDK 路径写入 `local.properties`（已被 git 忽略），例如：
  `sdk.dir=/opt/android-sdk`

## 构建

```bash
# 构建 Debug APK
./gradlew :app:assembleDebug
```

产物路径：`app/build/outputs/apk/debug/app-debug.apk`

## 目录结构

- `app/src/main/java/com/barcode/factory/codec`：条码编码与绘制
- `app/src/main/java/com/barcode/factory/data`：偏好设置与存档持久化
- `app/src/main/java/com/barcode/factory/ui`：界面与适配器
- `app/src/main/java/com/barcode/factory/util`：文件导出工具
- `app/src/main/res`：布局、图标与资源

> 说明：仓库根目录的 `index.html` 为重构前的旧版页面，已不再被应用使用，可按需手动移除。
