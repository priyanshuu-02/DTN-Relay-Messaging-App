---
inclusion: fileMatch
fileMatchPattern: ['**/receiver/**', '**/firmware/**', '**/model/Dtn*', '**/crypto/**']
---

# DTN wire protocol (phone ⇄ phone ⇄ hub)

Keep the Android `DtnWireCodec`/`DtnMessage`, the firmware `main.cpp` offsets, and this
file in lockstep. Any change here is a cross-component change.

## Self-contained bundle header — 32 bytes, big-endian
| Off | Size | Field | Notes |
|---|---|---|---|
| 0  | 16 | messageId (UUID) | dedup key everywhere |
| 16 | 4  | originNodeId (uint32) | `NodeId.toNodeNum32()` |
| 20 | 4  | destinationNodeId (uint32) | `0xFFFFFFFF` = broadcast |
| 24 | 2  | ttlMinutes (uint16) | **counts down**; encode remaining TTL, never refresh |
| 26 | 1  | hopCount | incremented per relay hop (phone and hub share one ceiling) |
| 27 | 1  | messageType | see below |
| 28 | 1  | channel | 0 = primary / broadcast; non-zero = group channel |
| 29 | 1  | fragmentIndex | 0-based |
| 30 | 1  | fragmentTotal | 1 = unfragmented |
| 31 | 1  | flags | bit0-2 crypto suite, bit3 receipt-lock, bit4 OK_TO_INTERNET, bit5 VIA_INTERNET, bit6-7 version |

Single LoRa frame = 255 bytes (SX1262) → payload budget 255 − 32 = **223 bytes**.
`messageType` wire values: 0x00 DATA, 0x01 ROUTING_SUMMARY, 0x02 ROUTING_ACK,
0x03 BUNDLE_OFFER, 0x04 BUNDLE_REQUEST, 0x05 ANNOUNCE (key/identity).
Unknown types must be dropped (not shown as chat) — do not default-map to DATA.

## Encryption envelope (end-to-end)
- Suite 1 = X25519 static-static → HKDF-SHA256 (salt = messageId) → per-message key → AES-256-GCM
  (extended-nonce "XAES" style). Nonce derived from header (messageId‖origin‖frag) = 0 extra bytes.
- AAD = the header with **ttlMinutes and hopCount zeroed** (they mutate in transit) + receipt lock.
- Overhead = 16-byte GCM tag (+8 optional receipt lock). Encrypt first, then fragment; each
  fragment is sealed independently and dedup is on (messageId, fragmentIndex).
- Receipt lock: cleartext `trunc8(SHA-256("rcpt"‖r))` in-band; receipt reveals `r` so any carrier/hub
  can verify before dropping custody. No receipts for broadcasts.

## Phone ⇄ hub — framed TCP on :9740
`[1B type][2B length BE][payload]` — HELLO 0x03 (hub→phone, 4B hubId), REGISTER 0x01
(phone→hub, 4B nodeId), BUNDLE 0x02 (either way, a 32B-header bundle). Max frame 255.
Hub SoftAP SSID prefix `DTN-HUB-`, passphrase currently `dtnmesh123`, IP 192.168.4.1.

## Firmware (Heltec V3) invariants
- Radio: region config (IN865 866.3 / EU868 869.525 / US915 906.875), SF9, BW125 (BW500 US),
  22 dBm (region-capped), 140 mA OCP, 1.8 V TCXO (+XTAL fallback), boosted RX, preamble 16,
  sync word 0x2B, DIO2 as RF switch. Pins NSS8 DIO1-14 RST12 BUSY13 SCK9 MISO11 MOSI10.
- Hub routes on the header only; it must NOT need to decrypt. It ages TTL by buffer residence,
  enforces a rolling-hour duty-cycle budget, does CAD + jitter before TX, and replays buffered
  bundles to a phone on REGISTER. HUB_ID + SSID suffix derive from the eFuse MAC.
