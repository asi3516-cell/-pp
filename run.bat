@echo off
REM Windows: double-click to start the player.
cd /d "%~dp0"
if "%PORT%"=="" set PORT=8000
python server.py --port %PORT% --open
pause
