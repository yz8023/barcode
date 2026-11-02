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

## 剪贴板、图片识别与扫码修复

本次改动以原版 `6386f82` 为基础，保留原有首页、条码预览、高清拼图预览和导出功能。

- 点击首页 **手动添加**，可手动输入多行，或选择 **从剪贴板读取** / **从图片识别（可多选）**。读取结果先进入输入框，检查后点击“添加”。
- 图片逐张识别：长图采用重叠分块，并尝试整图缩略识别以补充大条码；按图片提供顺序、图片内阅读顺序排列。重叠分块识别到同一个位置的同一个码只保留一次，但图片中不同位置的相同内容会保留。选择的图片排序取决于系统图片选择器。
- 图片可以是包含二维码等的图片；但本应用生成条码的格式仍然是首页原有的几种。识别到的格式不一定都能在当前格式下生成，数据仍可复制。包含换行的识别内容，在添加到每行一条的数据列表时会按行拆分。
- 图片识别支持停止，单图失败不会中断其他图片。每张图片最多 1024 个重叠分块，超过时提示分割而不静默截断；图片不清晰、条码跨分块或分辨率太低时仍可能漏识别。
- 扫码用原始相机帧供 ML Kit 识别，将结果映射到屏幕识别框，并防止一次扫码启动重复页面或重复提交。为保留原用途，不禁止用户主动再次扫描同一内容。非空列表扫码不再擅自切换整批数据的格式。

### 验证

```
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest
# 连接设备后运行（图片识别集成测试）：
./gradlew :app:connectedDebugAndroidTest
```

扫码准确度、弱光、多码、横竖屏、相机权限及多图/长图识别均需在实际 Android 设备上验收；不同设备与图片条件下不能保证全部识别。Debug APK 使用调试签名，不能覆盖不同签名的正式版，请勿为了安装而直接卸载正式版以免丢失本地数据。

### Release 签名

Release 需要原发布密钥才能作为已安装版本升级。不要把 keystore 或密码提交到仓库。
在项目根目录创建本地 `keystore.properties`（已忽略）：

```properties
storeFile=keystore/release.jks
storePassword=本地口令
keyAlias=密钥别名
keyPassword=本地密钥口令
```

也可分别通过环境变量 `RELEASE_STORE_FILE`、`RELEASE_STORE_PASSWORD`、`RELEASE_KEY_ALIAS`、`RELEASE_KEY_PASSWORD` 提供，环境变量优先。没有完整配置时，`assembleRelease` 生成**未签名 APK**，不能直接安装或发布作为更新。构建后应使用 Android SDK 的 `apksigner verify --print-certs` 验证签名，并和已发布版本的签名证书指纹对比。旧提交中暴露过的口令仍在历史中；应检查密钥状态并安全轮换，切勿直接更换签名导致用户无法升级。

官方 GitHub 发布的 `v1.3` APK 的签名证书 SHA-256 指纹（用于比对，不是私钥）：`decbabfb5ddfee7088f1c19c76a7acf267398375f7bdd892330376db0b7900f8`。实际发布前仍应从用户当前安装版本或可信发布渠道独立核验。
