@echo off
setlocal

set JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.10.7-hotspot
set PATH=%JAVA_HOME%\bin;%PATH%
set ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe
set EMULATOR=%LOCALAPPDATA%\Android\Sdk\emulator\emulator.exe
set AVD=Pixel_9_Pro_XL
set APK=android\app\build\outputs\apk\debug\app-debug.apk

echo === TIME Coin Mobile - Build, Emulator, Install ===
echo.

:: Clean lock files
del /q "%USERPROFILE%\.android\avd\%AVD%.avd\*.lock" 2>nul

:: Build debug APK
echo [1/4] Building debug APK...
cd android
call gradlew.bat assembleDebug
if %errorlevel% neq 0 (
    echo BUILD FAILED
    pause
    exit /b 1
)
cd ..
echo Build successful.
echo.

:: Start emulator
echo [2/4] Starting emulator (%AVD%)...
start "" "%EMULATOR%" -avd %AVD%

:: Wait for emulator to boot
echo [3/4] Waiting for emulator to boot...
:wait_boot
timeout /t 5 /nobreak >nul
"%ADB%" shell getprop sys.boot_completed 2>nul | findstr "1" >nul
if %errorlevel% neq 0 goto wait_boot
echo Emulator booted.
echo.

:: Install and launch
echo [4/4] Installing and launching app...
"%ADB%" install -r "%APK%"
"%ADB%" shell am start -n com.timecoin.wallet/.ui.MainActivity

echo.
echo === App running on emulator ===
pause
