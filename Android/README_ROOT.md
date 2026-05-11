# NFC 后台读卡 Root 方案

## 为什么 Intent Filter 方案还是不行？

即使正确配置了 `AndroidManifest.xml` 的 Intent Filter，后台读卡仍可能失败，原因是：

1. **系统 NFC 支付应用** — 手机绑定了微信/支付宝/钱包作为默认 NFC 支付应用，系统会优先把标签发给它们
2. **OEM 定制** — 小米、华为、OPPO 等厂商修改了 NFC 分发逻辑，加了白名单机制
3. **系统 NFC Service 拦截** — `com.android.nfc` 服务在框架层就拦截了标签，根本没走到 Intent 分发

## 诊断步骤

先把 `nfc_diagnose.sh` push 到手机运行，看具体是什么在拦你：

```bash
adb push nfc_diagnose.sh /sdcard/
adb shell su -c "sh /sdcard/nfc_diagnose.sh"
```

重点关注输出里的 **默认支付应用** 和 **竞争应用** 两部分。

## Root 解决方案

### 方案一：禁用竞争应用（推荐先试试）

```bash
adb push nfc_root_helper.sh /sdcard/
adb shell su -c "sh /sdcard/nfc_root_helper.sh disable-competitors"
```

这会禁用常见的 NFC 钱包/支付应用（微信、支付宝、各厂商钱包等），但保留系统 NFC 框架本身。

### 方案二：清除默认 NFC 支付应用

```bash
adb shell su -c "settings put secure nfc_payment_default_component null"
```

### 方案三：启动监控守护进程（兜底）

如果上述不行，启动一个后台脚本强制把 NFC 事件转发给我们的 App：

```bash
adb shell su -c "sh /sdcard/nfc_root_helper.sh start"
```

这会每 0.5 秒扫描一次 NFC 事件日志，检测到读卡就强制启动 `NfcBackgroundActivity`。

> ⚠️ 这个方案会略微增加耗电，因为持续读取 logcat。

### 方案四：最暴力的方案（停用系统 NFC 服务）

**警告：这会完全停用系统 NFC 框架，其他 NFC 功能（门禁卡、公交卡）也会失效。**

```bash
adb shell su -c "pm disable com.android.nfc"
```

停用后，需要我们自己直接操作 NFC 控制器。这需要在 App 里加原生代码访问 `/dev/pn544` 等设备节点，工作量较大。

恢复系统 NFC：
```bash
adb shell su -c "pm enable com.android.nfc"
```

## 建议的尝试顺序

1. 先跑诊断脚本，确认问题根源
2. 清除默认支付应用（方案二）
3. 禁用竞争应用（方案一）
4. 如果还不行，启动监控守护进程（方案三）
5. 终极方案：方案四 + 原生 NFC 控制器访问（需要额外开发）

## 长期方案：LSPosed/Xposed 模块（最优雅）

如果你装了 LSPosed 或 Xposed，可以写一个模块 Hook `NfcService.dispatchTagEndpoint()`，在系统分发 NFC 标签时直接转发给我们的 App。这个方案：
- 不需要禁用任何应用
- 不影响其他 NFC 功能
- 耗电最低

但需要会写 Xposed 模块，如果需要我可以提供代码模板。
