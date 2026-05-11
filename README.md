# NFC IC 卡读卡器

一个支持 Android 手机 + Windows 电脑的 NFC 读卡器工具。模拟 HID 键盘输入，将 UID 直接"打"到当前光标位置，就像真正的 IC 读卡器一样。

## 核心特性

- 🔑 **键盘模拟输入**：贴卡即输入，UID 直接输入到当前焦点位置
- 📋 **支持所有应用**：记事本、Excel、Word、网页表单、任何输入框
- 🎯 **多种格式可选**：HEX、十进制、倒序、WAHID 等
- ⚙️ **灵活配置**：前缀/后缀、自动回车、换行等
- 💾 **文件输出（可选）**：同时保存到文件作为备份
- 🖥️ **后台运行**：最小化到托盘，不影响其他操作
- 📱 **后台读卡**：App 退到后台仍能读卡（亮屏状态）
- 🔊 **读卡提示音**：后台读卡时播放确认音 + 震动反馈
- 🧩 **LSPosed 模块**：Root 设备可通过 LSPosed 深度集成，提升后台读卡稳定性

## 项目结构

```
nfc-ic-reader/
├── Android/                           # Android 端源码
│   ├── app/src/main/java/com/nfcreader/
│   │   ├── ui/
│   │   │   ├── MainActivity.kt        # 主界面
│   │   │   └── NfcBackgroundActivity.kt   # 透明后台 Activity
│   │   ├── service/
│   │   │   └── NfcForegroundService.kt    # 前台服务（保活 + 通知）
│   │   └── util/
│   │       ├── NfcConnectionManager.kt    # TCP 连接状态单例
│   │       ├── NfcTagHelper.kt            # UID 格式化 / 卡类型检测
│   │       ├── TcpClient.kt
│   │       └── TcpServer.kt
│   ├── nfc_diagnose.sh                # NFC 诊断脚本
│   └── nfc_root_helper.sh             # Root 设备辅助脚本
├── Windows/                           # Windows 端源码
│   ├── nfc_reader.py                  # PyQt5 主程序
│   ├── build.bat                      # 标准打包脚本
│   ├── build_optimized.bat            # 优化打包脚本（推荐）
│   ├── nfc_reader.spec                # 标准 PyInstaller 配置
│   ├── nfc_reader_optimized.spec      # 优化 PyInstaller 配置
│   └── diagnose.py                    # Windows 端诊断工具
├── nfc-lsposed-module/                # LSPosed/Xposed 模块
│   └── src/main/java/com/nfcreader/xposed/
│       └── NfcHookModule.kt           # Hook 系统 NFC 服务
└── README.md
```

## 使用步骤

### 1. 安装依赖

**Android 端**：
- 安装 APK 到手机
- 确保手机 NFC 功能已开启

**Windows 端**：
```bash
pip install PyQt5 keyboard
```

### 2. 运行程序

```bash
cd NFC读卡器/Windows
python nfc_reader.py
```

### 3. 选择连接模式

#### WiFi 模式
1. Windows 端选择 WiFi 模式，点击"启动服务器"
2. Android 端选择 WiFi 模式，输入电脑 IP 和端口
3. Android 端点击"连接"

#### ADB 模式
1. 手机 USB 连接电脑
2. Windows 端选择 ADB 模式
3. 设置端口，点击"创建转发并连接"
4. Android 端选择 ADB 模式，输入相同端口，点击"连接"

### 4. 开始使用

1. 确保手机和电脑已连接
2. 将任意输入框（记事本、Excel、浏览器等）置于前台
3. 将 IC 卡贴近手机 NFC 感应区
4. **UID 会自动输入到光标位置！**

## 后台读卡（App 不在前台也能读）

### 标准模式（无需 Root）

1. 打开 App，连接 Windows 端
2. 按 Home 键将 App 退到后台
3. **保持手机屏幕亮着**，贴卡即可读卡
4. 系统会弹出 NFC 选择器，选择本 App 即可

> **提示**：将本 App 设为默认 NFC 处理应用可跳过选择器。设置路径：系统设置 → NFC → 默认付款应用 / 触碰付款 → 选择 NFC读卡器

### LSPosed 模式（需要 Root）

对于 Root + LSPosed 用户，安装 LSPosed 模块可获得更稳定的后台读卡体验：

1. 编译 `nfc-lsposed-module/` 为 APK
2. 在 LSPosed 管理器中安装模块
3. **作用域勾选**：`Android 系统`、`NFC 服务`（包名因 ROM 而异，如 `com.android.nfc`、`com.miui.nfc` 等）
4. 重启手机
5. App 退到后台后，贴卡直接读卡，无需弹出选择器

