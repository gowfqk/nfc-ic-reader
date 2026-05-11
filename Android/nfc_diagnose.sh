#!/system/bin/sh
# NFC 诊断脚本 - 排查后台读卡失败原因
# 用法: adb shell su -c "sh /sdcard/nfc_diagnose.sh"

echo "========== NFC 诊断报告 =========="
echo "时间: $(date)"
echo ""

# 1. 检查本应用是否安装
echo "--- 1. 本应用安装状态 ---"
if pm list packages | grep -q "com.nfcreader"; then
    echo "[OK] com.nfcreader 已安装"
    pm dump com.nfcreader | grep -E "enabled|disabled" | head -5
else
    echo "[ERROR] com.nfcreader 未安装!"
fi
echo ""

# 2. 检查默认 NFC 支付应用
echo "--- 2. 默认 NFC 支付应用 ---"
DEFAULT_PAY=$(settings get secure nfc_payment_default_component 2>/dev/null)
if [ -z "$DEFAULT_PAY" ] || [ "$DEFAULT_PAY" = "null" ]; then
    echo "[OK] 未设置默认支付应用"
else
    echo "[WARN] 默认支付应用: $DEFAULT_PAY"
    echo "      这会拦截 NFC 标签，导致其他应用收不到"
fi
echo ""

# 3. 检查系统 NFC 服务状态
echo "--- 3. 系统 NFC 服务 ---"
if service list | grep -qi nfc; then
    echo "[INFO] NFC 服务运行中:"
    service list | grep -i nfc
else
    echo "[WARN] 未检测到 NFC 服务"
fi
echo ""

# 4. 检查 NFC 相关进程
echo "--- 4. NFC 相关进程 ---"
ps -A | grep -i nfc || echo "无 NFC 进程"
echo ""

# 5. 检查本应用 Activity 注册
echo "--- 5. 本应用 NFC Intent Filter ---"
pm dump com.nfcreader | grep -A 20 "android.nfc.action" || echo "未找到 NFC Intent Filter"
echo ""

# 6. 检查是否有其他应用注册了 NFC Intent Filter
echo "--- 6. 竞争应用（也注册了 NFC） ---"
for pkg in $(pm list packages | cut -d: -f2); do
    FILTERS=$(pm dump "$pkg" 2>/dev/null | grep "android.nfc.action" | head -3)
    if [ -n "$FILTERS" ]; then
        echo "[$pkg]"
        echo "$FILTERS"
    fi
done
echo ""

# 7. 检查最近的 NFC 相关日志
echo "--- 7. 最近 NFC 日志 (最近 50 条) ---"
logcat -d -t 50 | grep -i nfc | tail -20 || echo "无相关日志"
echo ""

# 8. 检查设备 NFC 节点
echo "--- 8. NFC 设备节点 ---"
ls -la /dev/pn* /dev/nfc* 2>/dev/null || echo "无标准 NFC 设备节点"
echo ""

# 9. 检查 SELinux 状态
echo "--- 9. SELinux 状态 ---"
getenforce 2>/dev/null || echo "无法获取"
echo ""

echo "========== 诊断完成 =========="
echo ""
echo "常见问题:"
echo "1. 如果有 [WARN] 默认支付应用，需要清除或禁用"
echo "2. 如果有其他应用也注册了 NFC，可能产生竞争"
echo "3. 如果 SELinux 是 Enforcing，root 操作可能受限"
