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
`[1B type][2B length BE][payload]`, max frame 255. Hub SoftAP SSID prefix `DTN-HUB-`, passphrase
currently `dtnmesh123`, IP 192.168.4.1.
- REGISTER 0x01 (phone→hub, 4B nodeId) — triggers a replay of buffered bundles for that phone.
- BUNDLE 0x02 (either way, a 32B-header bundle).
- HELLO 0x03 (hub→phone, 4B hubId).
- PING 0x04 (phone→hub, 1B) every 30 s — keepalive only. Only a phone that has PINGed is dropped
  after 120 s of silence; older apps never PING, so they're only dropped when TCP keepalive
  (60 s idle + 3 × 10 s probes) finds the link dead.
- ROSTER 0x05 (hub→phone, firmware v3+) `[1B n][n × 4B nodeId]` = every phone registered at the
  hub; sent on change and every 30 s. The app marks those phones online ("via LoRa hub", rssi −200
  = no own signal). Older apps ignore unknown frame types.
- App send rules: a DM for a roster phone goes to the hub as `HUB_LOCAL` (header dest = that
  phone, not a direct link → waits for the receipt, 30 s retry). The hub contact gets every other
  DM once per hub session, bypassing the routing strategy, unless the destination is reachable
  directly / via the roster / on the relay. Roster-only phones get no broadcasts, receipts,
  custody or routing summaries from us — the hub fans those out.

## Phone ⇄ phone over BLE (primary)
GATT service UUID in the advert; scan-response service data `[4B nodeId][1B flags][4B nonce]`
(27 B total, fits the 31 B legacy limit). Flags: bit0 owns a DTN Wi-Fi Direct group, bit1 Wi-Fi
Direct running, bits2-5 group members (0-15). Readers of only the first 4 bytes keep working.

## Phone ⇄ phone over Wi-Fi Direct (groups, Android 10+, no invitations)
- One phone owns a group `DIRECT-DT-<hex8>`; passphrase = first 10 bytes (hex, 20 chars) of
  SHA-256(`"DTN-wfd-v1|<hex8>|<%08x nonce>"`). Others join with `WifiP2pConfig.Builder`
  (name + pass) → no prompt on either phone. Never the legacy invite `connect`.
- Discovery: the BLE hint above (credentials derived) + DNS-SD `_dtnmesh._tcp` (instance `dtnmesh`)
  TXT `v=2`, `id=<hex8>`, and for owners `g=<name>`, `k=<pass>`, `n=<members>`. `v=1` = old build,
  ignored. Only phones advertising this are ever joined — never TVs / printers.
- Election (`WifiDirectGroupPolicy`): join the best visible group (most members, then lowest
  owner id); with none, the lowest node id creates (others wait 25 s, then create themselves);
  an owner with no members merges into a group with members or a lower owner; an owner alone
  for 120 s with nobody around disbands.
- TCP :9734, `[4B wireLength][wire][4B idLen][id]`; wireLength 0 = HELLO (node-id ↔ IP, heartbeat
  every 10 s, peer stale after 30 s); wireLength −2 = ROSTER `[2B len]["hex@ip,hex@ip,…"]` from the
  owner so members link to each other directly. A TCP write inside the group = delivered.

## Groups
- Channel byte (1..255) of a NEW group = `1 + (SHA-256("DTN-grp-ch-v1" ‖ key)[0] mod 255)`.
  Invites carry the channel (`dtngrp:<ch>:<name>:<b64 key>`, key = text after the LAST `:`), so
  legacy sequential-channel invites still work. Joining never overwrites a different local group
  on the same channel (refused as a conflict).
- Group/broadcast DATA is dest `0xFFFFFFFF` + channel; each peer gets a bundle once per
  encounter/presence session (never echoed to whoever handed it over).

## Phone ⇄ internet relay (MQTT, optional)
- Topics under `dtn/v1`: `node/<hex8>` (bundles for one node: DMs, receipts, per-peer catch-up),
  `bcast` (broadcast/group/announce, published once by the origin), `presence/<hex8>` (retained).
- Payload = `[0xD7][0x01][4B publisher node num BE][wire bytes]` (raw wire also accepted).
  The publisher prefix is unauthenticated: never trust it — ROUTING_SUMMARY over the relay is dropped.
- Presence = retained ASCII `on:<epochSec>` / `off:<epochSec>`; Last Will = `off`; heartbeat 60 s;
  stale after ~3 missed heartbeats; retained `on` older than 10 min is ignored.
  Client id `dtn-<hex8>` (stable, so a reconnect takes over and fires the old will first).
- A relay DM is published to the destination's node topic only while it is present, and stays
  buffered until the end-to-end receipt returns (PUBACK ≠ delivery). No custody over the relay.
- Default broker `ssl://broker.hivemq.com:8883` (TLS). The client resolves DNS itself and tries
  **every IPv4 address before any IPv6** (many ISPs hand out black-holed IPv6). For the public
  HiveMQ host it rotates TLS 8883 → TCP 1883 → WSS 8884 (`/mqtt`) on each failed attempt; a custom
  broker URL is used as given (`RelayEndpoints`). Publishes never run on the main thread.

## Firmware (Heltec V3) invariants
- Radio: region config (IN865 866.3 / EU868 869.525 / US915 906.875), SF9, BW125 (BW500 US),
  22 dBm (region-capped), 140 mA OCP, 1.8 V TCXO (+XTAL fallback), boosted RX, preamble 16,
  sync word 0x2B, DIO2 as RF switch. Pins NSS8 DIO1-14 RST12 BUSY13 SCK9 MISO11 MOSI10.
- Hub routes on the header only; it must NOT need to decrypt. It ages TTL by buffer residence,
  enforces a rolling-hour duty-cycle budget, does CAD + jitter before TX, and replays buffered
  bundles to a phone on REGISTER. HUB_ID + SSID suffix derive from the eFuse MAC.
- v3 (`DTN_FW_VERSION=3`): writes to phones go through a per-client non-blocking TX queue
  (4 KB, whole frames only, `send(MSG_DONTWAIT)`). Never gate on `WiFiClient::availableForWrite()`:
  arduino-esp32 2.0.x always returns 0, which made v2 hubs silently send nothing to any phone.
- v3 routing: ROUTING_ACK goes to every local phone except the sender (and floods); broadcasts
  skip the sender; a REGISTER retires that node's stale connection; a bundle a phone re-sends
  that the hub already holds is re-delivered locally (DM) or ignored (broadcast / ack), never
  re-flooded. Idle checks use signed `(int32_t)(millis() - lastSeen)` (unsigned wrapped instantly).
