@echo off
REM Sign the release APK with the project key. Run from the android folder:
REM   set TV_STORE_PASS=... & set TV_KEY_PASS=... & sign_apk.bat
REM The keystore (hh-release.jks) and its passwords are not committed.

setlocal
if "%TV_STORE_PASS%"=="" (
  echo TV_STORE_PASS is not set. Export it first, e.g.:
  echo   set TV_STORE_PASS=yourpassword
  exit /b 1
)
set KS=app\hh-release.jks
if not exist "%KS%" (
  echo Keystore not found: %KS%
  exit /b 1
)

set OUT=..\dist\TV-Player-signed.apk
apksigner sign --ks "%KS%" --ks-key-alias "%TV_KEY_ALIAS%" --ks-pass env:TV_STORE_PASS --key-pass env:TV_KEY_PASS --out "%OUT%" app\build\outputs\apk\release\app-release.apk
if errorlevel 1 exit /b 1

apksigner verify --print-certs "%OUT%"
endlocal
