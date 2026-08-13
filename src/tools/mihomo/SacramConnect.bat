@echo off
setlocal EnableExtensions
rem Sacram one-click launcher: starts mihomo (systemwide TCP+UDP via phone)
rem Double click me. If Windows asks, click Run / Yes for admin.
rem
rem Detects the phone (default-route gateway, with a WiFi Direct fallback
rem scan), finds the matching adapter name + subnet, and generates
rem config.yaml with:
rem   - interface-name         pins outbound to the phone's adapter,
rem                            prevents TUN from being picked as its own
rem                            outbound interface (self-dial deadlock)
rem   - route-exclude-address  keeps the phone's subnet out of the tunnel
rem
rem All output (including mihomo's startup crash messages) is mirrored to
rem the console AND written to sacramconnect.log next to this script.

title Sacram - mihomo

rem === log file (sits next to this .bat) ===
set "LOG=%~dp0sacramconnect.log"
rem === auto-clear any previous log content ===
if exist "%LOG%" (type nul > "%LOG%")
echo [%date% %time%] SacramConnect starting: %~f0 > "%LOG%"
echo [%date% %time%] Log file: %LOG%

rem === log verbosity: debug for diagnosis, info for daily use ===
set MIHOMO_LOG_LEVEL=debug

rem === self-elevate to admin (TUN needs admin) ===
net session >nul 2>&1
if %errorlevel% neq 0 (
    echo Requesting admin rights...
    echo [%date% %time%] Not running as admin - requesting elevation >> "%LOG%"
    powershell -Command "Start-Process -FilePath '%~f0' -Verb runAs"
    if errorlevel 1 (
        echo [%date% %time%] Elevation failed or was cancelled by the user >> "%LOG%"
        echo Elevation failed or was cancelled. Cannot run TUN without admin.
        pause
    )
    exit /b
)

cd /d "%~dp0"
echo [%date% %time%] Running elevated. Working dir: %CD% >> "%LOG%"

rem === detect phone IP / adapter / subnet and write config.yaml ===
echo [%date% %time%] Running network detection... >> "%LOG%"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0detect.ps1" > "%TEMP%\sacram_detect.txt" 2>> "%LOG%"
if errorlevel 1 (
    echo WARNING: detection script failed - using defaults.
    echo [%date% %time%] WARNING: detection script exited with an error >> "%LOG%"
)

rem === read the five result lines ===
for /f "usebackq delims=" %%L in ("%TEMP%\sacram_detect.txt") do (
    if not defined L1 (set "L1=%%L") else if not defined L2 (set "L2=%%L") else if not defined L3 (set "L3=%%L") else if not defined L4 (set "L4=%%L") else if not defined L5 (set "L5=%%L")
)
del "%TEMP%\sacram_detect.txt" >nul 2>&1

set "PHONE_IP=%L1%"
if "%PHONE_IP%"=="" set PHONE_IP=192.168.49.1
set "IFACE_NAME=%L2%"
if "%IFACE_NAME%"=="(unknown)" set "IFACE_NAME="
set "EXCLUDES=%L3%"
set "PROBE_OK=%L4%"
set "LOCAL_SUBNET=%L5%"
if "%LOCAL_SUBNET%"=="(none)" set "LOCAL_SUBNET="

echo ==========================================
echo   Sacram UDP Bridge - mihomo (TUN mode)
echo   Phone proxy at %PHONE_IP%:1080
if not "%IFACE_NAME%"=="" echo   Outbound adapter: %IFACE_NAME%
if not "%LOCAL_SUBNET%"=="" echo   Phone subnet:    %LOCAL_SUBNET%
if not "%EXCLUDES%"=="" echo   Route excludes:  %EXCLUDES%
echo ==========================================
echo.

(
echo ==========================================
echo   Sacram UDP Bridge - mihomo (TUN mode)
echo   Phone proxy at %PHONE_IP%:1080
if not "%IFACE_NAME%"=="" echo   Outbound adapter: %IFACE_NAME%
if not "%LOCAL_SUBNET%"=="" echo   Phone subnet:    %LOCAL_SUBNET%
if not "%EXCLUDES%"=="" echo   Route excludes:  %EXCLUDES%
echo ==========================================
) >> "%LOG%"

if "%PROBE_OK%"=="no" (
    echo WARNING: proxy not reachable on %PHONE_IP%:1080.
    echo Is the Sacram app RUNNING on the phone? Are you connected to its network?
    echo [%date% %time%] WARNING: proxy not reachable on %PHONE_IP%:1080 >> "%LOG%"
    echo.
)

rem === sanity check: is the mihomo binary present? ===
if not exist "mihomo-windows-amd64-v3.exe" (
    echo ERROR: mihomo-windows-amd64-v3.exe not found in %CD%
    echo [%date% %time%] ERROR: mihomo-windows-amd64-v3.exe not found in %CD% >> "%LOG%"
    echo.
    echo Close this window to stop.
    pause
    exit /b
)

echo Close this window to stop.
echo.
echo [%date% %time%] Launching mihomo... >> "%LOG%"

rem === run mihomo, mirror output to console AND log ===
mihomo-windows-amd64-v3.exe -f config.yaml 2>&1 | powershell -NoProfile -Command "$input | ForEach-Object { $_ | Out-Host; Add-Content -Path '%LOG%' -Value $_ }"

echo.
echo mihomo exited.
echo [%date% %time%] mihomo exited with code %errorlevel% >> "%LOG%"
echo.
echo Full log saved to: %LOG%
echo.
pause
