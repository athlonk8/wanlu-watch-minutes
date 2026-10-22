@echo off
chcp 65001 >nul
rem ===== 从手表拉取「腕录」录音到 inbox =====
rem 如果手表用无线 ADB:把下面 set WATCH_ADDR= 去掉注释并填手表IP:端口,如 192.168.1.8:5555
rem (注意:部分 OPPO 手表不支持无线 ADB,需按 README 用有线/蓝牙方式)
rem set WATCH_ADDR=192.168.1.8:5555

set ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe
if not exist "%ADB%" set ADB=adb

if defined WATCH_ADDR (
  echo 连接手表 %WATCH_ADDR% …
  "%ADB%" connect %WATCH_ADDR%
)

set SRC=/sdcard/Android/data/com.zcode.watchrec/files
echo 拉取 %SRC% → inbox …
"%ADB%" pull %SRC%/. "%~dp0..\inbox\"
echo.
echo 完成。录音在 watch_minutes\inbox\,双击「生成纪要.bat」开始转写。
pause
