# Connect via HTTP Proxy (Windows Settings)

Use this method when you just need browser / TCP traffic through the phone.
Simple, no extra programs, built into Windows.

## ⚠️ IMPORTANT CAVEAT — no UDP

An HTTP proxy is **TCP only**. Anything that needs **UDP** will NOT work:

- ❌ **Games** (matchmaking, voice chat, most multiplayer traffic)
- ❌ **Discord voice calls**
- ❌ Video calls (WhatsApp, Messenger, FaceTime-style calling)
- ❌ DNS-heavy / UDP protocols

✅ **Works**: browsing the web, downloads, videos (YouTube/Netflix),
general app HTTP(S) traffic.

If you need UDP too, use the SOCKS5 + TUN method instead (see wiki:
mihomo or Clash Verge Rev with TUN mode).

## Steps

### 1. Go to Settings
Go to Windows **Settings** (Win + I).

![Step 1 - Go to Settings](WindowsConnectDocs/GoToSettings1.png)

### 2. Click Manual proxy
Go to **Network & internet → Proxy** and turn on **Manual proxy**
(or click the option as shown).

![Step 2 - Click Manual proxy](WindowsConnectDocs/ClickManualProxy2.png)

### 3. Edit it with the configuration
Set the proxy address to the phone's:

```
Address: 192.168.49.1
Port:    8282 (HTTP default — or whatever your HTTP tab shows)
```

Make sure it's on **HTTP** (not SOCKS). Leave **"Use proxy for LAN"**
as needed (usually on).

![Step 3 - Edit with the configuration](WindowsConnectDocs/Edit.it.with.the.configuration3.png)

### 4. Click Save
Save, then open your browser — traffic now flows through the phone.

![Step 4 - Click Save](WindowsConnectDocs/ClickSave4.png)

## Notes

- The phone must be **RUNNING** with the Proxy tab on **HTTP mode**,
  and your PC must be connected to the phone's WiFi Direct network.
- To stop: turn Manual proxy back off.
- Windows applies this proxy systemwide for TCP apps, not just the browser.