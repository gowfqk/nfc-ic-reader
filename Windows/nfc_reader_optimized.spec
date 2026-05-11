# -*- mode: python ; coding: utf-8 -*-
"""
NFC 读卡器 — 优化打包配置
使用: pyinstaller nfc_reader_optimized.spec

优化点：
- 使用 onedir 替代 onefile（启动更快，体积更小）
- 排除大量未使用的 PyQt5 子模块
- 排除常用大型库（numpy/pandas/matplotlib 等）
"""

block_cipher = None

a = Analysis(
    ['nfc_reader.py'],
    pathex=[],
    binaries=[],
    datas=[],
    hiddenimports=[
        'keyboard',
        'PyQt5.sip',
    ],
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=[
        # PyQt5 未使用模块
        'PyQt5.QtWebEngine', 'PyQt5.QtWebEngineCore', 'PyQt5.QtWebEngineWidgets',
        'PyQt5.QtWebChannel', 'PyQt5.QtNetwork', 'PyQt5.QtMultimedia',
        'PyQt5.QtMultimediaWidgets', 'PyQt5.QtSql', 'PyQt5.QtTest',
        'PyQt5.QtXml', 'PyQt5.QtXmlPatterns', 'PyQt5.QtSvg',
        'PyQt5.QtOpenGL', 'PyQt5.QtPrintSupport', 'PyQt5.QtBluetooth',
        'PyQt5.QtDesigner', 'PyQt5.QtHelp', 'PyQt5.QtLocation',
        'PyQt5.QtNfc', 'PyQt5.QtPositioning', 'PyQt5.QtQuick',
        'PyQt5.QtQuickWidgets', 'PyQt5.QtRemoteObjects', 'PyQt5.QtSensors',
        'PyQt5.QtSerialPort', 'PyQt5.QtTextToSpeech', 'PyQt5.QtWinExtras',
        'PyQt5.QtX11Extras', 'PyQt5.Qt3DCore', 'PyQt5.Qt3DExtras',
        'PyQt5.Qt3DInput', 'PyQt5.Qt3DLogic', 'PyQt5.Qt3DRender',
        'PyQt5.QtChart', 'PyQt5.QtDataVisualization',
        # 常用大型库
        'matplotlib', 'numpy', 'pandas', 'PIL', 'scipy', 'sklearn',
        'cv2', 'IPython', 'jupyter', 'pytest', 'unittest',
        # 标准库中未使用的模块
        'tkinter', 'pydoc', 'email', 'html', 'http', 'xmlrpc',
    ],
    win_no_prefer_redirects=False,
    win_private_assemblies=False,
    cipher=block_cipher,
    noarchive=False,
)

pyz = PYZ(a.pure, a.zipped_data, cipher=block_cipher)

exe = EXE(
    pyz,
    a.scripts,
    [],
    exclude_binaries=True,
    name='NFC读卡器',
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=True,
    upx_exclude=[],
    runtime_tmpdir=None,
    console=False,
    disable_windowed_traceback=False,
    argv_emulation=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
    icon='icon.ico',
)

coll = COLLECT(
    exe,
    a.binaries,
    a.zipfiles,
    a.datas,
    strip=False,
    upx=True,
    upx_exclude=[],
    name='NFC读卡器',
)
