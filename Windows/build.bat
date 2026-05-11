@echo off
setlocal enabledelayedexpansion
chcp 65001 > nul
echo ========================================
echo NFC 读卡器 Windows 版打包脚本
echo ========================================
echo.

REM 检查 PyInstaller 是否安装
python -c "import PyInstaller" > nul 2>&1
if errorlevel 1 (
    echo [错误] PyInstaller 未安装，正在安装...
    pip install pyinstaller
    echo.
)

REM 清理旧构建
if exist "build" rmdir /s /q "build"
if exist "dist" rmdir /s /q "dist"

echo [1/3] 正在打包（单文件模式）...
echo.

pyinstaller --onefile --noconsole ^
    --name "NFCReader" ^
    --icon=icon.ico ^
    --add-data "icon.ico;." ^
    --hidden-import keyboard ^
    --hidden-import PyQt5.sip ^
    --hidden-import PyQt5.QtCore ^
    --hidden-import PyQt5.QtGui ^
    --hidden-import PyQt5.QtWidgets ^
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
    --exclude-module scipy ^
    --exclude-module sklearn ^
    --exclude-module cv2 ^
    --exclude-module IPython ^
    --exclude-module jupyter ^
    --exclude-module pytest ^
    --exclude-module unittest ^
    --exclude-module pydoc ^
    --exclude-module tkinter ^
    --exclude-module email ^
    --exclude-module html ^
    --exclude-module http ^
    --exclude-module xmlrpc ^
    --clean ^
    nfc_reader.py

echo.
echo [2/3] 打包完成
echo.

if exist "dist\NFCReader.exe" (
    for %%A in ("dist\NFCReader.exe") do (
        set /a sizeMB=%%~zA/1048576
        echo 输出文件: dist\NFCReader.exe
        echo 文件大小: %%~zA 字节 (约 !sizeMB! MB)
    )
) else (
    echo [错误] 打包失败，未找到输出文件
    pause
    exit /b 1
)

echo.
echo [3/3] 是否运行测试? (Y/N)
set /p runtest=
if /i "%runtest%"=="Y" (
    echo 启动程序...
    start "" "dist\NFCReader.exe"
)

echo.
echo ========================================
echo 打包完成！
echo ========================================
pause
