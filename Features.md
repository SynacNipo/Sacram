# Sacram — Feature Reference

Every user-facing and system feature shipped in the Sacram Android app. The app
turns a spare Android phone into a **WiFi Direct hotspot + proxy** so a PC/device
can reach the internet through the phone's mobile-data connection (TCP, and UDP
via SOCKS5).

Status legend: ✅ Stable · 🟡 Refined/validated · ⏸️ Paused/experimental

---

## Feature table

| # | Feature | Category | Description | Status / Notes |
|---|---------|----------|-------------|----------------|
| 1 | WiFi Direct hotspot (Group Owner) | Connectivity | Creates a WiFi Direct access point with a custom, changeable SSID + password the client connects to. | ✅ |
| 2 | Custom SSID & password | Connectivity | User-configured hotspot credentials, shown to clients so they can join. | ✅ |
| 3 | WiFi band selection | Connectivity | Pick **2.4 GHz** (default/farther), **5 GHz** (faster/shorter), or **Auto**; enforced via reflection on the hidden `groupOwnerBand` field. | ✅ |
| 4 | Disable band selector | Connectivity | Toggle that hides the band dropdown and forces 2.4 GHz (for phones that ignore the chosen band). | ✅ |
| 5 | Hotspot auto-enable | Connectivity | Attempts to turn WiFi on automatically at start (`ensureWifiOn`). | ✅ |
| 6 | Group re-create on inactivity | Connectivity | If Android silently tears down the P2P group, it is recreated in place (same SSID/pass) to keep the AP alive. | ✅ |
| 7 | Cellular egress binding | Connectivity | Proxy traffic is bound to the phone's **mobile data** network, never the WiFi-Direct interface. Resolution order: active network → cached cellular → last-good cellular → system default route. | ✅ |
| 8 | Auto mode (SOCKS5 + HTTP) | Proxy | Default mode runs the SOCKS5 and HTTP proxies together so any client can use whichever it supports. | ✅ |
| 9 | SOCKS5 proxy (TCP) | Proxy | RFC 1928 server: `CONNECT` (TCP) + `UDP ASSOCIATE`, default port **1080**. | ✅ |
| 10 | SOCKS5 UDP ASSOCIATE | Proxy | UDP relay for SOCKS5 clients; sessions capped at **1024** with a **300s** sweep. | ⏸️ Implemented; status matrix marks it Paused |
| 11 | HTTP proxy (plain + CONNECT) | Proxy | RFC-style HTTP proxy: absolute-form requests + `CONNECT` tunnels (HTTPS), default port **8282**. | ✅ |
| 12 | HTTP chunked / Content-Length | Proxy | Handles `Transfer-Encoding: chunked` and `Content-Length` responses; forces close-delimited bodies closed so pages don't hang. | ✅ |
| 13 | HTTP connection pooling | Proxy | Reuses upstream sockets per host (pool max 48, idle 60s) to cut connect overhead. | ✅ |
| 14 | HTTP DNS cache | Proxy | Caches resolved hosts (TTL 60s) on the egress network to avoid repeated lookups. | ✅ |
| 15 | Stale-egress auto-heal (HTTP) | Proxy | If outbound requests keep failing with no successes for 2 min, the proxy asks the service to restart and re-bind a fresh cellular network. | ✅ |
| 16 | Retry on fresh upstream | Proxy | Safe methods (GET/HEAD/OPTIONS/TRACE) retry once on a fresh socket if the first attempt used a dead pooled connection. | ✅ |
| 17 | Dedicated control-panel server | Panel | `PanelServer` runs the web UI on its **own port** (default **8283** = `http_port + 1`) with its own 16-thread pool, fully independent of proxy traffic. | ✅ New |
| 18 | Panel live status | Panel | Shows status, running, uptime, mode, SSID, password, group IP, clients, open TCP tunnels, version, panel port. | ✅ |
| 19 | Panel `/api/status` JSON | Panel | Machine-readable status endpoint polled every 5s by the page to live-update. | ✅ |
| 20 | Panel restart button | Panel | Restarts the proxy + hotspot from the web UI. | ✅ |
| 21 | Panel settings form | Panel | Edit keep-alive URL, keep-alive interval, telemetry, panel-enabled, and WiFi band live from the browser. | ✅ |
| 22 | In-app owner approval | Panel | Restart/config changes from the panel trigger a **10-second** approve/deny dialog in the app; ignored/denied = dropped. | ✅ |
| 23 | "Require approval for restart" | Panel | Toggle gating panel restarts behind the in-app approval window (or restart immediately when off). | ✅ |
| 24 | Panel reachable by anyone on WiFi | Panel | The control panel is open to any device on the WiFi Direct network (with the approval gate for changes). | ✅ |
| 25 | "Panel moved" redirect page | Panel | Requests to the old panel URL on the proxy port now return an info page pointing to the dedicated panel port. | ✅ New |
| 26 | Aggressive foreground service | Reliability | `START_STICKY` foreground service of type `connectedDevice` so OEM task-killers don't stop it. | ✅ |
| 27 | Wake lock + WiFi high-perf lock | Reliability | Held `PARTIAL_WAKE_LOCK` + `WIFI_MODE_FULL_HIGH_PERF` for the session. | ✅ |
| 28 | Boot auto-start | Reliability | `BootReceiver` starts the service on `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED` (after update). | ✅ |
| 29 | Watchdog (AlarmManager) | Reliability | `WatchdogReceiver` pings every **60s** and restarts the service if `shouldRun` is still true. | ✅ |
| 30 | Battery-exemption shortcut | Reliability | Deep-link to request ignoring battery optimizations. | ✅ |
| 31 | Autostart settings shortcut | Reliability | OEM-specific intents (Honor/Huawei/Xiaomi) to enable autostart. | ✅ |
| 32 | Config: three-tier persistence | Config | Stored in app-private dir, an `Android/data/...` mirror, **and** `Documents/Sacram/config.txt` via MediaStore so it survives uninstall/reinstall. | ✅ |
| 33 | Live config reload (FileObserver) | Config | Editing the external config file triggers an immediate restart to apply changes. | ✅ |
| 34 | UI autosave | Config | Settings auto-save ~1.2s after the last edit to `config.txt`. | ✅ |
| 35 | Configurable ports | Config | SOCKS5 port (default 1080), HTTP port (default 8282), panel port (default 8283). | ✅ |
| 36 | Proxy-type selection (config) | Config | `proxy_type`: Auto (0) / SOCKS5 (1) / HTTP (2) / Hybrid (3); UI currently surfaces Auto. | 🟡 |
| 37 | Two tabs + swipe switch | UX | Proxy tab and Keep-Alive tab; horizontal swipe switches between them (`SwipeScrollView`). | ✅ |
| 38 | Live connection info | UX | App shows SSID, password, SOCKS5/HTTP/panel addresses, clients; notification shows the same + a STOP action. | ✅ |
| 39 | In-app update check + install | UX | Checks GitHub for a newer release, downloads the APK in the background, and launches the installer. | ✅ |
| 40 | Telemetry opt-in prompt | UX | First-run dialog explaining anonymous data collection before enabling. | ✅ |
| 41 | Easter egg | UX | 7 quick taps on the status text shows a hidden message. | ✅ |
| 42 | Anonymous telemetry | Privacy | Opt-in: device/model/Android/version, events, and **sampled** host + HTTP status only (no SSID/password/IP/full URLs). Failures always reported; successes sampled ~1/10. | ✅ |
| 43 | Configurable keep-alive ping | Privacy | Pings a URL on an interval (min 15s) to keep the OS from idling the connection; default `google generate_204`. | ✅ |
| 44 | CI-only signed release | Build | GitHub Actions builds `assembleRelease`, auto-increments the semver tag, signs with the CI keystore, publishes `sacram.apk` + SHA256. | ✅ |
| 45 | Build gating (`[Trigger]`/`[DEBUG]`) | Build | Plain commits are skipped; `[Trigger]` builds+publishes, `[DEBUG]` builds only. | ✅ |

---

## How the pieces fit

- **Auto mode** starts both proxies + the panel server together.
- The **panel** lives on its own port so a busy proxy tab can never starve it.
- **Reliability** layers (foreground service, wake/ WiFi locks, boot + watchdog
  receivers, stale-egress auto-heal) keep the tunnel up on aggressive OEM ROMs.
- **Config** is mirrored into `Documents/` so it outlives an app reinstall, and a
  `FileObserver` hot-reloads edits.
