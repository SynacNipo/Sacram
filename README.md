# Sacram

<p align="center">
  <img src="src/MobileApp-src/src/main/res/drawable-nodpi/ic_launcher_legacy.png" width="108" height="108" alt="Sacram launcher icon">
</p>


![Created](https://img.shields.io/badge/created-2026--08--11-blue)
![Platform](https://img.shields.io/badge/platform-Android%20%7C%20WiFi%20Direct-3DDC84)
![Made with](https://img.shields.io/badge/made%20with-Kotlin-7F52FF)
![Latest release](https://img.shields.io/github/v/release/SynacNipo/Sacram)
![Release date](https://img.shields.io/github/release-date/SynacNipo/Sacram)
![Downloads](https://img.shields.io/github/downloads/SynacNipo/Sacram/total)
![Last commit](https://img.shields.io/github/last-commit/SynacNipo/Sacram)
![Commit activity](https://img.shields.io/github/commit-activity/m/SynacNipo/Sacram)
![CI build](https://img.shields.io/github/actions/workflow/status/SynacNipo/Sacram/build.yml?label=CI%20build)
![Issues](https://img.shields.io/github/issues/SynacNipo/Sacram)
![Pull requests](https://img.shields.io/github/issues-pr/SynacNipo/Sacram)
![Stars](https://img.shields.io/github/stars/SynacNipo/Sacram)
![Forks](https://img.shields.io/github/forks/SynacNipo/Sacram)
![Repo size](https://img.shields.io/github/repo-size/SynacNipo/Sacram)
![Top language](https://img.shields.io/github/languages/top/SynacNipo/Sacram)
![License](https://img.shields.io/github/license/SynacNipo/Sacram)

> [!NOTE]
> **STABLE**
>
> Sacram is **stable** for its core use case: a systemwide TCP+UDP proxy over a
> WiFi Direct hotspot. The Android app, HTTP (TCP), SOCKS5 (TCP) and SOCKS5 UDP
> (UDP ASSOCIATE) proxies are all validated end-to-end. Pair it with any SOCKS5
> client on the PC (e.g. **Proxifier**) — verify on your setup before relying on
> it for anything critical.

## Status

| Component        | Status        | Notes                                                              |
|------------------|---------------|--------------------------------------------------------------------|
| Android app      | Stable        | Auto mode (HTTP+SOCKS5 TCP+UDP) validated over WiFi Direct |
| Connectivity     | Working       | PC reaches the internet via the phone proxy over WiFi Direct       |
| Desktop client   | Working       | Use a SOCKS5 client like **Proxifier** pointed at the phone (port `1080`) |
| HTTP (TCP) proxy | Working       | Plain HTTP + CONNECT tunnels, full-duplex                          |
| SOCKS5 (TCP)     | Working       | Runs in Auto mode alongside HTTP; validated in hybrid use          |
| SOCKS5 UDP       | Working       | UDP ASSOCIATE works (RFC 1928); validated end-to-end               |

---

Android app (Kotlin) that turns any spare Android phone into a **WiFi Direct hotspot + proxy** so a PC can reach the internet through the phone's data connection — UDP included.

1. Starts a **WiFi Direct** access point (Group Owner) with a custom, changeable SSID + password
2. Runs a proxy on the phone in **Auto mode** — both **SOCKS5** (TCP CONNECT + UDP
   ASSOCIATE, RFC 1928, port `1080`) and **HTTP** (plain HTTP + CONNECT tunnels,
   port `8282`) start together, so clients can use whichever they support. The previous
   SOCKS5-only / HTTP-only / Hybrid picker was replaced by this single Auto mode.
3. A built-in **control panel** shows live status, the app version, and a Restart
   button; the config is stored so it survives app reinstalls. The panel runs on its
   **own dedicated port** (default `http_port + 1`, i.e. `8283`) via a separate
   `PanelServer`, so it stays responsive even when the proxy is saturated by a busy
   page. Reach it directly at `http://<phone-ip>:<panel_port>/` (e.g.
   `http://192.168.49.1:8283/`). Because it is on its own port, a browser that sends
   *all* traffic through the proxy must open it directly (or add the phone IP to the
    browser's proxy bypass list); a SOCKS5 client like Proxifier can be set to
    bypass the phone subnet, so direct access to the panel works there.
4. Lets a connected PC / device reach the internet through the phone's data connection
5. Runs as an **aggressive foreground service** (wakelocks, WiFi lock, `START_STICKY`, battery-exemption + autostart shortcuts)

Full setup guide, config reference, control panel, keep-alive and telemetry
details live in the **[wiki](https://github.com/SynacNipo/Sacram/wiki)**.

## Building

CI only — GitHub Actions builds the APK on demand. Pushing to `main` with
`[Trigger]` in the commit message builds and publishes the next versioned
release (`sacram.apk`). Plain commits are gated and never build.
