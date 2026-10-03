@echo off
REM ===================================================================
REM  TV Player - tek tikla Windows .exe olusturucu
REM  Bilgisayarinda Python kurulu ise bu dosyaya cift tikla; gerekli
REM  paketleri kurar ve dist\TV-Player.exe dosyasini uretir.
REM ===================================================================
setlocal enabledelayedexpansion
cd /d "%~dp0"
chcp 65001 >nul 2>&1

echo ============================================================
echo   TV Player - Windows .exe olusturma
echo ============================================================
echo.

REM --- 1) Python var mi? ---------------------------------------------
set "PY="
where py >nul 2>&1 && set "PY=py -3"
if not defined PY (
  where python >nul 2>&1 && set "PY=python"
)
if not defined PY (
  echo [HATA] Python bulunamadi.
  echo Lutfen https://www.python.org/downloads/ adresinden Python 3.10+
  echo kurun ve kurulumda "Add Python to PATH" secenegini isaretleyin.
  echo.
  pause
  exit /b 1
)
for /f "delims=" %%v in ('%PY% -c "import sys;print(sys.version.split()[0])"') do set "PYVER=%%v"
echo [OK] Python bulundu: !PYVER!  (!PY!)

REM --- 2) Gerekli paketleri kur --------------------------------------
echo.
echo [1/3] Gerekli paketler kuruluyor (pyinstaller)...
%PY% -m pip install --upgrade pip >nul 2>&1
%PY% -m pip install -r "build\requirements-build.txt"
if errorlevel 1 (
  echo [HATA] Paket kurulumu basarisiz oldu. Internet baglantinizi kontrol edin.
  pause
  exit /b 1
)

REM --- 3) Derle ------------------------------------------------------
echo.
echo [2/3] Uygulama derleniyor (birkac dakika surebilir)...
%PY% -m PyInstaller "build\tv_player.spec" --noconfirm --clean ^
  --distpath "dist" --workpath "build\work"
if errorlevel 1 (
  echo [HATA] Derleme basarisiz oldu. Yukaridaki mesajlara bakin.
  pause
  exit /b 1
)

REM --- 4) Sonuc ------------------------------------------------------
echo.
if exist "dist\TV-Player.exe" (
  echo [3/3] TAMAM! Uygulama hazir:
  echo.
  echo     %cd%\dist\TV-Player.exe
  echo.
  echo Bu dosyayi cift tiklayarak calistirabilirsiniz. Kurulum gerekmez.
  echo Isterseniz dosyayi baska bir bilgisayara kopyalayabilirsiniz.
  echo.
  choice /c EH /n /m "Simdi calistirilsin mi? [E=evet / H=hayir]: "
  if !errorlevel! equ 1 start "" "dist\TV-Player.exe"
) else (
  echo [HATA] dist\TV-Player.exe olusturulamadi.
  pause
  exit /b 1
)
endlocal
