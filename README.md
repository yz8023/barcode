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

产物路径：`app/build/outputs/apk/debug/`（按 CPU 架构拆分，另有通用包）

## 目录结构

- `app/src/main/java/com/barcode/factory/codec`：条码编码与绘制
- `app/src/main/java/com/barcode/factory/data`：偏好设置与存档持久化
- `app/src/main/java/com/barcode/factory/ui`：界面与适配器
- `app/src/main/java/com/barcode/factory/util`：文件导出工具
- `app/src/main/res`：布局、图标与资源

> 说明：仓库根目录的 `index.html` 为重构前的旧版页面，已不再被应用使用，可按需手动移除。

## 一键去重、剪贴板与图片识别

- 首页 **一键去重**：以去除首尾空白后的每行内容比较，区分大小写，保留首次出现的内容及其原有顺序。空行保留。
- 首页 **读取剪贴板**：点击后读取文本，按行追加至输入框；仍可直接编辑输入框。
- 点击 **扫码 → 从图片识别（可多选）** 读取一张或多张图片；长图分块处理，识别后先预览确认。即使拒绝相机权限，也可在扫码页使用图片识别。
- 图片识别保留不同位置上的相同内容；同一个码在重叠分块中重复识别时去重。顺序取决于系统选择器；单图超过 1024 个分块会提示分割。模糊、尺寸过小或跨分块的条码仍可能漏检。
- 识别支持二维码等格式，但本应用生成条码格式仍是原来的七种；不支持的格式可复制文本。含换行的内容添加到首页时会按行拆分。
- 扫码使用原始相机帧并匹配取景框，防止同一次扫码重复回传；不禁止用户主动再次扫描同一个码。

### 安装包体积

Release 开启 R8 和资源裁剪，并按 CPU 拆分为 `arm64-v8a`、`armeabi-v7a` 两个较小 APK，同时保留包含两种架构的 universal APK。现代 64 位 ARM 手机一般选择 `arm64-v8a`；较旧的 32 位 ARM 设备选择 `armeabi-v7a`；不确定时使用 universal。内置 ML Kit 模型仍保留，避免依赖在线下载和 Google Play 服务。

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

v1.5 根据项目要求使用新的独立签名密钥；其证书 SHA-256 指纹为 `9ba924706efa6a68c0a1861c2599b64fe121950a2d5cfb14e5f32f3d9ce4bd46`，与 v1.3 不同，不能覆盖安装旧签名版本。以后更新 v1.5 必须保管并使用 v1.5 对应密钥，不要将该密钥或口令提交、上传或公开。
