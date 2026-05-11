@echo off
chcp 65001 > nul
echo ========================================
echo NFC 读卡器 Windows 版 — 优化打包脚本
echo ========================================
echo.

REM 检查 PyInstaller
python -c "import PyInstaller" > nul 2>&1
if errorlevel 1 (
    echo [错误] PyInstaller 未安装，正在安装...
    pip install pyinstaller
    echo.
)

REM 检查 UPX
where upx > nul 2>&1
if %errorlevel% == 0 (
    echo [UPX] 检测到 UPX，将启用压缩
    set UPX_FLAG=--upx-dir=.
) else (
    echo [UPX] 未检测到 UPX，建议下载放置到当前目录以进一步减小体积
    echo         https://github.com/upx/upx/releases
    set UPX_FLAG=
)

REM 清理旧构建
if exist "build" rmdir /s /q "build"
if exist "dist" rmdir /s /q "dist"

echo.
echo [1/3] 正在优化打包...
echo.

REM 使用 onedir 模式（比 onefile 启动快、体积小）
REM 排除大量未使用的 PyQt5 模块
pyinstaller --onedir --noconsole ^
    --name "NFC读卡器" ^
    --icon=icon.ico ^
    --hidden-import keyboard ^
    --hidden-import PyQt5.sip ^
    --exclude-module PyQt5.QtWebEngine ^
    --exclude-module PyQt5.QtWebEngineCore ^
    --exclude-module PyQt5.QtWebEngineWidgets ^
    --exclude-module PyQt5.QtWebChannel ^
    --exclude-module PyQt5.QtNetwork ^
    --exclude-module PyQt5.QtMultimedia ^
    --exclude-module PyQt5.QtMultimediaWidgets ^
    --exclude-module PyQt5.QtSql ^
    --exclude-module PyQt5.QtTest ^
    --exclude-module PyQt5.QtXml ^
    --exclude-module PyQt5.QtXmlPatterns ^
    --exclude-module PyQt5.QtSvg ^
    --exclude-module PyQt5.QtOpenGL ^
    --exclude-module PyQt5.QtPrintSupport ^
    --exclude-module PyQt5.QtBluetooth ^
    --exclude-module PyQt5.QtDesigner ^
    --exclude-module PyQt5.QtHelp ^
    --exclude-module PyQt5.QtLocation ^
    --exclude-module PyQt5.QtNfc ^
    --exclude-module PyQt5.QtPositioning ^
    --exclude-module PyQt5.QtQuick ^
    --exclude-module PyQt5.QtQuickWidgets ^
    --exclude-module PyQt5.QtRemoteObjects ^
    --exclude-module PyQt5.QtSensors ^
    --exclude-module PyQt5.QtSerialPort ^
    --exclude-module PyQt5.QtTextToSpeech ^
    --exclude-module PyQt5.QtWinExtras ^
    --exclude-module PyQt5.QtX11Extras ^
    --exclude-module PyQt5.Qt3DCore ^
    --exclude-module PyQt5.Qt3DExtras ^
    --exclude-module PyQt5.Qt3DInput ^
    --exclude-module PyQt5.Qt3DLogic ^
    --exclude-module PyQt5.Qt3DRender ^
    --exclude-module PyQt5.QtChart ^
    --exclude-module PyQt5.QtDataVisualization ^
    --exclude-module matplotlib ^
    --exclude-module numpy ^
    --exclude-module pandas ^
    --exclude-module PIL ^
    --exclude-module tkinter ^
    --exclude-module scipy ^
    --exclude-module sklearn ^
    --exclude-module cv2 ^
    --exclude-module IPython ^
    --exclude-module jupyter ^
    --exclude-module pytest ^
    --exclude-module unittest ^
    --exclude-module pydoc ^
    --exclude-module email ^
    --exclude-module html ^
    --exclude-module http ^
    --exclude-module xmlrpc ^
    --clean ^
    %UPX_FLAG% ^
    nfc_reader.py

echo.
echo [2/3] 打包完成！
echo.

REM 计算大小
set "DIST_DIR=dist\NFC读卡器"
if exist "%DIST_DIR%" (
    for /f "tokens=3" %%a in ('dir /s "%DIST_DIR%" ^| findstr "个文件"') do (
        set /a totalBytes=%%a
        set /a totalMB=%%a/1048576
        echo 输出目录总大小: %totalMB% MB (%%a 字节)
    )
    
    for %%A in ("%DIST_DIR%\NFC读卡器.exe") do (
        set /a exeMB=%%~zA/1048576
        echo 主程序大小: %exeMB% MB
    )
) else (
    echo 未找到输出目录
)

echo.
echo [3/3] 打包完成！
echo 输出目录: dist\NFC读卡器\
echo.
echo 提示: 分发时请复制整个 dist\NFC读卡器\ 文件夹
echo ========================================
pause
