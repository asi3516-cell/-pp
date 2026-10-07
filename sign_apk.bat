@echo off
REM ===================================================================
REM  hh - APK'yi sabit release anahtariyla yeniden imzalar.
REM  Zaten Android Studio/Gradle imzaladiysa gerek yok; ham bir
REM  APK'yi (dist\TV-Player.apk) elle imzalamak icin kullanilir.
REM
REM  Kullanim: sign_apk.bat
REM  Istege bagli: set TV_STORE_PASS / TV_KEY_PASS onceden tanimlanabilir.
REM ===================================================================
setlocal
cd /d "%~dp0"

set "KS=android\app\hh-release.jks"
set "ALIAS=hhkey"
set "APK_IN=dist\TV-Player.apk"
set "APK_OUT=dist\TV-Player-signed.apk"

if not defined TV_STORE_PASS set /p TV_STORE_PASS=Keystore sifre: 
if not defined TV_KEY_PASS set "TV_KEY_PASS=%TV_STORE_PASS%"

set "APKSIGNER="
for /f "delims=" %%i in ('where apksigner 2^>nul') do if not defined APKSIGNER set "APKSIGNER=%%i"
if not defined APKSIGNER (
  if not defined ANDROID_SDK_ROOT set "ANDROID_SDK_ROOT=%LOCALAPPDATA%\Android\Sdk"
  set "APKSIGNER=%ANDROID_SDK_ROOT%\build-tools\34.0.0\apksigner.bat"
)
if not exist "%APKSIGNER%" (
  echo [HATA] apksigner bulunamadi. ANDROID_SDK_ROOT tanimlayin.
  pause
  exit /b 1
)
if not exist "%APK_IN%" (
  echo [HATA] %APK_IN% yok. Once APK'yi uretin.
  pause
  exit /b 1
)

"%APKSIGNER%" sign --ks "%KS%" --ks-key-alias %ALIAS% ^
  --ks-pass env:TV_STORE_PASS --key-pass env:TV_KEY_PASS ^
  --out "%APK_OUT%" "%APK_IN%"
if errorlevel 1 (
  echo [HATA] Imzalama basarisiz.
  pause
  exit /b 1
)

"%APKSIGNER%" verify --print-certs "%APK_OUT%"
echo.
echo Hazir: %APK_OUT%
pause