## 设置说明

点击"设置"按钮打开配置：

### 键盘输入设置

| 选项 | 说明 |
|------|------|
| **输出格式** | HEX 无空格、HEX 带空格、HEX 倒序、十进制正序、十进制倒序、WAHID 门禁格式 |
| **前缀** | 无、换行(Tab前)、Tab、自定义 |
| **后缀** | 无、换行(Tab后)、Tab、自定义 |
| **自动回车** | 输入完成后自动按回车键 |

### 文件输出（辅助功能）

| 选项 | 说明 |
|------|------|
| 启用文件输出 | 同时将 UID 保存到文件 |
| 输出文件 | 文件路径（默认 nfc_output.txt） |
| 添加时间戳 | 每行添加时间前缀 |

### 其他设置

- **关闭窗口时最小化到托盘**：默认开启
- **启动时最小化到托盘**：程序启动后直接后台运行

## 输出格式示例

假设卡片 UID 为 `01 02 03 04`：

| 格式 | 输出示例 |
|------|---------|
| HEX 无空格 | `01020304` |
| HEX 带空格 | `01 02 03 04` |
| HEX 倒序无空格 | `04030201` |
| HEX 倒序带空格 | `04 03 02 01` |
| 十进制正序 | `16909060` |
| 十进制倒序 | `67305985` |
| WAHID 门禁格式 | `67305985` |

## 使用场景

### 场景 1：门禁卡号录入
在 Excel 中将光标放在 A 列，手机贴卡，UID 自动输入，按回车跳转下一行。

### 场景 2：网页表单填写
打开网页表单，将光标放在卡号输入框，贴卡即输入，无需切换窗口。

### 场景 3：快速记录
打开记事本，贴卡，UID + 时间戳自动记录到文件。

### 场景 4：后台批量录入
App 退到后台，保持屏幕亮着，连续贴卡，UID 自动输入到当前焦点位置，无需反复切换应用。

## 工作原理

```
标准模式（亮屏后台）：
┌─────────────┐   NFC标签   ┌─────────────┐   Intent    ┌─────────────┐
│   IC 卡     │ ─────────→ │  Android    │ ─────────→  │ NfcBackground│
│             │            │  系统NFC    │             │  Activity    │
└─────────────┘            └─────────────┘             └─────────────┘
                                                              │
                                                              ▼
                                                       ┌─────────────┐
                                                       │ TCP 发送    │
                                                       │ 到 Windows  │
                                                       └─────────────┘

LSPosed 模式（Hook 系统 NFC 服务）：
┌─────────────┐   NFC标签   ┌─────────────┐   Hook拦截   ┌─────────────┐
│   IC 卡     │ ─────────→ │  NfcService │ ─────────→  │ NfcHookModule│
│             │            │  (系统)     │  直接转发    │ (LSPosed)   │
└─────────────┘            └─────────────┘             └─────────────┘
                                                              │
                                                              ▼
                                                       ┌─────────────┐
                                                       │ NfcBackground│
                                                       │  Activity   │
                                                       └─────────────┘
```

## 编译 Android APK

```bash
cd Android
./gradlew assembleDebug
```

APK 生成在 `app/build/outputs/apk/debug/`

## 打包 Windows 端

### 标准打包（体积较大）
```bash
cd Windows
build.bat
```

### 优化打包（推荐，体积减少 50%~70%）
```bash
cd Windows
build_optimized.bat
```

优化点：
- 使用 `onedir` 替代 `onefile`，启动更快
- 排除 30+ 未使用的 PyQt5 子模块（QtWebEngine、3D、图表等）
- 排除 numpy、pandas、matplotlib、scipy 等大型库
- 自动检测并启用 UPX 压缩

## 注意事项

1. **管理员权限**：keyboard 库可能需要管理员权限运行
2. **焦点问题**：确保目标输入框获得焦点
3. **输入法干扰**：某些输入法可能会拦截键盘事件
4. **USB 调试**：ADB 模式需要开启 USB 调试
5. **后台读卡限制**：息屏时大部分手机 NFC 芯片会硬件断电，软件无法绕过
6. **电池优化**：后台读卡需要关闭本 App 的电池优化，否则系统可能杀进程

## 技术栈

- **Android**：Kotlin, android.nfc, Material Design, LSPosed API
- **Windows**：Python 3, PyQt5, keyboard 库

## 许可证

MIT License
