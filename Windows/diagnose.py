#!/usr/bin/env python3
"""NFC 读卡器 - 网络诊断（修复版）"""
import socket
import sys
import subprocess
import threading
import time

PORT = 8888

def get_local_ips():
    ips = []
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(('8.8.8.8', 80))
        ips.append(('局域网IP', s.getsockname()[0]))
        s.close()
    except:
        pass
    return ips

def check_port_in_use(port):
    """检查端口是否被占用"""
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        s.bind(('0.0.0.0', port))
        s.close()
        return False  # 未被占用
    except OSError:
        return True  # 已被占用

def test_self_connect(port):
    """用独立端口测试自连"""
    try:
        srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        srv.bind(('127.0.0.1', port))
        srv.listen(1)
        srv.settimeout(5)

        result = [False]

        def client_thread():
            try:
                c = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
                c.settimeout(5)
                c.connect(('127.0.0.1', port))
                result[0] = True
                c.close()
            except Exception as e:
                print(f"  客户端错误: {e}")

        t = threading.Thread(target=client_thread, daemon=True)
        t.start()

        client, addr = srv.accept()
        srv.close()
        client.close()
        t.join(timeout=3)
        return result[0]
    except Exception as e:
        return False, str(e)

print("=" * 50)
print("NFC 读卡器 - 网络诊断")
print("=" * 50)

# 1. 本机 IP
print("\n[1] 本机 IP 地址:")
ips = get_local_ips()
for name, ip in ips:
    print(f"  {name}: {ip}")

# 2. 端口占用检查
print(f"\n[2] 端口 {PORT} 状态:")
in_use = check_port_in_use(PORT)
if in_use:
    print(f"  ⚠️ 端口 {PORT} 已被占用！")
    print(f"  如果 NFC 读卡器正在运行，这是正常的")
    print(f"  请先关闭 NFC 读卡器再测试")
    # 尝试列出占用进程
    try:
        result = subprocess.run(
            ['netstat', '-ano', '-p', 'tcp'],
            capture_output=True, text=True, encoding='gbk', timeout=10
        )
        for line in result.stdout.split('\n'):
            if f':{PORT}' in line and 'LISTENING' in line:
                pid = line.strip().split()[-1]
                print(f"  占用进程 PID: {pid}")
                # 获取进程名
                try:
                    proc = subprocess.run(
                        ['tasklist', '/fi', f'PID eq {pid}', '/fo', 'csv', '/nh'],
                        capture_output=True, text=True, encoding='gbk', timeout=5
                    )
                    if proc.stdout.strip():
                        print(f"  进程信息: {proc.stdout.strip().split(',')[0].strip('\"')}")
                except:
                    pass
                break
    except:
        pass
else:
    print(f"  ✅ 端口 {PORT} 空闲")

# 3. 自连测试（用随机高端口）
test_port = 19876
print(f"\n[3] 自连测试 (127.0.0.1:{test_port}):")
try:
    ok = test_self_connect(test_port)
    if ok:
        print("  ✅ 自连成功，本机 TCP 正常")
    else:
        print("  ❌ 自连失败")
except Exception as e:
    print(f"  ❌ 自连异常: {e}")

# 4. 模拟服务器绑定测试
print(f"\n[4] 模拟服务器绑定测试:")
try:
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(('0.0.0.0', 0))  # 系统分配端口
    srv.listen(1)
    actual_port = srv.getsockname()[1]
    print(f"  ✅ 绑定成功，实际端口: {actual_port}")
    print(f"  监听地址: 0.0.0.0:{actual_port}")
    srv.close()
except Exception as e:
    print(f"  ❌ 绑定失败: {e}")

# 5. 防火墙检查
print(f"\n[5] 防火墙检查:")
try:
    result = subprocess.run(
        ['netsh', 'advfirewall', 'show', 'currentprofile'],
        capture_output=True, text=True, encoding='gbk', timeout=10
    )
    for line in result.stdout.split('\n'):
        if '状态' in line or 'State' in line:
            print(f"  {line.strip()}")
except:
    print("  无法检查防火墙状态")

# 6. 总结
print("\n" + "=" * 50)
if in_use:
    print("诊断结果: 端口被占用")
    print(f"请先关闭占用端口 {PORT} 的程序，")
    print("或者修改 NFC 读卡器的端口设置。")
else:
    print("诊断结果: 端口空闲，自连正常")
    print("如果手机仍然连不上，请检查：")
    print("  1. 手机和电脑是否在同一 WiFi")
    print("  2. 手机填的 IP 是否正确")
    print("  3. Windows 防火墙是否放行 Python")
print("=" * 50)

input("\n按回车键退出...")
