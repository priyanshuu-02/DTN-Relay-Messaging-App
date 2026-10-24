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

## Per-hub configuration (no per-device source edits)

The **same binary** is flashed to every hub. Each one derives a unique `HUB_ID` = MAC bytes 2..5 and
SoftAP SSID suffix = MAC bytes 4..5 at boot (MAC `34:b7:da:64:a9:b0` → `!da64a9b0`, `DTN-HUB-A9B0`), so
you just `pio run -t upload --upload-port COMxx` to each board. (Before Oct 2026 the id came from the
OUI half of the MAC: every board advertised `DTN-HUB-B734` and ids differed in one byte.)

Serial monitor commands (115200): `stats` (dashboard now), `ping` (send a heartbeat now, the other
hubs log its RSSI/SNR), `txpow <-9..22>` (TX power until reboot, for bench link tests), `region …`.
Every LoRa frame is logged as `[RF] rx from=… type=… msg=… rssi=… snr=…` / `[RF] tx …` (header fields
only, never payload). The OLED top line shows the last frame heard: `rx <RSSI>/<SNR>dB <seconds ago>`.

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
  Since the Oct 2026 app (UI Phase 2) a phone's id is derived from its Android device id, so it stays
  the same after a data clear; it is never 0 or 0xFFFFFFFF. Hubs need no change (they route on the
  header only and never read announce payloads).
- `BUNDLE` (0x02) either direction, a self-contained DTN bundle (32-byte header + payload).
- `PING` (0x04) phone→hub every 30 s, keepalive only. A phone that PINGs is dropped after 120 s of
  silence; older apps (no PING) are dropped only when TCP keepalive finds the link dead (~90 s).
  The OLED `msg:` count shows only DMs waiting for delivery, not announces/broadcasts/receipts.
- `ROSTER` (0x05) hub→phone (v3), `[1B n][n × 4B node id]` of every phone registered here, on change
  and every 30 s, so phones on the same hub show each other online and DM each other through it.
- `MESH_ROSTER` (0x06) hub→phone, `[1B n][n × (4B phone id, 4B hub id)]` of the phones on the other
  hubs this one hears over LoRa, sent with every ROSTER. Each hub's LoRa heartbeat carries its own
  phones (`[0xD8][1][n][n × 4B]` after the 32-byte header; early when they change), so phones on
  hub A show phones on hub B as "Online · via LoRa (hub BDB8)". One hop only; entries expire after
  200 s without a heartbeat listing them. The dashboard prints them as `remote phone … on hub …`.

Oct 2026 app (UI Phase 8), no firmware change: a group member's **group receipt** is an ordinary
encrypted DM to the group message's origin (DATA, dest = origin), so the hub routes, stores and
floods it like any DM, and its ROUTING_ACK clears it. Group messages themselves still never get a
ROUTING_ACK. The app's **5 s DM fallback** may hand a DM to the hub while the destination is also in
Bluetooth range: to the hub that's just another BUNDLE (a re-send it already holds is re-delivered
locally, never re-flooded).

**v3 (`DTN_FW_VERSION=3`).** Writes to phones go through a non-blocking per-phone TX queue. v2
gated every write on `WiFiClient::availableForWrite()`, which arduino-esp32 2.0.x always reports as
0, so a v2 hub never sent anything to a phone.

**v4 (`DTN_FW_VERSION=4`).** v3 plus a **Bluetooth beacon**, so the
app's LoRa hub page lists *Available hubs* (signal, phones, DMs waiting, LoRa link to the other hubs)
from its existing Bluetooth scan, and **Join** asks Android for exactly that hub's Wi-Fi. v3 and v4
hubs interoperate (nothing changed on LoRa or TCP); only v4+ hubs are listed.

**v5 (current, `DTN_FW_VERSION=5`) — flash it to every hub.** v4 plus one routing fix: a DM whose
ROUTING_ACK already passed through this hub (its id is in the 256-entry receipt ring) is dropped when
it arrives again, from a phone or over LoRa. Before, a phone that missed the receipt (it was away when
the receipt came) and re-sent the DM after re-registering had it buffered and flooded again; that
copy then sat in the buffer ("1 DM waiting") until its TTL, because no second receipt comes. Serial:
`[LC] AckedResend     msg=… dropped (its receipt already passed here)`. Nothing else changed on LoRa,
TCP or the beacon (which now reports firmware 5): v3, v4 and v5 hubs interoperate. Size (`pio run`):
flash 903,409 → 903,613 B (+204 B), static RAM unchanged (115,648 B).

