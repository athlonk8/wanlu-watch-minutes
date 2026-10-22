@echo off
chcp 65001 >nul
cd /d "%~dp0"
start "腕录控制面板" cmd /c "python server.py"
timeout /t 2 >nul
start "" http://127.0.0.1:8733
