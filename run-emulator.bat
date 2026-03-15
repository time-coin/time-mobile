@echo off
setlocal

set JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.10.7-hotspot
set PATH=%JAVA_HOME%\bin;%PATH%
set ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe
set EMULATOR=%LOCALAPPDATA%\Android\Sdk\emulator\emulator.exe
set AVD=Pixel_5_Dev
set APK=android\app\build\outputs\apk\debug\app-debug.apk

echo === TIME Coin Mobile - Emulator Launcher ===
echo.

:: Parse flags
set FORCE_BUILD=0
for %%A in (%*) do (
    if /i "%%A"=="--build" set FORCE_BUILD=1
    if /i "%%A"=="-b" set FORCE_BUILD=1
)

:: Clean lock files
del /q "%USERPROFILE%\.android\avd\%AVD%.avd\*.lock" 2>nul

:: Build step — skip if APK exists and --build not passed
if %FORCE_BUILD%==1 goto do_build
if exist "%APK%" (
    echo [1/3] APK already built — skipping. Pass --build to rebuild.
    echo.
    goto start_emulator
)

:do_build
echo [1/3] Building debug APK...
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

:start_emulator
:: Check if emulator is already running
"%ADB%" devices 2>nul | findstr "emulator" >nul
if %errorlevel%==0 (
    echo [2/3] Emulator already running — skipping launch.
    echo.
    goto install_app
)

echo [2/3] Starting emulator (%AVD%)...
start "" "%EMULATOR%" -avd %AVD%

:: Wait for emulator to boot
echo [3/3] Waiting for emulator to boot...
:wait_boot
ping -n 6 127.0.0.1 >nul
"%ADB%" shell getprop sys.boot_completed 2>nul | findstr "1" >nul
if %errorlevel% neq 0 goto wait_boot
echo Emulator booted.
echo.

:install_app
echo Installing and launching TIME Coin...
"%ADB%" install -r "%APK%"
"%ADB%" shell am start -n com.timecoin.wallet/.ui.MainActivity

echo.
echo === TIME Coin running on %AVD% ===
pause
