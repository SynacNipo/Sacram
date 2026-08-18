# Sacram

> [!WARNING]
> **ALPHA — HTTP/HTTPS PROXY ONLY IS STABLE**
>
> This project is in **early alpha**. Expect breaking changes and bugs in the
> UDP + TUN path. The **HTTP/HTTPS (TCP) proxy over WiFi Direct is confirmed
> stable** — the PC reliably reaches the internet through the phone's HTTP
> proxy. UDP + TUN support is still being debugged. Do not rely on anything
> beyond the HTTP/HTTPS proxy for anything real yet.

## Status

| Component        | Status        | Notes                                                              |
|------------------|---------------|--------------------------------------------------------------------|
| Android app      | Alpha         | Proxy + WiFi Direct logic present, not validated end-to-end        |
| Connectivity     | Working (TCP) | PC reaches the internet via the phone proxy over WiFi Direct       |
| Windows client   | Alpha         | `SacramConnect.bat` (sing-box TUN) launches; DNS/routing handled by sing-box TUN (verify end-to-end) |
| HTTP (TCP) proxy | Working       | Plain HTTP + CONNECT tunnels, full-duplex                          |
| SOCKS5 (TCP)     | Unverified    | Bidirectional pump reworked; not yet validated end-to-end         |
| SOCKS5 UDP       | Paused        | UDP ASSOCIATE exists; dev paused, not actively worked on           |

---

Android app (Kotlin) that turns any spare Android phone into a **WiFi Direct hotspot + proxy** so a PC can reach the internet through the phone's data connection — UDP included.

1. Starts a **WiFi Direct** access point (Group Owner) with a custom, changeable SSID + password
2. Runs a proxy on the phone (pick the mode with the **Proxy Type** dropdown):
   - **SOCKS5** — TCP CONNECT + UDP ASSOCIATE (RFC 1928), default port `1080`
   - **HTTP** — plain HTTP proxy + CONNECT tunnels, TCP only, default port `8282`
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
collector_token=YOUR_WORKER_VIEW_TOKEN
keepalive_url=https://sacram-telemetry.synacnipo.workers.dev/keepalive
keepalive_interval_ms=60000
wifi_autorestore_min=5
panel_enabled=true
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
and will NOT carry UDP. Use the bundled sing-box client
(`SacramConnect.bat`) for systemwide TCP + UDP instead.

## Control panel

While the **HTTP proxy** is running, open a browser on the connected PC/device
and visit `http://192.168.49.1:8282/` (or `http://<group-ip>:<http-port>/`).
Because the browser connects *directly* to the proxy, Sacram serves a small
local **control panel** instead of forwarding the request:

- Live status: running state, uptime, mode, SSID/password, group IP, client count
- Edit keep-alive URL + interval, WiFi auto-restore minutes, telemetry and panel
  toggles — changes apply **live** (no restart needed)

The panel is reachable by **anyone on the WiFi Direct network**, so only enable
it on networks you trust. Disable it with `panel_enabled=false` in `config.txt`
or via the panel's own checkbox. It does not affect normal proxy/CONNECT traffic
— only requests aimed at the proxy's own address.

**Owner approval:** any settings change submitted from the panel does NOT apply
immediately. Instead the **Sacram app shows an in-app Approve / Deny prompt**; the
change is applied only if the owner approves within 10 seconds, otherwise the
request is silently dropped. This stops a device on the network from reconfiguring
the proxy (e.g. changing the hotspot password) without the owner's consent.

## Keep-alive (so the OS doesn't kill it)

Done automatically by the app:
- Foreground service `connectedDevice` type + persistent notification
- Partial wake lock + WiFi lock (`WIFI_MODE_FULL_HIGH_PERF`)
- `START_STICKY`, continues after task swipe
- **Network heartbeat** — while the proxy runs, the app pings a pre-listed URL
  (`keepalive_url`, default every `keepalive_interval_ms=60000`) so the OS sees
  ongoing traffic and is less likely to idle / Doze / kill the process. Configure
  it in the **Keep-Alive** tab or `config.txt`. This is a supplement to the items
  below, not a replacement for them. Each heartbeat also reports the app's release
  version in the `X-Sacram-Version` header and `Sacram-KeepAlive/<version>`
  User-Agent, so the telemetry endpoint can see which build is alive. The version
  is set automatically by CI from the computed release tag (`-PappVersion`).
- **WiFi radio auto-restore** — if the WiFi radio gets turned off while the proxy
  is meant to run, the app waits `wifi_autorestore_min` (default `5`) minutes and
  then re-enables WiFi and rebuilds the hotspot. Set `0` to disable. It does **not**
  distinguish who turned WiFi off — stopping the proxy also stops auto-restore.
- Battery-exemption and autostart shortcut buttons (Keep-Alive tab)

Also recommended once on the phone (menu names vary by brand — the wiki's
[Keep-Alive page](https://github.com/SynacNipo/Sacram/wiki/Keep-Alive) covers it):
disable battery optimization for the app, enable Autostart if available, and
lock the app in the recent-apps list.

> Note: the network heartbeat helps against Android's own Doze / idle deferral.
> Aggressive OEM task-killers (Xiaomi, Huawei, Honor, …) only truly respect a
> foreground service once the user grants battery-exemption / autostart — the
> heartbeat alone will not override their auto-kill policies.

## Telemetry (anonymous, opt-in)

The app can send anonymous usage data to help improve the proxy. When enabled,
it sends:

- Device model, Android version, app version
- Proxy lifecycle events, errors and heartbeats
- **The domain names (hosts) of sites you access through the proxy and their
  HTTP status codes** — sampled (~1 in 10 successful requests, all failures) so
  a busy browsing session can't flood the store. This is used to debug
  connection drops and is the main reason telemetry exists.

**No full URLs, search queries, SSID, password or IP addresses are ever sent.**
You are shown exactly what is collected and must explicitly agree before it is
enabled (first-launch prompt with a consent checkbox); toggle it off anytime via
`telemetry_enabled=false` in config.txt.

### Locking down the collector

Ingestion (`POST /collect`) is **open** so the opted-in app sends telemetry with
no per-user secret — that's how telemetry normally works, and the store is
anonymous and capped so abuse is bounded.

Reading the data (dashboard, `/data`, `/stats`) is **locked** behind a secret
token so nobody can view the collected domains/status. Set it once on the
worker:

```bash
wrangler secret put VIEW_TOKEN   # any value you choose
```

Then view the dashboard at `https://<your-worker>/?token=<VIEW_TOKEN>`; a
session cookie is set so sub-links keep working without re-passing the token in
the URL. The optional `collector_token` field in `config.txt` is only used if
you also want the app to authenticate its writes — it is **not required** for
telemetry to work.

## Building

CI only — GitHub Actions builds the APK on demand. Pushing to `main` with
`[Trigger]` in the commit message builds and publishes the next versioned
release (`sacram.apk`). Plain commits are gated and never build.