## Bluetooth beacon (v4)

NimBLE-Arduino **1.4.3** (pinned in `platformio.ini`) runs as a broadcaster only: central, observer
and peripheral roles are compiled out, so there is no GATT server, no scanning and no connection.
It shares the 2.4 GHz radio with the SoftAP through the ESP32-S3's Wi-Fi/BLE coexistence; LoRa is a
separate radio.

- One **legacy, non-connectable, non-scannable** advert (ADV_NONCONN_IND, no scan response, no
  flags), interval 1000–1100 ms, +9 dBm. Every second the hub rebuilds it and re-advertises only
  if a byte changed (a phone joined or left, a DM was buffered or delivered, another hub's
  heartbeat changed its RSSI/SNR).
- The advert's only AD structure is Manufacturer Specific Data, company id `0xFFFF` (no Bluetooth
  SIG id), then:

  | Offset | Size | Field |
  |---|---|---|
  | 0 | 2 | magic `0x44 0x48` ("DH") |
  | 2 | 1 | beacon version, 1 |
  | 3 | 4 | `HUB_ID`, big-endian |
  | 7 | 1 | firmware version (`DTN_FW_VERSION`) |
  | 8 | 1 | phones registered here (capped 255) |
  | 9 | 1 | DMs waiting (buffered DATA not addressed to broadcast, the OLED `msg:` count; capped 255) |
  | 10 | 1 | n = other hubs listed (0–4) |
  | 11 + 4i | 2 | other hub's `id & 0xFFFF` (its SSID suffix), big-endian |
  | 13 + 4i | 1 | its LoRa RSSI as −dBm (0 = none) |
  | 14 + 4i | 1 | its LoRa SNR × 4, signed |

  At most 27 bytes + the 2-byte company id = a 31-byte AD, exactly one legacy advert. Other hubs
  are those heard directly over LoRa in the last 180 s (their heartbeats), strongest first.
- Phones advertise the DTN GATT service UUID, never manufacturer data, so the app can't confuse a
  hub with a phone. Readers ignore other beacon versions; later fields are appended.
- The SSID is `DTN-HUB-` + the last 4 hex digits of `HUB_ID` (upper case), so the app derives it
  from the beacon.
- Boot banner: `BLE beacon up: v1, <15–31> B advert every ~1 s, non-connectable (NimBLE-Arduino
  1.4.3), heap used … B, free … B` (`FAILED` instead of `up` if advertising didn't start). The
  dashboard (`stats`) adds `ble beacon: on phones=… dms=… hubs=… updates=… free heap=… B`.
- Size (`pio run`, v3 → v4): static RAM 111,652 → 115,648 B (+3,996 B, 35.3 % of 320 KB), flash
  786,469 → 903,409 B (+116,940 B, 27.0 % of the 3.2 MB app partition). NimBLE's run-time heap
  (host task + controller buffers) isn't in those numbers: the boot banner prints it.

Over LoRa the hub floods bundles with message-ID **dedup**, **TTL aging**, a **hop limit** (≤ 8),
**listen-before-talk** (CAD) + random jitter, overhear-suppression, and a rolling-hour **duty-cycle**
budget. It never starts CAD or TX while a frame is arriving (PREAMBLE_DETECTED / HEADER_VALID latched
in the SX1262 IRQ register): CAD aborts an ongoing receive, which made a hub with something queued
miss the other hub's back-to-back frames. It ages the header TTL by buffer residence and replays buffered bundles to a phone when it
(re)registers (store-and-forward). An OLED status screen and battery readout are shown on the board.

## Notes / limitations (research build)

- Payload is **end-to-end encrypted by the phones** — the hub routes on the cleartext 32-byte header
  only and never needs the keys. There is no hub-to-hub link MIC yet (a network-key AES-CMAC is a
  future add; it would reduce the 223-byte LoRa payload budget).
- WPA2 still protects the phone↔hub SoftAP link; the passphrase is shared and currently in source.
- SoftAP serves ~8 concurrent phones (`MAX_CLIENTS`).
- `HDR_MIN`/offsets in `main.cpp` must track any change to `DtnWireCodec` header layout.
