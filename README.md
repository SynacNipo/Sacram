# Sacram — Network Patch Beta

<p align="center">
  <img src="src/MobileApp-src/src/main/res/drawable-nodpi/ic_launcher_legacy.png" width="108" height="108" alt="Sacram launcher icon">
</p>

![License](https://img.shields.io/github/license/SynacNipo/Sacram)
![Beta build](https://img.shields.io/github/actions/workflow/status/SynacNipo/Sacram/networkingpatch.yml?label=network-patch%20build)

> [!CAUTION]
> **This is a beta branch.** Builds here contain experimental networking
> fixes that are **not yet in the stable release**. They may introduce
> regressions. Test on a non-critical setup and report issues before using
> in production.

## What is Sacram?

Android app (Kotlin) that turns a spare Android phone into a **WiFi Direct
hotspot + proxy** so a PC can reach the internet through the phone's data
connection — UDP included.

## What's in the network patches?

The `networkingpatch` branch fixes reliability and compatibility issues
across all three proxy layers:

### SOCKS5 (`1080`)
- **TCP teardown fix** — connections now close cleanly without leaking
  sockets or hanging on FIN.
- **UDP IPv6 support** — IPv6 target addresses are now routed correctly
  through UDP ASSOCIATE.
- **Validated-egress routing** — outgoing connections are checked against
  the actual cellular/WiFi egress before binding, preventing silent
  failures on phones where Android misreports the default route.

### HTTP proxy (`8282`)
- **Any-method request bodies** — `PUT`, `PATCH`, and other non-GET/POST
  methods now forward their bodies correctly.
- **No-body responses** — `204`, `304`, and similar responses no longer
  stall waiting for a body that will never arrive.
- **Chunked transfer-encoding** — chunked response streaming is parsed and
  forwarded instead of buffered, fixing streaming endpoints.
- **IPv6 CONNECT** — HTTPS tunnelling now works over IPv6 targets.
- **Tunnel teardown** — CONNECT tunnels close promptly on client
  disconnect instead of lingering.
- **Connection-pool validation** — idle upstream connections are probed
  before reuse, eliminating stale-connection errors.

### SOCKS4a (`1081`)
- Internal cleanup and consistency fixes aligned with the SOCKS5 changes.

### Egress manager
- Improved interface enumeration and route validation used by the SOCKS5
  and HTTP layers.

## Proxy features

1. **WiFi Direct** access point with custom SSID + password
2. **Auto mode** — SOCKS5 (`1080`, TCP + UDP ASSOCIATE) and HTTP (`8282`,
   plain + CONNECT) together, plus legacy SOCKS4a (`1081`)
3. Built-in **control panel** at `http://192.168.49.1:8283/` — live status,
   per-client usage, restart (backup panel on `8284` survives crashes)
4. Aggressive foreground service (wakelocks, `START_STICKY`,
   battery-exemption + autostart shortcuts)

Full setup guide, config reference and keep-alive details live in the
**[wiki](https://github.com/SynacNipo/Sacram/wiki)**.

## Building

Pushes to `networkingpatch` with `[Trigger]` in the commit message build a
beta APK via the **networkingpatch** workflow. Plain commits are gated and
never build. The artifact is attached to the GitHub Actions run — no
published release is created for beta builds.

## Limitations

- **OEM quirks:** on some HONOR / Huawei / Xiaomi phones Android reports
  cellular as internet-capable but bound sockets don't route — some sites
  fail to load. Firmware limitation, not a proxy bug; prefer a phone whose
  cellular egress routes normally.
- **UDP is best-effort:** needs a client with real UDP ASSOCIATE support;
  no fragmentation, some carriers throttle UDP. WebRTC apps (e.g. Discord
  voice) bypass SOCKS5 for UDP entirely — nothing arrives to relay. Fall
  back to TCP when UDP misbehaves.
- **SOCKS4 needs 4a:** enable remote hostname resolving (SOCKS4a) in the
  client or hostnames won't resolve. Details on the
  [wiki](https://github.com/SynacNipo/Sacram/wiki/PC-Client#socks4--socks4a-legacy-clients).
