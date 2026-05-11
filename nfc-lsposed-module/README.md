# NFC Reader LSPosed 模块

通过 LSPosed/Xposed Hook 系统 NFC 服务，强制将所有 NFC 标签发现事件转发给 NFC Reader 应用。

## 为什么需要这个模块

Android 的 NFC 框架设计上不允许后台应用接收 NFC 标签（安全考虑）。即使通过 `AndroidManifest.xml` 注册了 Intent Filter，系统 NFC 服务或默认支付应用仍可能拦截标签事件。

这个模块在系统 NFC 服务层面 Hook 标签分发流程，绕过 Android 的限制，实现真正的后台读卡。

## 构建

### 前提条件
- Android Studio
- LSPosed 框架已安装

### 步骤

1. 用 Android Studio 打开 `nfc-lsposed-module` 文件夹
2. 同步 Gradle，确保 Xposed API 依赖已下载
3. Build → Build Bundle(s) / APK(s) → Build APK(s)
4. 取出 `app/build/outputs/apk/release/app-release-unsigned.apk`

### 签名（如需）

```bash
# 生成签名密钥
keytool -genkey -v -keystore nfc-hook.keystore -alias nfcreader -keyalg RSA -validity 10000

# 签名 APK
jarsigner -verbose -sigalg SHA1withRSA -digestalg SHA1 -keystore nfc-hook.keystore app-release-unsigned.apk nfcreader
```

## 安装

1. 把 APK 复制到手机
2. 在 LSPosed 管理器中安装模块
3. **作用域选择**：勾选系统 NFC 服务包（通常是 `com.android.nfc`）
4. 启用模块，重启手机

### 各品牌作用域对照

| 品牌 | 作用域包名 |
|------|-----------|
| AOSP/Google | `com.android.nfc` |
| 三星 | `com.android.nfc` 或 `com.samsung.android.nfc` |
| 小米/红米 | `com.android.nfc` 或 `com.miui.nfc` |
| 华为 | `com.android.nfc` 或 `com.huawei.nfc` |
| OPPO/一加/realme | `com.android.nfc` 或 `com.coloros.nfc` 或 `com.oplus.nfc` |
| vivo/iQOO | `com.android.nfc` 或 `com.vivo.nfc` |
| 魅族 | `com.android.nfc` 或 `com.meizu.nfc` |

如果不确定，可以全选 `com.android.nfc` 和带 `nfc` 的系统包。

## 工作原理

```
NFC 芯片检测到标签
    ↓
系统 NFC 服务 (com.android.nfc)
    ↓
NfcDispatcher.dispatchTag() —— 【Hook 点】
    ↓
模块拦截，提取 UID 和卡类型
    ↓
强制启动 com.nfcreader/.ui.NfcBackgroundActivity
    ↓
NfcBackgroundActivity 通过 TCP 发送给电脑
```

## 调试

模块日志输出到 LSPosed 日志，可以在 LSPosed 管理器中查看。

搜索关键词 `NfcReaderHook` 可找到模块相关日志。

## 注意事项

1. **需要同时安装 NFC Reader 主应用** — 模块只负责转发，实际处理逻辑在主应用里
2. **首次配置后需要重启** — LSPosed 模块生效需要重启
3. **如果 Hook 不成功** — 可能是 ROM 深度定制，尝试在作用域里多勾选几个 NFC 相关包
