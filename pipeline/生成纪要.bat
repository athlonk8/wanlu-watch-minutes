@echo off
chcp 65001 >nul
cd /d "%~dp0"
if not exist config.ini (
  if exist config.example.ini (
    copy config.example.ini config.ini >nul
    echo 已生成 config.ini,首次使用请先打开填入 dashscope_api_key!
    notepad config.ini
  )
)
python minutes.py %*
pause
