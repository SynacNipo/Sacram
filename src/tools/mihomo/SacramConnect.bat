@echo off
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

title Sacram - mihomo

rem === log verbosity: debug for diagnosis, info for daily use ===
set MIHOMO_LOG_LEVEL=debug

rem === self-elevate to admin (TUN needs admin) ===
net session >nul 2>&1
if %errorlevel% neq 0 (
    echo Requesting admin rights...
    powershell -Command "Start-Process -FilePath '%~f0' -Verb runAs"
    exit /b
)

cd /d "%~dp0"

rem === detect phone IP / adapter / subnet and write config.yaml ===
powershell -NoProfile -ExecutionPolicy Bypass -Command "& {
  function Get-NetworkCidr([string]$addr, [int]$prefix) {
    $octets = $addr -split '\.'
    $ipInt = [int64]0
    foreach ($o in $octets) { $ipInt = ($ipInt -shl 8) -bor ([int64]$o) }
    $mask = ([int64]0xffffffff) -shl (32 - $prefix)
    $net = ($ipInt -band $mask) -band [int64]0xffffffff
    $out = @()
    for ($i = 3; $i -ge 0; $i--) { $out += ($net -shr ($i * 8)) -band 255 }
    return (($out -join '.') + '/' + $prefix)
  }
  $phone = '192.168.49.1'
  $ifaceName = ''
  $localSubnet = ''
  $excludes = @('192.168.49.0/24','192.168.43.0/24')
  $route = Get-NetRoute -DestinationPrefix '0.0.0.0/0' | Sort-Object RouteMetric | Select-Object -First 1
  if ($route -and $route.NextHop -and $route.NextHop -ne '0.0.0.0') {
    $phone = $route.NextHop
    $ifaceName = (Get-NetAdapter -InterfaceIndex $route.InterfaceIndex -ErrorAction SilentlyContinue).Name
    $ip = Get-NetIPAddress -InterfaceIndex $route.InterfaceIndex -AddressFamily IPv4 -ErrorAction SilentlyContinue | Sort-Object PrefixLength -Descending | Select-Object -First 1
    if ($ip) {
      $localSubnet = Get-NetworkCidr $ip.IPAddress $ip.PrefixLength
      $excludes += $localSubnet
    }
  }
  if (-not $ifaceName) {
    $cand = Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue | Where-Object { $_.IPAddress -like '192.168.49.*' -or $_.IPAddress -like '192.168.43.*' } | Select-Object -First 1
    if ($cand) {
      $ifaceName = (Get-NetAdapter -InterfaceIndex $cand.InterfaceIndex -ErrorAction SilentlyContinue).Name
      $localSubnet = Get-NetworkCidr $cand.IPAddress $cand.PrefixLength
      $excludes += $localSubnet
      $phone = (($cand.IPAddress -split '\.')[0..2] -join '.') + '.1'
    }
  }
  $excludes = @($excludes | Select-Object -Unique)
  $ok = 'no'
  try {
    $c = New-Object System.Net.Sockets.TcpClient
    $r = $c.BeginConnect($phone, 1080, $null, $null)
    if ($r.AsyncWaitHandle.WaitOne(1500)) { $c.EndConnect($r); $ok = 'yes' }
    $c.Close()
  } catch { $ok = 'no' }
  $logLevel = $env:MIHOMO_LOG_LEVEL
  if (-not $logLevel) { $logLevel = 'info' }
  $lines = New-Object System.Collections.Generic.List[string]
  $lines.Add('# Mihomo (Clash core) config - Sacram phone proxy (auto-generated)')
  $lines.Add('mixed-port: 7890')
  $lines.Add('allow-lan: true')
  $lines.Add('bind-address: ''*''')
  $lines.Add('log-level: ' + $logLevel)
  if ($ifaceName) { $lines.Add('interface-name: ''' + $ifaceName + '''') }
  $lines.Add('')
  $lines.Add('proxies:')
  $lines.Add('  - name: phone')
  $lines.Add('    type: socks5')
  $lines.Add('    server: ' + $phone)
  $lines.Add('    port: 1080')
  $lines.Add('    udp: true')
  $lines.Add('')
  $lines.Add('proxy-groups:')
  $lines.Add('  - name: auto')
  $lines.Add('    type: select')
  $lines.Add('    proxies:')
  $lines.Add('      - phone')
  $lines.Add('')
  $lines.Add('rules:')
  $lines.Add('  - MATCH,auto')
  $lines.Add('')
  $lines.Add('# Systemwide capture: TCP + UDP + ICMP')
  $lines.Add('tun:')
  $lines.Add('  enable: true')
  $lines.Add('  stack: mixed')
  $lines.Add('  auto-route: true')
  if ($ifaceName) {
    $lines.Add('  auto-detect-interface: false')
  } else {
    $lines.Add('  auto-detect-interface: true')
  }
  $lines.Add('  route-exclude-address:')
  foreach ($e in $excludes) { $lines.Add('    - ' + $e) }
  $lines.Add('  dns-hijack:')
  $lines.Add('    - any:53')
  [IO.File]::WriteAllText('config.yaml', (($lines -join [Environment]::NewLine) + [Environment]::NewLine), (New-Object System.Text.UTF8Encoding($false)))
  Write-Output $phone
  if (-not $ifaceName) { Write-Output '(unknown)' } else { Write-Output $ifaceName }
  Write-Output ($excludes -join ' ')
  Write-Output $ok
  if (-not $localSubnet) { Write-Output '(none)' } else { Write-Output $localSubnet }
}" > "%TEMP%\sacram_detect.txt"
if errorlevel 1 (
    echo WARNING: detection script failed - using defaults.
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

if "%PROBE_OK%"=="no" (
    echo WARNING: proxy not reachable on %PHONE_IP%:1080.
    echo Is the Sacram app RUNNING on the phone? Are you connected to its network?
    echo.
)

echo Close this window to stop.
echo.
mihomo-windows-amd64-v3.exe -f config.yaml

echo.
echo mihomo exited.
pause
