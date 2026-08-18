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

Full setup guide, config reference, control panel, keep-alive and telemetry
details live in the **[wiki](https://github.com/SynacNipo/Sacram/wiki)**.

## Building

CI only — GitHub Actions builds the APK on demand. Pushing to `main` with
`[Trigger]` in the commit message builds and publishes the next versioned
release (`sacram.apk`). Plain commits are gated and never build.
