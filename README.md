# Sacram UDP Bridge

> [!WARNING]
> **UNSTABLE — ALPHA — NOT PRODUCTION READY**
>
> This project is in **early alpha**. Expect breaking changes, bugs, and
> partial implementations. **HTTP (TCP) proxy connectivity is now confirmed
> working** over WiFi Direct — the PC reaches the internet through the phone's
> HTTP proxy. UDP + TUN path is still being debugged. Do not rely on this for
> anything real yet.

## Status

| Area            | State                                                                 |
|-----------------|-----------------------------------------------------------------------|
| Android app     | Alpha — proxy + WiFi Direct logic exists but unvalidated end-to-end    |
| Connectivity    | **Working (HTTP/TCP)** — PC reaches the internet through the phone HTTP proxy over WiFi Direct |
| Windows client  | Alpha — `SacramConnect.bat` (mihomo TUN) starts but DNS/routing still being fixed |
| HTTP (TCP) proxy| **Working** — plain HTTP + CONNECT tunnels fixed (full-duplex)               |
| UDP dev         | **Paused** — SOCKS5 UDP ASSOCIATE exists but verification/support on hold    |

Known open issues being worked on:
- mihomo TUN starts but upstream DNS resolution was failing (public DoH
  unreachable through the phone proxy); now pointed at the phone gateway.
- UDP/QUIC (`can't resolve ip`) failures need verification after the DNS fix.
- The auto-detected "phone" gateway may be wrong when the phone isn't the
  default route.

---

Android app (Kotlin) that turns any spare Android phone into a **WiFi Direct hotspot + proxy** so a PC can reach the internet through the phone's data connection — UDP included.

1. Starts a **WiFi Direct** access point (Group Owner) with a custom, changeable SSID + password
2. Runs a proxy on the phone:
   - **SOCKS5** (Proxy tab) — TCP CONNECT + UDP ASSOCIATE (RFC 1928), default port `1080`
   - **HTTP** (HTTP tab) — plain HTTP proxy + CONNECT tunnels, TCP only, default port `8282`
3. Lets a connected PC / device reach the internet through the phone's data connection
4. Runs as an **aggressive foreground service** (wakelocks, WiFi lock, `START_STICKY`, battery-exemption + autostart shortcuts)

Full setup guide, Windows client configs and troubleshooting live in the **[wiki](https://github.com/SynacNipo/Sacram/wiki)**.

## Config file

Persisted on the phone in a `Sacram` folder:

- Internal (system) copy: `/data/data/com.sacram.proxy/files/Sacram/config.txt` (app-private)
- User-editable mirror: `Android/data/com.sacram.proxy/files/Sacram/config.txt` (visible in a file manager, no root)

Format (`key=value`, edited externally = auto-applied while running):

```
ssid=SacramAP
password=sacram1234
port=1080
proxy_mode=socks5|http
proxy_type=0|1|2
http_port=8282
telemetry_prompted=true
telemetry_enabled=true
collector_url=https://sacram-telemetry.synacnipo.workers.dev
```

`proxy_type` (optional): `0` = use `proxy_mode` as-is (default), `1` = UDP/SOCKS5, `2` = HTTP.

Constraints enforced by Android:
- SSID gets the required `DIRECT-xy` prefix automatically (e.g. `DIRECT-SASacramAP`)
- Password must be **8–63 ASCII characters**
- Port must be 1–65535

## Using it (short version)

1. Open the app → **START PROXY** (grant location / nearby-wifi / notification permissions when asked)
2. Connect your PC to the SSID shown on screen, using the shown password
3. On the PC, configure a proxy client at `192.168.49.1:<port>` (the phone's WiFi Direct GO IP)

For systemwide **TCP + UDP** (games, DNS, UDP apps) the PC client must use a
**TUN-mode** client — a plain "system proxy" setting only handles HTTP(S) apps
and will NOT carry UDP. See the [wiki](https://github.com/SynacNipo/Sacram/wiki) for
mihomo and Clash Verge Rev setup.

## Keep-alive (so the OS doesn't kill it)

Done automatically by the app:
- Foreground service `connectedDevice` type + persistent notification
- Partial wake lock + WiFi lock (`WIFI_MODE_FULL_HIGH_PERF`)
- `START_STICKY`, continues after task swipe
- Battery-exemption and autostart shortcut buttons (Keep-Alive tab)

Also recommended once on the phone (menu names vary by brand — the wiki's
[Keep-Alive page](https://github.com/SynacNipo/Sacram/wiki/Keep-Alive) covers it):
disable battery optimization for the app, enable Autostart if available, and
lock the app in the recent-apps list.

## Telemetry (anonymous, opt-in)

The app can send anonymous usage data — device model, Android version, app
version, proxy success/errors, and a 30-minute heartbeat while the app is
open. **No SSID, password, IP or personal data is ever sent.** You choose on
first launch; toggle anytime via `telemetry_enabled` in config.txt.

## Building

CI only — GitHub Actions builds the APK on demand. Pushing to `main` with
`[Trigger]` in the commit message builds and publishes the next versioned
release (`sacram.apk`). Plain commits are gated and never build.

## API notes (researched)

- `WifiP2pManager.createGroup(Channel, WifiP2pConfig, ActionListener)` honors `WifiP2pConfig.Builder.setNetworkName()` / `setPassphrase()` (SSID must match `^DIRECT-[a-zA-Z0-9]{2}`, passphrase 8–63)
- Requires runtime `ACCESS_FINE_LOCATION` (AOSP `@RequiresPermission` on createGroup) and `NEARBY_WIFI_DEVICES` on Android 13+
- `android:foregroundServiceType="connectedDevice"` (API 31+); `FOREGROUND_SERVICE_CONNECTED_DEVICE` permission needed when targeting API 34+
- SOCKS5 UDP = RFC 1928 UDP ASSOCIATE (fragmentation dropped per spec, domain targets resolved server-side)