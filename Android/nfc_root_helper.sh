#!/system/bin/sh
# NFC Root Helper - 通过 root 权限强制接管 NFC 后台读卡
# 用法: adb shell su -c "sh /sdcard/nfc_root_helper.sh [start|stop|status|disable-competitors|enable-all]"

APP_PACKAGE="com.nfcreader"
APP_ACTIVITY="com.nfcreader.ui.NfcBackgroundActivity"
LOG_TAG="NfcRootHelper"

# 常见的系统 NFC 竞争包名（支付、钱包、交通卡等）
COMPETITORS="
com.google.android.gms
com.google.android.apps.walletnfcrel
com.samsung.android.spay
com.huawei.wallet
com.miui.tsmclient
com.coloros.wallet
com.vivo.wallet
com.oneplus.wallet
com.android.nfc
com.gsma.services.nfc
com.sec.android.app.nfc
"

log_info() {
    echo "[NfcRootHelper] $1"
    log -t "$LOG_TAG" "$1"
}

# 检查 root 权限
check_root() {
    if [ "$(id -u)" != "0" ]; then
        echo "错误: 需要 root 权限运行"
        exit 1
    fi
}

# 获取当前默认 NFC 应用
get_default_nfc() {
    echo "=== 当前默认 NFC 应用 ==="
    # 尝试读取 NFC 默认支付应用
    settings get secure nfc_payment_default_component 2>/dev/null || echo "未设置"
    echo ""
}

# 列出所有 NFC 相关包
list_nfc_packages() {
    echo "=== NFC 相关包 ==="
    pm list packages | grep -i nfc
    echo ""
}

# 列出正在运行的 NFC 服务
list_nfc_services() {
    echo "=== 正在运行的 NFC 服务 ==="
    ps -A | grep -i nfc
    echo ""
}

# 禁用竞争 NFC 应用（保留 com.android.nfc 框架）
disable_competitors() {
    log_info "正在禁用竞争 NFC 应用..."
    for pkg in $COMPETITORS; do
        if pm list packages | grep -q "$pkg"; then
            if [ "$pkg" != "com.android.nfc" ]; then
                pm disable-user --user 0 "$pkg" 2>/dev/null && log_info "已禁用: $pkg" || log_info "禁用失败或已禁用: $pkg"
            fi
        fi
    done
    log_info "竞争应用处理完成"
}

# 启用所有 NFC 应用
enable_all() {
    log_info "正在启用所有 NFC 应用..."
    for pkg in $COMPETITORS; do
        if pm list packages | grep -q "$pkg"; then
            pm enable --user 0 "$pkg" 2>/dev/null && log_info "已启用: $pkg" || true
        fi
    done
    log_info "全部启用完成"
}

# 强制启动后台 Activity 处理 NFC 标签
force_dispatch() {
    log_info "强制分发 NFC 事件到应用..."
    am start -n "$APP_PACKAGE/$APP_ACTIVITY" \
        -a android.nfc.action.TAG_DISCOVERED \
        --ez "com.nfcreader.FORCE_DISPATCH" true \
        -f 0x10000000 2>/dev/null
}

# 启动 NFC 监控守护进程（轮询方式）
start_monitor() {
    log_info "启动 NFC 监控守护进程..."
    
    # 创建监控脚本
    cat > /data/local/tmp/nfc_monitor.sh << 'MONITOR_EOF'
#!/system/bin/sh
APP_PACKAGE="com.nfcreader"
APP_ACTIVITY="com.nfcreader.ui.NfcBackgroundActivity"
LAST_TAG=""

while true; do
    # 通过 logcat 检测 NFC 标签事件
    # 注意: 这会持续读取 logcat，可能较耗电
    TAG_EVENT=$(logcat -d -b events -t 100 | grep -i "nfc.*discovered\|tag.*detected" | tail -1)
    
    if [ -n "$TAG_EVENT" ] && [ "$TAG_EVENT" != "$LAST_TAG" ]; then
        LAST_TAG="$TAG_EVENT"
        # 强制启动我们的 Activity
        am start -n "$APP_PACKAGE/$APP_ACTIVITY" \
            -a android.nfc.action.TAG_DISCOVERED \
            -f 0x10000000 2>/dev/null
    fi
    
    sleep 0.5
done
MONITOR_EOF

    chmod +x /data/local/tmp/nfc_monitor.sh
    
    # 在后台启动
    nohup sh /data/local/tmp/nfc_monitor.sh > /dev/null 2>&1 &
    
    log_info "监控进程已启动 (PID: $!)"
}

# 停止监控守护进程
stop_monitor() {
    log_info "停止 NFC 监控守护进程..."
    pkill -f "nfc_monitor.sh" 2>/dev/null
    log_info "监控进程已停止"
}

# 状态检查
status() {
    echo "=== NFC Root Helper 状态 ==="
    echo ""
    get_default_nfc
    list_nfc_packages
    list_nfc_services
    
    echo "=== 监控进程 ==="
    ps -A | grep -i "nfc_monitor" || echo "未运行"
    echo ""
    
    echo "=== 本应用状态 ==="
    pm dump "$APP_PACKAGE" 2>/dev/null | grep -A 5 "disabled" || echo "应用状态正常"
}

# 主逻辑
check_root

case "${1:-status}" in
    start)
        start_monitor
        ;;
    stop)
        stop_monitor
        ;;
    status)
        status
        ;;
    disable-competitors)
        disable_competitors
        ;;
    enable-all)
        enable_all
        ;;
    force-dispatch)
        force_dispatch
        ;;
    *)
        echo "用法: $0 [start|stop|status|disable-competitors|enable-all|force-dispatch]"
        echo ""
        echo "命令说明:"
        echo "  start              - 启动 NFC 监控守护进程"
        echo "  stop               - 停止监控守护进程"
        echo "  status             - 查看 NFC 相关状态"
        echo "  disable-competitors - 禁用竞争 NFC 应用（保留系统框架）"
        echo "  enable-all         - 启用所有 NFC 应用"
        echo "  force-dispatch     - 强制分发 NFC 事件到本应用"
        exit 1
        ;;
esac
