#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
NFC 读卡器 Windows 端
模拟 HID 键盘输入，将 UID 直接输入到当前光标位置

作者: NFC Reader Team
版本: 2.0
"""

import sys
import json
import os
import socket
import select
import threading
import subprocess
from datetime import datetime
from typing import Optional, List, Tuple

# 尝试导入键盘库
try:
    import keyboard
    KEYBOARD_AVAILABLE = True
except ImportError:
    KEYBOARD_AVAILABLE = False
    print("警告: keyboard 库未安装，键盘模拟功能不可用")
    print("请运行: pip install keyboard")

from PyQt5.QtWidgets import (
    QApplication, QMainWindow, QWidget, QVBoxLayout, QHBoxLayout,
    QLabel, QLineEdit, QGroupBox, QPushButton, QCheckBox,
    QRadioButton, QComboBox, QSystemTrayIcon, QMenu, QAction,
    QDialog, QGridLayout, QFileDialog, QStatusBar, QMessageBox
)
from PyQt5.QtCore import Qt, QTimer, QSettings
from PyQt5.QtGui import QIcon, QColor, QPalette

# 配置常量
DEFAULT_WIFI_PORT = 8888
DEFAULT_ADB_LOCAL_PORT = 8888
DEFAULT_OUTPUT_FILE = "nfc_output.txt"


class AdbHelper:
    """ADB 工具类"""
    
    @staticmethod
    def is_adb_available() -> bool:
        """检查 ADB 是否可用"""
        try:
            result = subprocess.run(
                ['adb', 'version'],
                capture_output=True,
                text=True,
                encoding='utf-8',
                errors='replace',
                timeout=5
            )
            ok = result.returncode == 0
            print(f'[ADB] version 检查: {"✓" if ok else "✗"} (rc={result.returncode})')
            return ok
        except FileNotFoundError:
            print('[ADB] ✗ adb 命令未找到')
            return False
        except subprocess.SubprocessError as e:
            print(f'[ADB] ✗ version 检查失败: {e}')
            return False
    
    @staticmethod
    def get_devices() -> List[Tuple[str, str]]:
        """获取已连接的设备列表"""
        try:
            result = subprocess.run(
                ['adb', 'devices'],
                capture_output=True,
                text=True,
                encoding='utf-8',
                errors='replace',
                timeout=10
            )
            
            devices = []
            lines = result.stdout.strip().split('\n')[1:]
            
            for line in lines:
                if line.strip():
                    parts = line.split()
                    if len(parts) >= 2:
                        devices.append((parts[0], parts[1]))
            
            return devices
        except (subprocess.SubprocessError, FileNotFoundError):
            return []
    
    @staticmethod
    def forward(local_port: int, remote_port: int) -> bool:
        """创建端口转发"""
        try:
            print(f'[ADB] 创建转发: tcp:{local_port} -> tcp:{remote_port}')
            result = subprocess.run(
                ['adb', 'forward', f'tcp:{local_port}', f'tcp:{remote_port}'],
                capture_output=True,
                text=True,
                encoding='utf-8',
                errors='replace',
                timeout=10
            )
            ok = result.returncode == 0
            print(f'[ADB] 转发结果: {"✓" if ok else "✗"} (rc={result.returncode})')
            if not ok:
                print(f'[ADB] stdout: {result.stdout.strip()}')
                print(f'[ADB] stderr: {result.stderr.strip()}')
            return ok
        except FileNotFoundError:
            print('[ADB] ✗ adb 命令未找到')
            return False
        except subprocess.SubprocessError as e:
            print(f'[ADB] ✗ 转发失败: {e}')
            return False
    
    @staticmethod
    def forward_remove(local_port: int) -> bool:
        """移除端口转发"""
        try:
            result = subprocess.run(
                ['adb', 'forward', '--remove', f'tcp:{local_port}'],
                capture_output=True,
                text=True,
                encoding='utf-8',
                errors='replace',
                timeout=5
            )
            return result.returncode == 0
        except subprocess.SubprocessError:
            return False
    
    @staticmethod
    def connect(ip: str, port: int = 5555) -> bool:
        """无线 ADB 连接"""
        try:
            result = subprocess.run(
                ['adb', 'connect', f'{ip}:{port}'],
                capture_output=True,
                text=True,
                encoding='utf-8',
                errors='replace',
                timeout=10
            )
            return result.returncode == 0 and 'connected' in result.stdout.lower()
        except subprocess.SubprocessError:
            return False
    
    @staticmethod
    def disconnect(ip: str, port: int = 5555) -> bool:
        """断开无线 ADB"""
        try:
            result = subprocess.run(
                ['adb', 'disconnect', f'{ip}:{port}'],
                capture_output=True,
                text=True,
                encoding='utf-8',
                errors='replace',
                timeout=5
            )
            return result.returncode == 0
        except subprocess.SubprocessError:
            return False


class TcpServer:
    """TCP 服务器（WiFi 模式）"""
    
    def __init__(self, port: int, callback):
        self.port = port
        self.callback = callback
        self.server_socket: Optional[socket.socket] = None
        self.client_socket: Optional[socket.socket] = None
        self.is_running = False
        self.thread: Optional[threading.Thread] = None
        self._lock = threading.Lock()
    
    def start(self):
        """启动服务器"""
        if self.is_running:
            return
        
        self.thread = threading.Thread(target=self._run_server, daemon=True)
        self.thread.start()
    
    def _run_server(self):
        """服务器主循环"""
        try:
            print(f'[TcpServer] 正在创建 socket...')
            self.server_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            self.server_socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            print(f'[TcpServer] 正在绑定 0.0.0.0:{self.port}...')
            self.server_socket.bind(('0.0.0.0', self.port))
            self.server_socket.listen(1)
            self.is_running = True
            print(f'[TcpServer] ✓ 已启动，监听端口 {self.port}，等待手机连接...')
            
            self.callback.on_status_changed(f'等待手机连接... (监听 0.0.0.0:{self.port})')
            
            while self.is_running:
                try:
                    self.server_socket.settimeout(1.0)
                    try:
                        client, addr = self.server_socket.accept()
                        print(f'[TcpServer] ✓ 手机已连接! 来自 {addr[0]}:{addr[1]}')
                        client.setsockopt(socket.SOL_SOCKET, socket.SO_KEEPALIVE, 1)
                        with self._lock:
                            self.client_socket = client
                        print(f'[TcpServer] 正在通知 UI...')
                        self.callback.on_client_connected(addr[0])
                        print(f'[TcpServer] 开始接收数据...')
                        self._receive_data(client)
                    except socket.timeout:
                        continue
                    except Exception as e:
                        if self.is_running:
                            print(f'[TcpServer] accept 异常: {type(e).__name__}: {e}')
                        
                except Exception as e:
                    if self.is_running:
                        print(f'[TcpServer] 连接错误: {type(e).__name__}: {e}')
                        self.callback.on_error(f'连接错误: {e}')
        
        except OSError as e:
            print(f'[TcpServer] ✗ 绑定失败: {e}')
            self.callback.on_error(f'端口 {self.port} 绑定失败: {e}')
        except Exception as e:
            print(f'[TcpServer] ✗ 服务器错误: {type(e).__name__}: {e}')
            self.callback.on_error(f'服务器错误: {e}')
        finally:
            print('[TcpServer] 服务器已关闭')
            self._cleanup()
    
    def _receive_data(self, client: socket.socket):
        """接收客户端数据（阻塞模式，关闭 socket 中断）"""
        buffer = ''
        try:
            peer = client.getpeername()
            print(f'[TcpServer] 开始接收数据 from {peer}')
        except Exception:
            peer = '?'
        while self.is_running:
            try:
                # 阻塞 recv，不用 settimeout 也不用 select
                # 需要停止时由 stop() 关闭 socket 让 recv 抛异常退出
                data = client.recv(1024)
                if not data:
                    print(f'[TcpServer] 客户端 {peer} 断开（空数据）')
                    break
                
                text = data.decode('utf-8')
                buffer += text
                print(f'[TcpServer] 收到数据: {repr(text[:100])}')
                
                while '\n' in buffer:
                    line, buffer = buffer.split('\n', 1)
                    if line.strip():
                        print(f'[TcpServer] 解析: {line.strip()}')
                        self.callback.on_data_received(line.strip())
                        
            except Exception as e:
                print(f'[TcpServer] recv 异常: {type(e).__name__}: {e}')
                break
        
        try:
            client.close()
        except Exception:
            pass
        with self._lock:
            if self.client_socket is client:
                self.client_socket = None
        print(f'[TcpServer] 客户端 {peer} 连接结束')
        if self.is_running:
            self.callback.on_client_disconnected()
    
    def stop(self):
        """停止服务器"""
        self.is_running = False
        # 关闭所有 socket，让阻塞的 accept/recv 抛异常退出
        with self._lock:
            if self.client_socket:
                try:
                    self.client_socket.close()
                except Exception:
                    pass
                self.client_socket = None
            if self.server_socket:
                try:
                    self.server_socket.close()
                except Exception:
                    pass
                self.server_socket = None
    
    def _cleanup(self):
        """清理资源（仅由 _run_server 的 finally 调用）"""
        with self._lock:
            if self.client_socket:
                try:
                    self.client_socket.close()
                except Exception:
                    pass
                self.client_socket = None
            if self.server_socket:
                try:
                    self.server_socket.close()
                except Exception:
                    pass
                self.server_socket = None


class TcpClient:
    """TCP 客户端（ADB 模式）"""
    
    def __init__(self, host: str, port: int, callback):
        self.host = host
        self.port = port
        self.callback = callback
        self.socket: Optional[socket.socket] = None
        self.is_running = False
        self.thread: Optional[threading.Thread] = None
        self._lock = threading.Lock()
    
    def connect(self):
        """连接到服务器"""
        if self.is_running:
            return
        
        self.is_running = True
        self.thread = threading.Thread(target=self._run_client, daemon=True)
        self.thread.start()
    
    def _run_client(self):
        """客户端主循环（带自动重连）"""
        while self.is_running:
            try:
                sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
                sock.setsockopt(socket.SOL_SOCKET, socket.SO_KEEPALIVE, 1)
                sock.connect((self.host, self.port))
                with self._lock:
                    self.socket = sock
                self.callback.on_connected()
                self._receive_data(sock)
                
            except ConnectionRefusedError:
                self.callback.on_status_changed('连接被拒绝，2秒后重试...')
            except socket.timeout:
                self.callback.on_status_changed('连接超时，2秒后重试...')
            except Exception as e:
                if self.is_running:
                    self.callback.on_error(f'连接错误: {e}，2秒后重试...')
            
            # 断开后等待重连
            if self.is_running:
                import time
                time.sleep(2)
    
    def _receive_data(self, sock: socket.socket):
        """接收数据（阻塞模式，关闭 socket 中断）"""
        buffer = ''
        while self.is_running:
            try:
                data = sock.recv(1024).decode('utf-8')
                if not data:
                    break
                
                buffer += data
                
                while '\n' in buffer:
                    line, buffer = buffer.split('\n', 1)
                    if line.strip():
                        self.callback.on_data_received(line.strip())
                        
            except Exception:
                break
        
        try:
            sock.close()
        except Exception:
            pass
        with self._lock:
            if self.socket is sock:
                self.socket = None
    
    def disconnect(self):
        """断开连接"""
        self.is_running = False
        with self._lock:
            if self.socket:
                try:
                    self.socket.close()
                except Exception:
                    pass
                self.socket = None


class KeyboardSimulator:
    """键盘模拟器 - 模拟 HID 读卡器行为"""
    
    def __init__(self):
        self.keyboard_available = KEYBOARD_AVAILABLE
    
    def type_text(self, text: str):
        """模拟键盘输入文本"""
        if not self.keyboard_available:
            print(f"[KeyboardSimulator] 无法输入，keyboard库不可用: {text}")
            return False
        
        try:
            # 使用 write 一次性输入，速度更快更稳定
            keyboard.write(text, delay=0.01)
            return True
        except Exception as e:
            print(f"[KeyboardSimulator] 输入失败: {e}")
            return False
    
    def press_key(self, key: str):
        """按下单个按键"""
        if not self.keyboard_available:
            return False
        
        try:
            keyboard.press_and_release(key)
            return True
        except Exception as e:
            print(f"[KeyboardSimulator] 按键失败: {e}")
            return False
    
    def type_enter(self):
        """模拟回车键"""
        return self.press_key('enter')
    
    def type_tab(self):
        """模拟 Tab 键"""
        return self.press_key('tab')


class UidFormatter:
    """UID 格式化工具类"""
    
    @staticmethod
    def format_hex_with_space(uid: str) -> str:
        """十六进制正序（带空格）"""
        return ' '.join(uid[i:i+2] for i in range(0, len(uid), 2))
    
    @staticmethod
    def format_hex_no_space(uid: str) -> str:
        """十六进制正序（无空格）"""
        return uid.upper()
    
    @staticmethod
    def format_hex_reverse_with_space(uid: str) -> str:
        """十六进制倒序（带空格）"""
        reversed_uid = ''.join(reversed([uid[i:i+2] for i in range(0, len(uid), 2)]))
        return ' '.join(reversed_uid[i:i+2] for i in range(0, len(reversed_uid), 2))
    
    @staticmethod
    def format_hex_reverse_no_space(uid: str) -> str:
        """十六进制倒序（无空格）"""
        return ''.join(reversed([uid[i:i+2] for i in range(0, len(uid), 2)]))
    
    @staticmethod
    def format_decimal_normal(uid: str) -> str:
        """十进制正序"""
        return str(int(uid, 16))
    
    @staticmethod
    def format_decimal_reverse(uid: str) -> str:
        """十进制倒序"""
        reversed_hex = ''.join(reversed([uid[i:i+2] for i in range(0, len(uid), 2)]))
        return str(int(reversed_hex, 16))
    
    @staticmethod
    def format_wahid(uid: str) -> str:
        """WAHID 格式（门禁常用，倒序十进制）"""
        return UidFormatter.format_decimal_reverse(uid)
    
    @staticmethod
    def get_formatted(uid: str, format_key: str) -> str:
        """获取指定格式的 UID"""
        formats = {
            'hex_with_space': UidFormatter.format_hex_with_space,
            'hex_no_space': UidFormatter.format_hex_no_space,
            'hex_reverse_with_space': UidFormatter.format_hex_reverse_with_space,
            'hex_reverse_no_space': UidFormatter.format_hex_reverse_no_space,
            'decimal_normal': UidFormatter.format_decimal_normal,
            'decimal_reverse': UidFormatter.format_decimal_reverse,
            'wahid': UidFormatter.format_wahid,
        }
        
        formatter = formats.get(format_key, UidFormatter.format_hex_no_space)
        return formatter(uid)


class SettingsDialog(QDialog):
    """设置对话框"""
    
    def __init__(self, settings: dict, parent=None):
        super().__init__(parent)
        self.settings = settings
        self.init_ui()
    
    def init_ui(self):
        """初始化设置界面"""
        self.setWindowTitle('设置')
        self.setMinimumWidth(450)
        self.setWindowFlags(Qt.WindowFlags(Qt.Dialog | Qt.WindowCloseButtonHint))
        
        layout = QVBoxLayout(self)
        layout.setSpacing(15)
        
        # 键盘输入设置
        keyboard_group = QGroupBox('键盘输入设置')
        keyboard_layout = QVBoxLayout(keyboard_group)
        
        # 输出格式
        self.FORMAT_OPTIONS = [
            ('hex_no_space', 'HEX 无空格 (如 01020304)'),
            ('hex_with_space', 'HEX 带空格 (如 01 02 03 04)'),
            ('hex_reverse_no_space', 'HEX 倒序无空格 (如 04030201)'),
            ('hex_reverse_with_space', 'HEX 倒序带空格 (如 04 03 02 01)'),
            ('decimal_normal', '十进制正序'),
            ('decimal_reverse', '十进制倒序'),
            ('wahid', 'WAHID 门禁格式'),
        ]
        format_layout = QHBoxLayout()
        format_layout.addWidget(QLabel('输出格式：'))
        self.format_combo = QComboBox()
        for key, label in self.FORMAT_OPTIONS:
            self.format_combo.addItem(label, key)
        self.format_combo.setCurrentIndex(0)
        format_layout.addWidget(self.format_combo)
        keyboard_layout.addLayout(format_layout)
        
        # 前缀
        prefix_layout = QHBoxLayout()
        prefix_layout.addWidget(QLabel('前缀：'))
        self.prefix_combo = QComboBox()
        self.prefix_combo.addItems(['无', '换行 (Enter前)', 'Tab', '自定义'])
        self.prefix_combo.currentIndexChanged.connect(self.on_prefix_changed)
        prefix_layout.addWidget(self.prefix_combo)
        
        self.prefix_custom = QLineEdit()
        self.prefix_custom.setPlaceholderText('自定义前缀')
        self.prefix_custom.setMaximumWidth(100)
        self.prefix_custom.setVisible(False)
        prefix_layout.addWidget(self.prefix_custom)
        prefix_layout.addStretch()
        keyboard_layout.addLayout(prefix_layout)
        
        # 后缀
        suffix_layout = QHBoxLayout()
        suffix_layout.addWidget(QLabel('后缀：'))
        self.suffix_combo = QComboBox()
        self.suffix_combo.addItems(['无', '换行 (Enter后)', 'Tab', '自定义'])
        self.suffix_combo.currentIndexChanged.connect(self.on_suffix_changed)
        suffix_layout.addWidget(self.suffix_combo)
        
        self.suffix_custom = QLineEdit()
        self.suffix_custom.setPlaceholderText('自定义后缀')
        self.suffix_custom.setMaximumWidth(100)
        self.suffix_custom.setVisible(False)
        suffix_layout.addWidget(self.suffix_custom)
        suffix_layout.addStretch()
        keyboard_layout.addLayout(suffix_layout)
        
        # 自动回车
        self.auto_enter_checkbox = QCheckBox('输入完成后自动按回车键')
        self.auto_enter_checkbox.setChecked(self.settings.get('auto_enter', False))
        keyboard_layout.addWidget(self.auto_enter_checkbox)
        
        layout.addWidget(keyboard_group)
        
        # 文件输出设置（辅助功能）
        file_group = QGroupBox('文件输出（辅助功能）')
        file_layout = QVBoxLayout(file_group)
        
        self.enable_file_output = QCheckBox('启用文件输出')
        self.enable_file_output.setChecked(self.settings.get('enable_file_output', False))
        self.enable_file_output.stateChanged.connect(self.on_file_output_changed)
        file_layout.addWidget(self.enable_file_output)
        
        file_path_layout = QHBoxLayout()
        file_path_layout.addWidget(QLabel('输出文件：'))
        self.file_path_input = QLineEdit()
        self.file_path_input.setText(self.settings.get('output_file', DEFAULT_OUTPUT_FILE))
        file_path_layout.addWidget(self.file_path_input)
        
        self.browse_btn = QPushButton('浏览')
        self.browse_btn.clicked.connect(self.browse_file)
        self.browse_btn.setEnabled(self.settings.get('enable_file_output', False))
        file_path_layout.addWidget(self.browse_btn)
        file_layout.addLayout(file_path_layout)
        
        self.file_timestamp_checkbox = QCheckBox('添加时间戳')
        self.file_timestamp_checkbox.setChecked(self.settings.get('file_add_timestamp', True))
        self.file_timestamp_checkbox.setEnabled(self.settings.get('enable_file_output', False))
        file_layout.addWidget(self.file_timestamp_checkbox)
        
        layout.addWidget(file_group)
        
        # 其他设置
        other_group = QGroupBox('其他设置')
        other_layout = QVBoxLayout(other_group)
        
        self.minimize_to_tray_checkbox = QCheckBox('关闭窗口时最小化到托盘')
        self.minimize_to_tray_checkbox.setChecked(self.settings.get('minimize_to_tray', True))
        other_layout.addWidget(self.minimize_to_tray_checkbox)
        
        self.start_minimized_checkbox = QCheckBox('启动时最小化到托盘')
        self.start_minimized_checkbox.setChecked(self.settings.get('start_minimized', False))
        other_layout.addWidget(self.start_minimized_checkbox)
        
        layout.addWidget(other_group)
        
        # 按钮
        btn_layout = QHBoxLayout()
        btn_layout.addStretch()
        
        ok_btn = QPushButton('确定')
        ok_btn.clicked.connect(self.accept)
        ok_btn.setMinimumWidth(80)
        btn_layout.addWidget(ok_btn)
        
        cancel_btn = QPushButton('取消')
        cancel_btn.clicked.connect(self.reject)
        cancel_btn.setMinimumWidth(80)
        btn_layout.addWidget(cancel_btn)
        
        layout.addLayout(btn_layout)
        
        # 恢复当前设置
        self.restore_settings()
    
    def restore_settings(self):
        """恢复当前设置到界面"""
        # 输出格式 — 通过 itemData 匹配 key
        format_key = self.settings.get('output_format', 'hex_no_space')
        for i in range(self.format_combo.count()):
            if self.format_combo.itemData(i) == format_key:
                self.format_combo.setCurrentIndex(i)
                break
        
        # 前缀
        prefix_type = self.settings.get('prefix_type', 'none')
        prefix_map = {'none': 0, 'enter_before': 1, 'tab_before': 2, 'custom': 3}
        self.prefix_combo.setCurrentIndex(prefix_map.get(prefix_type, 0))
        
        if prefix_type == 'custom':
            self.prefix_custom.setText(self.settings.get('prefix_custom', ''))
            self.prefix_custom.setVisible(True)
        
        # 后缀
        suffix_type = self.settings.get('suffix_type', 'none')
        suffix_map = {'none': 0, 'enter_after': 1, 'tab_after': 2, 'custom': 3}
        self.suffix_combo.setCurrentIndex(suffix_map.get(suffix_type, 0))
        
        if suffix_type == 'custom':
            self.suffix_custom.setText(self.settings.get('suffix_custom', ''))
            self.suffix_custom.setVisible(True)
    
    def on_prefix_changed(self, index):
        """前缀选择变化"""
        self.prefix_custom.setVisible(index == 3)  # 自定义
    
    def on_suffix_changed(self, index):
        """后缀选择变化"""
        self.suffix_custom.setVisible(index == 3)  # 自定义
    
    def on_file_output_changed(self, state):
        """文件输出开关变化"""
        enabled = state == Qt.Checked
        self.browse_btn.setEnabled(enabled)
        self.file_timestamp_checkbox.setEnabled(enabled)
    
    def browse_file(self):
        """浏览选择文件"""
        file_path, _ = QFileDialog.getSaveFileName(
            self, '选择输出文件',
            self.file_path_input.text(),
            '文本文件 (*.txt);;所有文件 (*.*)'
        )
        if file_path:
            self.file_path_input.setText(file_path)
    
    def get_settings(self) -> dict:
        """获取设置"""
        # 输出格式 — 直接取 itemData
        format_key = self.format_combo.currentData() or 'hex_no_space'
        
        # 前缀类型
        prefix_types = ['none', 'enter_before', 'tab_before', 'custom']
        prefix_type = prefix_types[self.prefix_combo.currentIndex()]
        
        # 后缀类型
        suffix_types = ['none', 'enter_after', 'tab_after', 'custom']
        suffix_type = suffix_types[self.suffix_combo.currentIndex()]
        
        return {
            'output_format': format_key,
            'prefix_type': prefix_type,
            'prefix_custom': self.prefix_custom.text(),
            'suffix_type': suffix_type,
            'suffix_custom': self.suffix_custom.text(),
            'auto_enter': self.auto_enter_checkbox.isChecked(),
            'enable_file_output': self.enable_file_output.isChecked(),
            'output_file': self.file_path_input.text(),
            'file_add_timestamp': self.file_timestamp_checkbox.isChecked(),
            'minimize_to_tray': self.minimize_to_tray_checkbox.isChecked(),
            'start_minimized': self.start_minimized_checkbox.isChecked(),
        }


class MainWindow(QMainWindow):
    """主窗口"""
    
    def __init__(self):
        super().__init__()
        
        # 加载设置
        self.app_settings = QSettings('NFCReader', 'NFCReader')
        
        # 网络组件
        self.tcp_server: Optional[TcpServer] = None
        self.tcp_client: Optional[TcpClient] = None
        self.current_forward_port: Optional[int] = None
        
        # 键盘模拟器
        self.keyboard_sim = KeyboardSimulator()
        
        # 托盘
        self.tray_icon: Optional[QSystemTrayIcon] = None
        self.blink_timer: Optional[QTimer] = None
        self.blink_state = False
        
        # 初始化 UI
        self.init_ui()
        
        # 初始化托盘
        self.init_tray()
        
        # 检查 ADB
        self.check_adb_status()
        
        # 自动最小化
        if self.get_settings().get('start_minimized', False):
            QTimer.singleShot(300, self.minimize_to_tray)
    
    def get_settings(self) -> dict:
        """获取设置"""
        return {
            'output_format': self.app_settings.value('output_format', 'hex_no_space'),
            'prefix_type': self.app_settings.value('prefix_type', 'none'),
            'prefix_custom': self.app_settings.value('prefix_custom', ''),
            'suffix_type': self.app_settings.value('suffix_type', 'none'),
            'suffix_custom': self.app_settings.value('suffix_custom', ''),
            'auto_enter': self.app_settings.value('auto_enter', False, type=bool),
            'enable_file_output': self.app_settings.value('enable_file_output', False, type=bool),
            'output_file': self.app_settings.value('output_file', DEFAULT_OUTPUT_FILE),
            'file_add_timestamp': self.app_settings.value('file_add_timestamp', True, type=bool),
            'minimize_to_tray': self.app_settings.value('minimize_to_tray', True, type=bool),
            'start_minimized': self.app_settings.value('start_minimized', False, type=bool),
        }
    
    def save_settings(self, settings: dict):
        """保存设置"""
        for key, value in settings.items():
            self.app_settings.setValue(key, value)
    
    def init_ui(self):
        """初始化 UI"""
        self.setWindowTitle('NFC 读卡器 - 键盘模拟模式')
        self.setMinimumSize(500, 350)
        self.set_dark_theme()
        
        central = QWidget()
        self.setCentralWidget(central)
        
        layout = QVBoxLayout(central)
        layout.setSpacing(15)
        layout.setContentsMargins(20, 20, 20, 20)
        
        # 欢迎信息
        welcome_label = QLabel('NFC 读卡器已启动，贴卡即输入 UID 到当前光标位置')
        welcome_label.setStyleSheet('font-size: 14px; color: #4CAF50; font-weight: bold;')
        welcome_label.setAlignment(Qt.AlignCenter)
        layout.addWidget(welcome_label)
        
        # 连接设置
        layout.addWidget(self._create_connection_section())
        
        # 状态显示
        self.status_label = QLabel('状态: 未连接')
        self.status_label.setStyleSheet('font-size: 13px; padding: 5px;')
        self.status_label.setAlignment(Qt.AlignCenter)
        layout.addWidget(self.status_label)
        
        # 最后读取
        self.last_read_label = QLabel('上次读取: --')
        self.last_read_label.setStyleSheet('color: #81D4FA; font-family: monospace; font-size: 16px;')
        self.last_read_label.setAlignment(Qt.AlignCenter)
        layout.addWidget(self.last_read_label)
        
        layout.addStretch()
        
        # 底部按钮
        btn_layout = QHBoxLayout()
        btn_layout.addStretch()
        
        self.settings_btn = QPushButton('设置')
        self.settings_btn.clicked.connect(self.open_settings)
        btn_layout.addWidget(self.settings_btn)
        
        self.minimize_btn = QPushButton('最小化到托盘')
        self.minimize_btn.clicked.connect(self.minimize_to_tray)
        btn_layout.addWidget(self.minimize_btn)
        
        layout.addLayout(btn_layout)
        
        # 状态栏
        self.status_bar = QStatusBar()
        self.setStatusBar(self.status_bar)
        self.status_bar.showMessage('就绪')
    
    def set_dark_theme(self):
        """深色主题"""
        # Fusion 样式才会完整遵循 QPalette 配色，Windows 默认样式会忽略
        QApplication.setStyle('Fusion')
        palette = QPalette()
        palette.setColor(QPalette.Window, QColor(53, 53, 53))
        palette.setColor(QPalette.WindowText, Qt.white)
        palette.setColor(QPalette.Base, QColor(25, 25, 25))
        palette.setColor(QPalette.AlternateBase, QColor(53, 53, 53))
        palette.setColor(QPalette.ToolTipBase, QColor(25, 25, 25))
        palette.setColor(QPalette.ToolTipText, Qt.white)
        palette.setColor(QPalette.Text, Qt.white)
        palette.setColor(QPalette.Button, QColor(53, 53, 53))
        palette.setColor(QPalette.ButtonText, Qt.white)
        palette.setColor(QPalette.BrightText, Qt.red)
        palette.setColor(QPalette.Link, QColor(42, 130, 218))
        palette.setColor(QPalette.Highlight, QColor(42, 130, 218))
        palette.setColor(QPalette.HighlightedText, Qt.black)
        QApplication.setPalette(palette)
    
    def _create_connection_section(self) -> QGroupBox:
        """连接设置区域"""
        group = QGroupBox('连接设置')
        layout = QVBoxLayout(group)
        
        # 模式选择
        mode_layout = QHBoxLayout()
        mode_layout.addWidget(QLabel('模式:'))
        
        self.wifi_radio = QRadioButton('WiFi')
        self.wifi_radio.setChecked(True)
        self.wifi_radio.toggled.connect(self.on_mode_changed)
        
        self.adb_radio = QRadioButton('ADB')
        self.adb_radio.toggled.connect(self.on_mode_changed)
        
        mode_layout.addWidget(self.wifi_radio)
        mode_layout.addWidget(self.adb_radio)
        mode_layout.addStretch()
        layout.addLayout(mode_layout)
        
        # WiFi 设置
        self.wifi_widget = QWidget()
        wifi_layout = QHBoxLayout(self.wifi_widget)
        wifi_layout.setContentsMargins(0, 0, 0, 0)
        
        wifi_layout.addWidget(QLabel('端口:'))
        self.wifi_port = QLineEdit()
        self.wifi_port.setText(str(DEFAULT_WIFI_PORT))
        self.wifi_port.setMaximumWidth(80)
        wifi_layout.addWidget(self.wifi_port)
        
        self.wifi_btn = QPushButton('启动服务器')
        self.wifi_btn.clicked.connect(self.toggle_wifi)
        wifi_layout.addWidget(self.wifi_btn)
        wifi_layout.addStretch()
        layout.addWidget(self.wifi_widget)
        
        # ADB 设置
        self.adb_widget = QWidget()
        self.adb_widget.setVisible(False)
        adb_layout = QVBoxLayout(self.adb_widget)
        
        # ADB 状态
        self.adb_status = QLabel('ADB: 检查中...')
        adb_layout.addWidget(self.adb_status)
        
        # 端口设置
        port_layout = QHBoxLayout()
        port_layout.addWidget(QLabel('本地端口:'))
        self.adb_local = QLineEdit()
        self.adb_local.setText(str(DEFAULT_ADB_LOCAL_PORT))
        self.adb_local.setMaximumWidth(80)
        port_layout.addWidget(self.adb_local)
        
        port_layout.addWidget(QLabel('手机端口:'))
        self.adb_remote = QLineEdit()
        self.adb_remote.setText(str(DEFAULT_ADB_LOCAL_PORT))
        self.adb_remote.setMaximumWidth(80)
        port_layout.addWidget(self.adb_remote)
        
        self.adb_btn = QPushButton('创建转发并连接')
        self.adb_btn.clicked.connect(self.toggle_adb)
        port_layout.addWidget(self.adb_btn)
        port_layout.addStretch()
        adb_layout.addLayout(port_layout)
        
        layout.addWidget(self.adb_widget)
        
        # 本机 IP
        self.local_ip = QLabel(f'本机 IP: {self.get_local_ip()}')
        layout.addWidget(self.local_ip)
        
        return group
    
    def init_tray(self):
        """初始化托盘"""
        menu = QMenu()
        
        show_action = QAction('显示主界面', self)
        show_action.triggered.connect(self.show_from_tray)
        menu.addAction(show_action)
        
        menu.addSeparator()
        
        quit_action = QAction('退出', self)
        quit_action.triggered.connect(self.quit_app)
        menu.addAction(quit_action)
        
        self.tray_icon = QSystemTrayIcon(self)
        self.tray_icon.setContextMenu(menu)
        self.tray_icon.setToolTip('NFC 读卡器')
        self.tray_icon.setIcon(QApplication.style().standardIcon(
            QApplication.style().SP_ComputerIcon
        ))
        self.tray_icon.activated.connect(self.on_tray_activated)
        
        self.blink_timer = QTimer()
        self.blink_timer.timeout.connect(self.toggle_blink)
    
    def on_tray_activated(self, reason):
        """托盘激活"""
        if reason == QSystemTrayIcon.Trigger:
            self.show_from_tray()
    
    def show_from_tray(self):
        """从托盘显示"""
        self.show()
        self.setWindowState(Qt.WindowNoState)
        self.blink_timer.stop()
        self.tray_icon.setIcon(QApplication.style().standardIcon(
            QApplication.style().SP_ComputerIcon
        ))
    
    def start_blink(self):
        """开始闪烁"""
        if not self.blink_timer.isActive():
            self.blink_state = False
            self.blink_timer.start(500)
    
    def stop_blink(self):
        """停止闪烁"""
        self.blink_timer.stop()
        self.tray_icon.setIcon(QApplication.style().standardIcon(
            QApplication.style().SP_ComputerIcon
        ))
    
    def toggle_blink(self):
        """切换闪烁"""
        self.blink_state = not self.blink_state
        if self.blink_state:
            self.tray_icon.setIcon(QApplication.style().standardIcon(
                QApplication.style().SP_MessageBoxWarning
            ))
        else:
            self.tray_icon.setIcon(QApplication.style().standardIcon(
                QApplication.style().SP_ComputerIcon
            ))
    
    def on_mode_changed(self):
        """模式切换"""
        is_wifi = self.wifi_radio.isChecked()
        self.wifi_widget.setVisible(is_wifi)
        self.adb_widget.setVisible(not is_wifi)
        self.disconnect_all()
    
    def check_adb_status(self):
        """检查 ADB"""
        if AdbHelper.is_adb_available():
            self.adb_status.setText('ADB: 已就绪')
            self.adb_status.setStyleSheet('color: #4CAF50;')
        else:
            self.adb_status.setText('ADB: 未找到')
            self.adb_status.setStyleSheet('color: #F44336;')
    
    def _parse_port(self, text: str, default: int) -> Optional[int]:
        """解析端口号，返回 None 表示无效"""
        try:
            port = int(text) if text.strip() else default
            if 1 <= port <= 65535:
                return port
        except ValueError:
            pass
        return None

    def toggle_wifi(self):
        """切换 WiFi 服务器"""
        if self.tcp_server and self.tcp_server.is_running:
            self.tcp_server.stop()
            self.tcp_server = None
            self.wifi_btn.setText('启动服务器')
            self.wifi_port.setEnabled(True)
            self.status_label.setText('状态: 未连接')
            self.status_bar.showMessage('服务器已停止')
        else:
            port = self._parse_port(self.wifi_port.text(), DEFAULT_WIFI_PORT)
            if port is None:
                QMessageBox.warning(self, '错误', f'端口无效，范围 1-65535')
                return
            self.tcp_server = TcpServer(port, callback=ServerCallback(self))
            self.tcp_server.start()
            self.wifi_btn.setText('停止服务器')
            self.wifi_port.setEnabled(False)
            self.status_label.setText('状态: 等待连接...')
            self.status_bar.showMessage(f'服务器启动，监听端口 {port}')
    
    def toggle_adb(self):
        """切换 ADB 连接"""
        if self.tcp_client:
            self.tcp_client.disconnect()
            self.tcp_client = None
            if self.current_forward_port:
                AdbHelper.forward_remove(self.current_forward_port)
                self.current_forward_port = None
            self.adb_btn.setText('创建转发并连接')
            self.adb_local.setEnabled(True)
            self.adb_remote.setEnabled(True)
            self.status_label.setText('状态: 未连接')
            self.status_bar.showMessage('ADB 连接已断开')
        else:
            local_port = self._parse_port(self.adb_local.text(), DEFAULT_ADB_LOCAL_PORT)
            remote_port = self._parse_port(self.adb_remote.text(), DEFAULT_ADB_LOCAL_PORT)
            if local_port is None or remote_port is None:
                QMessageBox.warning(self, '错误', '端口无效，范围 1-65535')
                return
            
            if not AdbHelper.forward(local_port, remote_port):
                QMessageBox.warning(self, '错误', '创建 ADB 转发失败')
                return
            
            self.current_forward_port = local_port
            self.tcp_client = TcpClient('127.0.0.1', local_port, callback=ClientCallback(self))
            self.tcp_client.connect()
            self.adb_btn.setText('断开连接')
            self.adb_local.setEnabled(False)
            self.adb_remote.setEnabled(False)
            self.status_label.setText('状态: 连接中...')
            self.status_bar.showMessage(f'正在连接 localhost:{local_port}...')
    
    def disconnect_all(self):
        """断开所有连接"""
        if self.tcp_server:
            self.tcp_server.stop()
            self.tcp_server = None
            self.wifi_btn.setText('启动服务器')
            self.wifi_port.setEnabled(True)
        
        if self.tcp_client:
            self.tcp_client.disconnect()
            self.tcp_client = None
            if self.current_forward_port:
                AdbHelper.forward_remove(self.current_forward_port)
                self.current_forward_port = None
            self.adb_btn.setText('创建转发并连接')
            self.adb_local.setEnabled(True)
            self.adb_remote.setEnabled(True)
        
        self.status_label.setText('状态: 未连接')
    
    def get_local_ip(self) -> str:
        """获取本机 IP"""
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.connect(('8.8.8.8', 80))
            ip = s.getsockname()[0]
            s.close()
            return ip
        except Exception:
            return '127.0.0.1'
    
    def process_received(self, data: str):
        """处理接收到的数据"""
        try:
            print(f'[TcpServer] 收到数据: {data}')
            json_data = json.loads(data)
            uid = json_data.get('uid', '')
            card_type = json_data.get('type', 'unknown')
            
            # 更新显示
            self.last_read_label.setText(f'上次读取: {uid} ({card_type})')
            self.status_bar.showMessage(f'读取到卡片: {uid}')
            
            # 模拟键盘输入
            self.simulate_input(uid)
            
            # 文件输出（辅助）
            self.write_to_file(uid)
            
            # 托盘闪烁
            self.start_blink()
            QTimer.singleShot(2000, self.stop_blink)
            
        except json.JSONDecodeError as e:
            print(f'[TcpServer] JSON 解析失败: {data!r} ({e})')
            self.status_bar.showMessage(f'无效数据: {data[:50]}')
    
    def simulate_input(self, uid: str):
        """模拟键盘输入"""
        settings = self.get_settings()
        
        # 格式化 UID
        formatted_uid = UidFormatter.get_formatted(uid, settings['output_format'])
        
        # 前缀
        prefix_type = settings['prefix_type']
        if prefix_type == 'enter_before':
            self.keyboard_sim.type_enter()
        elif prefix_type == 'tab_before':
            self.keyboard_sim.type_tab()
        elif prefix_type == 'custom':
            prefix = settings.get('prefix_custom', '')
            if prefix:
                self.keyboard_sim.type_text(prefix)
        
        # 主内容
        self.keyboard_sim.type_text(formatted_uid)
        
        # 后缀
        suffix_type = settings['suffix_type']
        if suffix_type == 'enter_after':
            self.keyboard_sim.type_enter()
        elif suffix_type == 'tab_after':
            self.keyboard_sim.type_tab()
        elif suffix_type == 'custom':
            suffix = settings.get('suffix_custom', '')
            if suffix:
                self.keyboard_sim.type_text(suffix)
        
        # 自动回车
        if settings.get('auto_enter', False):
            self.keyboard_sim.type_enter()
    
    def write_to_file(self, uid: str):
        """写入文件（辅助功能），超过 10MB 自动轮转"""
        settings = self.get_settings()
        
        if not settings.get('enable_file_output', False):
            return
        
        output_file = settings.get('output_file', DEFAULT_OUTPUT_FILE)
        if not output_file:
            return
        
        try:
            # 超过 10MB 轮转
            MAX_SIZE = 10 * 1024 * 1024
            try:
                if os.path.exists(output_file) and os.path.getsize(output_file) > MAX_SIZE:
                    backup = output_file + '.bak'
                    if os.path.exists(backup):
                        os.remove(backup)
                    os.rename(output_file, backup)
            except OSError:
                pass
            
            formatted_uid = UidFormatter.get_formatted(uid, settings.get('output_format', 'hex_no_space'))
            
            with open(output_file, 'a', encoding='utf-8') as f:
                if settings.get('file_add_timestamp', True):
                    timestamp = datetime.now().strftime('%Y-%m-%d %H:%M:%S')
                    f.write(f'{timestamp} {formatted_uid}\n')
                else:
                    f.write(formatted_uid + '\n')
        except Exception as e:
            self.status_bar.showMessage(f'文件写入失败: {e}')
    
    def open_settings(self):
        """打开设置"""
        dialog = SettingsDialog(self.get_settings(), self)
        if dialog.exec_() == QDialog.Accepted:
            new_settings = dialog.get_settings()
            self.save_settings(new_settings)
            self.status_bar.showMessage('设置已保存')
    
    def minimize_to_tray(self):
        """最小化到托盘"""
        self.hide()
        self.tray_icon.show()
    
    def on_connected(self):
        """已连接"""
        self.status_label.setText('状态: 已连接')
        self.status_label.setStyleSheet('color: #4CAF50; font-weight: bold;')
        self.status_bar.showMessage('已连接到手机')
    
    def on_disconnected(self):
        """断开连接"""
        self.status_label.setText('状态: 未连接')
        self.status_label.setStyleSheet('')
        self.status_bar.showMessage('连接已断开')
    
    def on_client_connected(self, addr: str):
        """客户端连接"""
        self.status_label.setText(f'状态: 手机已连接 ({addr})')
        self.status_label.setStyleSheet('color: #4CAF50; font-weight: bold;')
        self.status_bar.showMessage(f'手机已连接 ({addr})')
    
    def on_client_disconnected(self):
        """客户端断开"""
        self.status_label.setText('状态: 等待连接...')
        self.status_label.setStyleSheet('')
        self.status_bar.showMessage('手机已断开')
    
    def closeEvent(self, event):
        """关闭事件"""
        if self.get_settings().get('minimize_to_tray', True):
            event.ignore()
            self.minimize_to_tray()
        else:
            self.quit_app()
    
    def quit_app(self):
        """退出应用"""
        if self.blink_timer:
            self.blink_timer.stop()
        self.disconnect_all()
        if self.tray_icon:
            self.tray_icon.hide()
        QApplication.quit()


class ServerCallback:
    """服务器回调"""
    def __init__(self, window):
        self.window = window
    
    def on_status_changed(self, msg):
        QTimer.singleShot(0, lambda: self.window.status_bar.showMessage(msg))
    
    def on_client_connected(self, addr):
        QTimer.singleShot(0, lambda: self.window.on_client_connected(addr))
    
    def on_client_disconnected(self):
        QTimer.singleShot(0, lambda: self.window.on_client_disconnected())
    
    def on_data_received(self, data):
        QTimer.singleShot(0, lambda: self.window.process_received(data))
    
    def on_error(self, msg):
        QTimer.singleShot(0, lambda: self.window.status_bar.showMessage(msg))


class ClientCallback:
    """客户端回调"""
    def __init__(self, window):
        self.window = window
    
    def on_connected(self):
        QTimer.singleShot(0, lambda: self.window.on_connected())
    
    def on_disconnected(self):
        QTimer.singleShot(0, lambda: self.window.on_disconnected())
    
    def on_data_received(self, data):
        QTimer.singleShot(0, lambda: self.window.process_received(data))
    
    def on_error(self, msg):
        QTimer.singleShot(0, lambda: self.window.status_bar.showMessage(msg))
    
    def on_status_changed(self, msg):
        QTimer.singleShot(0, lambda: self.window.status_bar.showMessage(msg))


def main():
    """主函数"""
    try:
        app = QApplication(sys.argv)
        app.setApplicationName('NFC 读卡器')
        
        window = MainWindow()
        window.show()
        
        sys.exit(app.exec_())
    except Exception as e:
        import traceback
        traceback.print_exc()
        input(f"\n程序出错，按回车退出: {e}")


if __name__ == '__main__':
    main()
