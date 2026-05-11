@echo off
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

REM 创建打包输出目录
if not exist "dist" mkdir dist

echo [1/3] 正在打包...
echo.

REM 使用 --collect-all 确保所有依赖都被打包
pyinstaller --onefile --noconsole^
    --name "NFC读卡器"^
    --icon=icon.jpg^
    --collect-all keyboard^
    --collect-all PyQt5^
    --hidden-import PyQt5.QtCore^
    --hidden-import PyQt5.QtGui^
    --hidden-import PyQt5.QtWidgets^
    --clean^
    nfc_reader.py

echo.
echo [2/3] 打包完成，输出文件: dist\NFC读卡器.exe
echo.

REM 检查文件大小
for %%A in ("dist\NFC读卡器.exe") do set size=%%~zA
set /a sizeMB=%size%/1048576
echo 可执行文件大小: %sizeMB% MB
echo.

REM 运行测试
echo [3/3] 是否运行测试? (Y/N)
set /p runtest=
if /i "%runtest%"=="Y" (
    echo 启动程序...
    start "" "dist\NFC读卡器.exe"
)

echo.
echo ========================================
echo 打包完成！
echo ========================================
pause
