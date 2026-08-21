# Sacram

![Created](https://img.shields.io/badge/created-2026--08--11-blue)
![Latest release](https://img.shields.io/github/v/release/SynacNipo/Sacram)

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
| Android app      | Alpha         | Auto mode (HTTP+SOCKS5 TCP) validated over WiFi Direct; UDP/TUN still alpha |
| Connectivity     | Working (TCP) | PC reaches the internet via the phone proxy over WiFi Direct       |
| Windows client   | Alpha         | `SacramConnect.bat` (sing-box TUN) launches; DNS/routing handled by sing-box TUN (verify end-to-end) |
| HTTP (TCP) proxy | Working       | Plain HTTP + CONNECT tunnels, full-duplex                          |
| SOCKS5 (TCP)     | Working       | Runs in Auto mode alongside HTTP; validated in hybrid use          |
| SOCKS5 UDP       | Paused        | UDP ASSOCIATE exists; dev paused, not actively worked on           |

---

Android app (Kotlin) that turns any spare Android phone into a **WiFi Direct hotspot + proxy** so a PC can reach the internet through the phone's data connection — UDP included.

1. Starts a **WiFi Direct** access point (Group Owner) with a custom, changeable SSID + password
2. Runs a proxy on the phone in **Auto mode** — both **SOCKS5** (TCP CONNECT + UDP
   ASSOCIATE, RFC 1928, port `1080`) and **HTTP** (plain HTTP + CONNECT tunnels,
   port `8282`) start together, so clients can use whichever they support. The previous
   SOCKS5-only / HTTP-only / Hybrid picker was replaced by this single Auto mode.
3. A built-in **control panel** (served by the HTTP proxy) shows live status, the app
   version, and a Restart button; the config is stored so it survives app reinstalls.
4. Lets a connected PC / device reach the internet through the phone's data connection
5. Runs as an **aggressive foreground service** (wakelocks, WiFi lock, `START_STICKY`, battery-exemption + autostart shortcuts)

Full setup guide, config reference, control panel, keep-alive and telemetry
details live in the **[wiki](https://github.com/SynacNipo/Sacram/wiki)**.

## Building

CI only — GitHub Actions builds the APK on demand. Pushing to `main` with
`[Trigger]` in the commit message builds and publishes the next versioned
release (`sacram.apk`). Plain commits are gated and never build.
