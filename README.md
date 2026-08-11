# Sacram UDP Bridge

Android app (Kotlin) for the **Vivo Y16** (Android 12 / Funtouch OS 12) that:

1. Starts a **WiFi Direct** access point (Group Owner) with a custom, changeable SSID + password
2. Runs a **SOCKS5 proxy server** (RFC 1928) supporting **TCP CONNECT** and **UDP ASSOCIATE** on the phone
3. Lets a connected PC / device reach the internet through the phone's data connection — UDP included
4. Runs as an **aggressive foreground service** (wakelocks, WiFi lock, `START_STICKY`, battery-exemption request, Vivo autostart shortcut)

## Config file

Persisted on the phone in a `Sacram` folder:

- Internal (system) copy: `/data/data/com.sacram.proxy/files/Sacram/config.txt` (app-private)
- User-editable mirror: `Android/data/com.sacram.proxy/files/Sacram/config.txt` (visible in a file manager, no root)

Format (`key=value`, edited externally = auto-applied while running):

```
ssid=SacramAP
password=sacram1234
port=1080
```

Constraints enforced by Android 12:
- SSID gets the required `DIRECT-xy` prefix automatically (e.g. `DIRECT-SASacramAP`)
- Password must be **8–63 ASCII characters**
- Port must be 1–65535

## Using it

1. Open the app → **START PROXY** (grant location / nearby-wifi / notification permissions when asked)
2. Connect your PC to the SSID shown on screen, using the shown password
3. On the PC, configure a SOCKS5 proxy at `192.168.49.1:<port>` (the phone's WiFi Direct GO IP)

## Client program for systemwide UDP (what to use)

**Clash Verge Rev** (free, open source) — recommended.

- Add a proxy: `type: socks5`, `server: 192.168.49.1`, `port: <port>`, `udp: true`
- Enable **TUN Mode** (Settings → TUN Mode; install the service / grant admin once). TUN creates a virtual NIC and captures **ALL traffic systemwide: TCP, UDP and ICMP** — browsers, games, DNS, UWP apps, CLI tools. This is what makes UDP work end-to-end.

Alternatives (all support systemwide TCP+UDP via TUN):
- **Netch** (Windows, TUN mode)
- **v2rayN** (Windows, TUN mode)
- **SSTap** (Windows, driver-based)

> Note: a plain "system proxy" setting on the PC only handles HTTP(S)/TCP apps and will NOT carry UDP. Use TUN mode.

## Vivo Y16 keep-alive (so the OS doesn't kill it)

Done automatically by the app:
- Foreground service `connectedDevice` type + persistent notification
- Partial wake lock + WiFi lock (`WIFI_MODE_FULL_HIGH_PERF`)
- `START_STICKY`, continues after task swipe
- "Request battery exemption" button (ignores battery optimization)

Also do once in Funtouch OS settings:
1. **Settings → More settings → Applications → Autostart** → enable Sacram
2. **Settings → Battery → High background power consumption** → enable Sacram
3. Lock the app in the recent-apps list (padlock icon)
4. **Settings → Battery → Battery optimization** → set Sacram to "Not optimized"
5. **Settings → Battery → Sleep Standby** → exclude Sacram

## Building

CI only — GitHub Actions builds the APK (no local machine load). Download `sacram-udp-bridge-apk` from the **Actions** tab after each push, or use workflow_dispatch.

## API notes (researched)

- Vivo Y16 = Android 12 (API 31/32), Helio P35
- `WifiP2pManager.createGroup(Channel, WifiP2pConfig, ActionListener)` exists on Android 12 and honors `WifiP2pConfig.Builder.setNetworkName()` / `setPassphrase()` (SSID must match `^DIRECT-[a-zA-Z0-9]{2}`, passphrase 8–63)
- Requires runtime `ACCESS_FINE_LOCATION` (AOSP `@RequiresPermission` on createGroup) and `NEARBY_WIFI_DEVICES` on Android 13+
- `android:foregroundServiceType="connectedDevice"` (API 31+); `FOREGROUND_SERVICE_CONNECTED_DEVICE` permission needed only when targeting API 34+
- SOCKS5 UDP = RFC 1928 UDP ASSOCIATE (fragmentation dropped per spec, domain targets resolved server-side)
