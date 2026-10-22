# DTN LoRa Hub firmware

Custom firmware for the LoRa hubs — an **ESP32-S3 + SX1262** (Heltec WiFi LoRa 32 V3). Each hub is
a WiFi SoftAP that several phones join, bridged to a long-range LoRa hub-to-hub backbone. Independent
of Meshtastic.

## Build & flash (PlatformIO)

```bash
cd firmware
pio run                 # build
pio run -t upload       # flash over USB
pio device monitor      # serial log @115200
```

(Or open this folder in VS Code with the PlatformIO extension.)

## Per-hub configuration (v2 — no per-device source edits)

The **same binary** is flashed to every hub. Each one derives a unique `HUB_ID` and SoftAP SSID
suffix from its ESP32 eFuse MAC at boot (e.g. `DTN-HUB-B734`, hub id `!65dab734`), so you just
`pio run -t upload --upload-port COMxx` to each board.

The LoRa **region** is stored in NVS and defaults to **IN865** (India, 866.3 MHz). Change it over the
serial monitor at runtime — type one of:

```
region in    # IN865  866.3 MHz  (India)
region eu    # EU868  869.525 MHz
region us    # US915  906.875 MHz, BW500
```

The compile-time default is `-D DTN_DEFAULT_REGION` in `platformio.ini` (0=IN, 1=EU, 2=US).
SoftAP SSID prefix (`DTN-HUB-`), passphrase (`dtnmesh123`), TCP port (`9740`) and the frame protocol
stay in sync with `LoRaHubTransportAdapter.kt`. **Attach the LoRa antenna before powering on** —
transmitting at 22 dBm without one can damage the radio.

## Protocol (matches the Android app)

Framed TCP on port 9740: `[1B type][2B length BE][payload]`

- `HELLO` (0x03) hub→phone, 4B `HUB_ID`, sent once on connect so the phone treats the hub as a peer.
- `REGISTER` (0x01) phone→hub, 4B phone node id, so the hub can deliver bundles back to that phone.
- `BUNDLE` (0x02) either direction, a self-contained DTN bundle (32-byte header + payload).
- `PING` (0x04) phone→hub every 30 s, keepalive only. A phone that PINGs is dropped after 120 s of
  silence; older apps (no PING) are dropped only when TCP keepalive finds the link dead (~90 s).
  The OLED `msg:` count shows only DMs waiting for delivery, not announces/broadcasts/receipts.
- `ROSTER` (0x05) hub→phone (v3), `[1B n][n × 4B node id]` of every phone registered here, on change
  and every 30 s, so phones on the same hub show each other online and DM each other through it.

**v3 (current, `DTN_FW_VERSION=3`) — flash it to every hub.** Writes to phones go through a
non-blocking per-phone TX queue. v2 gated every write on `WiFiClient::availableForWrite()`, which
arduino-esp32 2.0.x always reports as 0, so a v2 hub never sent anything to a phone.

Over LoRa the hub floods bundles with message-ID **dedup**, **TTL aging**, a **hop limit** (≤ 8),
**listen-before-talk** (CAD) + random jitter, overhear-suppression, and a rolling-hour **duty-cycle**
budget. It ages the header TTL by buffer residence and replays buffered bundles to a phone when it
(re)registers (store-and-forward). An OLED status screen and battery readout are shown on the board.

## Notes / limitations (research build)

- Payload is **end-to-end encrypted by the phones** — the hub routes on the cleartext 32-byte header
  only and never needs the keys. There is no hub-to-hub link MIC yet (a network-key AES-CMAC is a
  future add; it would reduce the 223-byte LoRa payload budget).
- WPA2 still protects the phone↔hub SoftAP link; the passphrase is shared and currently in source.
- SoftAP serves ~8 concurrent phones (`MAX_CLIENTS`).
- `HDR_MIN`/offsets in `main.cpp` must track any change to `DtnWireCodec` header layout.
