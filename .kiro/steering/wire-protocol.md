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
| 26 | 1  | hopCount | incremented per relay hop; hubs stop flooding at 8, phones stop relaying at 10 |
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
  can verify before dropping custody. No ROUTING_ACK for broadcasts or group messages (a group's
  members answer with group receipts, which are ordinary DMs — see Groups).

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
- MESH_ROSTER 0x06 (hub→phone, Oct 2026+) `[1B n][n × (4B phone id, 4B hub id)]` = phones on the
  OTHER hubs this hub hears (from their heartbeats), minus phones registered here; sent with every
  ROSTER (on change and every 30 s), also when empty. The app shows them "Online · via LoRa (hub
  XXXX)" (`meshPeers`, presence only): not online peers, no encounters, no routing-table training;
  a DM to one still goes to our hub (which floods it) and is Delivered only by the receipt.
- Hub heartbeat (LoRa, every 60–72 s; early, ≥ 10 s apart, when its phones change) = 32 B
  ROUTING_SUMMARY from HUB_ID (msg id starts with HUB_ID, hop 0) + `[0xD8][ver 1][n][n × 4B phone
  id]`. One hop: summaries are never re-flooded. Remote phones expire after 200 s unheard.
- App send rules: a DM for a roster phone goes to the hub as `HUB_LOCAL` (header dest = that
  phone, not a direct link → waits for the receipt, 30 s retry). The hub contact gets every other
  DM once per hub session, bypassing the routing strategy, unless the destination is reachable
  directly / via the roster / on the relay. Roster-only phones get no broadcasts, receipts,
  custody or routing summaries from us — the hub fans those out.
- 5 s DM fallback (app, Oct 2026, `DmFallback`): a DM still queued 5 s after this phone buffered it
  keeps trying BLE / Wi-Fi Direct and is also handed to every other link that is up — the hub
  (`HUB_LOCAL` for a roster phone, else a flood once per hub session) and the relay (destination
  present, 30 s window). A DM write over BLE that fails tries Wi-Fi Direct at once but no longer falls
  through to the hub / relay in the same call. Since fix 3 no send waits for a BLE connect (no BLE link
  up → Wi-Fi Direct at once, or nothing yet). Nothing changes on the wire or in the firmware.

## Hub Bluetooth beacon (firmware v4+, Oct 2026)
- Every hub broadcasts ONE legacy advert, **non-connectable and non-scannable** (ADV_NONCONN_IND, no
  scan response, no flags), every ~1 s (interval 1000–1100 ms, +9 dBm), NimBLE-Arduino 1.4.3 as a
  broadcaster only (no GATT, no scanning, no connections). It is rebuilt every second and
  re-advertised only when its bytes change. SoftAP + LoRa are unchanged (ESP32-S3 Wi-Fi/BLE coexistence).
- Its only AD structure is Manufacturer Specific Data (type 0xFF), company id **0xFFFF** (no SIG id),
  body = `[0x44 'D'][0x48 'H'][ver 1][4B HUB_ID BE][1B firmware][1B phones registered][1B DMs waiting]
  [1B n]` + n × `[2B other hub's id & 0xFFFF BE][1B −RSSI dBm, 0 = none][1B SNR × 4, int8]`, n ≤ 4,
  strongest first (other hubs heard directly in the last 180 s). Max 27 B body + 2 B company id →
  a 31-byte AD = exactly one advert. Counts are capped at 255. "DMs waiting" = buffered DATA bundles
  not addressed to broadcast (the OLED `msg:` count).
- Phones advertise the DTN GATT service UUID instead, so a hub is never mistaken for a phone (older
  apps filter on that UUID and never see hubs). Readers drop other versions, wrong magic, truncated
  bodies and hub ids 0 / 0xFFFFFFFF; later fields are appended without a version bump.
- App (`HubBeacons`, `HubBeaconRegistry`): the existing BLE scan has a second filter (manufacturer
  0xFFFF, data prefix "DH"); a beacon only feeds LoRa hub › Available hubs (no GATT connect, no
  encounter, no routing-table training). Hubs not heard for 30 s leave the list.
- SSID from the hub id: `DTN-HUB-` + last 4 hex digits of HUB_ID, upper case (= firmware `%04X` of
  `HUB_ID & 0xFFFF` = `hubLabel()`). **Join** asks Android for exactly that SSID
  (`WifiNetworkSpecifier.setSsid`); **auto-join** asks for the strongest beacon heard in the last 15 s
  (ties: heard last, then lowest id), skipping a hub whose Wi-Fi request just failed (60 s), and falls
  back to the `DTN-HUB-` prefix request when no beacon is heard. Right after the BLE scan starts
  (Connect starts both) it waits up to 3 s for a first beacon. A hub picked with Join stays picked
  until auto-join runs again (switch / Connect / retry) or the hub link is switched off.

## Phone ⇄ phone over BLE (primary)
GATT service UUID in the advert; scan-response service data `[4B nodeId][1B flags][4B nonce][1B channel]`
(28 B total, fits the 31 B legacy limit). Flags: bit0 owns a DTN Wi-Fi Direct group, bit1 Wi-Fi
Direct running, bits2-5 group members (0-15). Channel (Oct 2026 fix 2) = the owned group's Wi-Fi
channel (2.4 GHz 1–14, 5 GHz 32–177, 0 = unknown; `WifiDirectHints.channelOf`). Builds before it send
9 bytes (channel unknown); readers of only the first 4 or 9 bytes keep working.
GATT service `d791b000-2c8f-4f2a-9b1e-000000000001` (Oct 2026 fix 3 added the link characteristic):
- **Bundle characteristic** `d791b001-…` (write / write without response): the central writes bundles
  as `[2B len][wire]` in MTU − 3 chunks, each a write WITH response (the ATT ack is the hop-level
  confirmation).
- **Link characteristic** `d791b002-…` (indicate + write, CCCD 0x2902; fix 3): right after discovery the
  central subscribes to its indications (CCCD 0x0002), then writes **HELLO** `[ver 1][4B node id BE]`
  to it (with response). Readers drop other versions, short values and ids 0 / 0xFFFFFFFF, and ignore
  trailing bytes. Once subscribed + HELLO, that connection is the peripheral's link to that phone: it
  sends its bundles as **indications** with the same `[2B len][wire]` framing in MTU − 3 chunks
  (≤ 512 B), one in flight, each waiting for the central's ATT confirmation (`onNotificationSent`). A
  confirmed indication to the destination is a direct-link delivery, like a write with response. Only
  the central requests an MTU; the peripheral uses what the central negotiated (server `onMtuChanged`).
- **One link per phone pair, used both ways** (fix 3): when two phones see each other only the LOWER
  node id (unsigned 32-bit) connects. The higher id waits 3 s and connects itself only if no link from
  the lower id appeared (its connect failed, it is backing off, or it can't hear us). Nobody opens a
  client link to a phone that already has a link with it in either role; a bare server connection
  doesn't count (Android reports every LE link to the server), only subscribed + HELLO. If both connect
  at the same instant, the lower id's link stays and the higher id closes its own. Connects run in the
  background with a per-phone back-off of 1, 2, 4, 8, 16, then 30 s after failures in a row (reset when
  a link comes up in either role). A send uses a link that is up NOW or returns at once, and then Wi-Fi
  Direct gets the bundle if it is linked; a link coming up triggers a flush. (Before fix 3 both phones
  connected to each other, and on the Redmi ⇄ OnePlus pair the second client's MTU exchange and
  discovery never answered, so every send waited out an 8 s connect.)
- Client links (fix 1, kept): one per peer phone, reused for every bundle while it is up even when the
  peer's address changes (an older link to the same phone is closed), `TRANSPORT_LE`, one quick connect
  retry within the 8 s budget (connect + MTU + discovery + subscribe + HELLO), the peer's cached GATT
  table is dropped before each discovery (a peer that restarted its server has new handles: writes
  failed with status 1), and a bundle whose link dies mid-write goes again once the new link is up. A
  receiver drops a partial frame after a 2 s gap.
- Older builds (before fix 3) have no link characteristic: a central's link to them is write-only and
  they connect to us for their own bundles (both sides connect, as before). Every phone should run it.

## Phone ⇄ phone over Wi-Fi Direct (groups, Android 10+, no invitations)
- One phone owns a group `DIRECT-DT-<hex8>`; passphrase = first 10 bytes (hex, 20 chars) of
  SHA-256(`"DTN-wfd-v1|<hex8>|<%08x nonce>"`). Others join with `WifiP2pConfig.Builder`
  (name + pass) → no prompt on either phone. Never the legacy invite `connect`.
- Discovery: the BLE hint above (credentials derived) + DNS-SD `_dtnmesh._tcp` (instance `dtnmesh`)
  TXT `v=2`, `id=<hex8>`, and for owners `g=<name>`, `k=<pass>`, `n=<members>`, `f=<MHz>` (fix 2,
  when known). `v=1` = old build, ignored. Only phones advertising this are ever joined — never TVs /
  printers.
- Joining (app, Oct 2026 fix 2): with the owner's channel known the join is restricted to that
  frequency (`setGroupOperatingFrequency`, a one-channel scan); a join that hasn't formed in 12 s is
  cancelled (+ `removeGroup`) and retried after 2 s, on every channel if it had used one. The nonce is
  one per app run, so a re-created group keeps its password; a joiner whose owner re-created its group
  with another password, or stopped it, starts over at once. A new owner doesn't run discovery for its
  first 20 s (it would leave the group's channel while the joiner connects).
- Election (`WifiDirectGroupPolicy`): join the best visible group (most members, then lowest
  owner id); with none, the lowest node id creates (others wait 25 s, then create themselves);
  an owner with no members merges into a group with members or a lower owner; an owner alone
  for 120 s with nobody around disbands.
- TCP :9734, `[4B wireLength][wire][4B idLen][id]`; wireLength 0 = HELLO (node-id ↔ IP, heartbeat
  every 10 s, peer stale after 30 s); wireLength −2 = ROSTER `[2B len]["hex@ip,hex@ip,…"]` from the
  owner so members link to each other directly. A TCP write inside the group = delivered.

## Payload framing (inside the encryption)
- DM plaintext starts with 1 byte naming the sender's routing algorithm: 0x01 PRoPHET, 0x02 Stable
  PRoPHET, 0x03 MaxProp, 0x04 DiMaxProp, 0x05 Epidemic, 0x06 Q-learning (`AlgorithmCode`). The
  receiver strips it (shown as "Sender's algorithm" on the Message page). A first byte outside 0x01–0x06
  = an older build's message, left as is. Group / broadcast plaintext has no prefix.
- Control DMs (Oct 2026): an UNFRAGMENTED DM whose plaintext starts with a byte in **0x80–0xBF** is
  control data, never text — valid UTF-8 can't start with a continuation byte, and it is outside the
  algorithm codes. (Later fragments of a long text can start with any byte, so only `fragmentTotal = 1`
  is checked.) **0xA7 = group receipt**: `[0xA7][ver 1][channel 1..255][n 1..12][n × 16B message id]`
  (`GroupReceiptCodec`); 4 + 16·n ≤ 207 plaintext bytes = one LoRa frame after the 16 B GCM tag.
  Readers drop other versions, channel 0, n = 0 / n > 12 and short bodies, and ignore trailing bytes.
  Older builds show it as garbage text: every phone must run the Oct 2026 (Phase 8) build.

## Routing summaries (ROUTING_SUMMARY payload, 1 hop, direct links only)
- The tag is not on the wire: a payload starting `0xD1 0x01` is DiMaxProp's; anything else is read
  in the receiver's own strategy format (PRoPHET / MaxProp start with a 0x00 count byte).
- PRoPHET: `[4B count][count × (UTF id, double P)]`, the top ≤ 12 P values within 200 B. Transitivity
  is RFC 6693 eq. 3, `P(a,c) = max(P(a,c), P(a,b)·P(b,c)·β)` (Oct 2026, Phase 9; the additive form
  inflated with every 8 s exchange).
- DiMaxProp: `[0xD1][ver 1][buffer occupancy ×255][n]` then n × `[4B node id][f ×255][d_norm ×254,
  0xFF = no distance]`, the top contacts by f, followed by **path-cost entries** `[4B node id][0x00]
  [C ×64]` (Phase 9): the sender's own cheapest DiMaxProp cost to nodes further away (or cheaper than
  its direct link), cheapest first, C < 4. n ≤ 36 in all (≤ 220 B, one LoRa frame). The f byte 0 marks
  a cost entry; readers before Phase 9 already skipped f = 0 entries. Q-learning phones send it too
  (their state needs C(B,D) and the relay's buffer). Hub-roster phones never get summaries.
- MaxProp: `[4B count][count × (UTF id, double f)]` (the likeliest contacts) then, since Phase 9,
  `[4B count][count × (UTF id, double C)]` = the sender's own cheapest path cost to nodes further away,
  all within 200 B (the whole f-table used to overflow 223 B past ~11 known nodes and was then never
  sent). Readers before Phase 9 stop after the f part.
- Multi-hop: a phone's own f-table (MaxProp, DiMaxProp) holds only meetings it saw; reach beyond its
  neighbours' contacts comes from their path-cost entries (distance vectors), relaxed one summary at a
  time. Before Phase 9 a neighbour's f was "seeded" into one's own table (× 0.5), which made every node
  look one hop away at a cost of about 1 and hid the real multi-hop paths.

## Node id + announces + Discover
- Node id (decision 14, Oct 2026) = first 4 bytes (BE uint32) of SHA-256(`"DTN-node-v1|" + ANDROID_ID +
  "|" + packageName`), re-hashed with `"|1"`, `"|2"`, … while it is 0 or 0xFFFFFFFF
  (`NodeIdDerivation`). Same id after a data clear / reinstall (new keys); a factory reset changes it.
  No usable ANDROID_ID → the id stored by this install (or the old key-derived id). Older builds used
  SHA-256("DTN-node-v1" ‖ xPub ‖ edPub): every phone gets a new id once and contacts re-add once.
- ANNOUNCE payload v2: `[ver 2][flags][4B node id][32B xPub][32B edPub][4B issuedAt s][1B len][name ≤48]
  [64B Ed25519 sig over everything before it]` (≤ 187 B). Receivers require node id == bundle origin.
  v1 (no node id field; id = key hash) is still accepted.
- Key changes: an announce for a known node with other keys replaces them only if its issuedAt is
  newer than the stored keys' (stale replays ignored, not relayed). Unverified contact → switch + a
  "safety number changed" note in its chat. QR-verified contact → keys held for review ("Keys changed —
  scan their QR or Accept"); DMs to it wait (chat status HELD, not sealed) until resolved. An announce
  carrying the receiver's own keys is ignored. Blocked senders' announces are ignored (still relayed).
- Identity QR v2: `dtnid:2:<hex8 node id>:<b64 xPub>:<b64 edPub>:<name>` (v1 `dtnid:1:<xPub>:<edPub>:<name>`
  still parses, id = key hash). A QR scan pins + verifies the keys.
- Discover on: ANNOUNCE to `0xFFFFFFFF` (+ relay `bcast`) every 5 min, TTL 10 min, as before.
- Discover off (default): one ANNOUNCE addressed to each added contact (dest = its node id, TTL
  30 min, at most every 30 min, immediately on QR add / Add / name change; never to blocked contacts).
  Only the addressee pins the keys; carriers relay it like a DM. Superseded per (origin, destination).
  Dropped from the buffer once handed to its destination (direct, same hub, or relay).
- Data-clear ghosts: the pre-clear identity has the receiver's own node id, so its announces are dropped
  as echoes. Only a phone without a usable ANDROID_ID ignores announces issued before its keys existed.

## Groups
- Channel byte (1..255) of a NEW group = `1 + (SHA-256("DTN-grp-ch-v1" ‖ key)[0] mod 255)`.
  Invites carry the channel (`dtngrp:<ch>:<name>:<b64 key>`, key = text after the LAST `:`), so
  legacy sequential-channel invites still work. Joining never overwrites a different local group
  on the same channel (refused as a conflict).
- Invite DMs (Create group › Add members, Oct 2026): the invite string is sent as an ordinary DM
  (suite 1, its whole text = the invite) to each picked contact with keys; the receiver's chat shows
  a Join group card and joins only on a tap. The group name is capped at **151 UTF-8 bytes** so the
  invite always fits one DM frame: 223 − 16 (GCM tag) − 1 (algorithm byte) = 206 text bytes, minus
  55 (`dtngrp:` + 3 channel digits + 2 `:` + 43 Base64 key chars). Older builds capped names at 32
  characters and read longer ones cut to that.
- Group/broadcast DATA is dest `0xFFFFFFFF` + channel; each peer gets a bundle once per
  encounter/presence session (never echoed to whoever handed it over).
- Group receipts (Oct 2026): a member whose phone decrypts a group message for the first time (not its
  own, not from a blocked contact) tells the origin with a group receipt (above): an ordinary suite-1
  DM (dest = origin, header channel 0, DATA), batched per (origin, channel) for 3 s, ≤ 12 ids per DM,
  TTL = what the group messages had left, at least 30 min. Phones, hubs and the relay carry it like any
  DM (no firmware change); its own DM receipt (ROUTING_ACK) clears the carriers' copies. A group message
  itself **never** gets a ROUTING_ACK — hubs and carriers would delete it from their buffers before it
  reached the other members — so it keeps spreading until its TTL. The origin records member, time and
  link per message (journal route); known members = invited with Create group ∪ seen posting ∪ sent a
  receipt, minus this phone and blocked contacts. A member without the origin's keys can't send one.
  The public broadcast (channel 0, not encrypted) has no receipts.

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
  Besides relay-only destinations, the 5 s fallback publishes a DM whose radio path hasn't delivered
  it yet (at most every 30 s); a carried DM goes to its own destination only (still no custody), and
  one for a phone that isn't a contact on the carrier is published by the fallback only.
- Default broker `ssl://broker.hivemq.com:8883` (TLS). The client resolves DNS itself and tries
  **every IPv4 address before any IPv6** (many ISPs hand out black-holed IPv6). For the public
  HiveMQ host it rotates TLS 8883 → TCP 1883 → WSS 8884 (`/mqtt`) on each failed attempt; a custom
  broker URL is used as given (`RelayEndpoints`). Publishes never run on the main thread.
- Reconnects (app, Oct 2026 fix 1, `RelayLinkMachine`; nothing changes on the wire): no HiveMQ automatic
  reconnect. Each attempt is a fresh client (same client id) with a 27 s budget; after a failure it waits
  2, 4, 8, 15, 30, then 60 s and takes the next endpoint, and stops after 10 in a row (Retry or a network
  change resumes). A session that worked and dropped is retried on the same endpoint after 1 s; two
  presence publishes in a row without a PUBACK end a half-open session. Without a validated default
  network nothing is tried; when one appears (or the default network changes) it starts over at once
  with fresh DNS.

## Firmware (Heltec V3) invariants
- Radio: region config (IN865 866.3 / EU868 869.525 / US915 906.875), SF9, BW125 (BW500 US),
  22 dBm (region-capped), 140 mA OCP, 1.8 V TCXO (+XTAL fallback), boosted RX, preamble 16,
  sync word 0x2B, DIO2 as RF switch. Pins NSS8 DIO1-14 RST12 BUSY13 SCK9 MISO11 MOSI10.
- Hub routes on the header only; it must NOT need to decrypt. It ages TTL by buffer residence,
  enforces a rolling-hour duty-cycle budget, does CAD + jitter before TX (never while a frame is
  arriving: PREAMBLE_DETECTED / HEADER_VALID latched, since CAD aborts a receive), and replays buffered
  bundles to a phone on REGISTER. HUB_ID = eFuse MAC bytes 2..5, SSID suffix = bytes 4..5 (the low
  bytes of `getEfuseMac()` are the shared Espressif OUI — using them gave every board `DTN-HUB-B734`).
  Only a directly heard hub heartbeat (32 B, hop 0, msg id = origin) updates the LoRa peer table.
- v3 (`DTN_FW_VERSION=3`): writes to phones go through a per-client non-blocking TX queue
  (4 KB, whole frames only, `send(MSG_DONTWAIT)`). Never gate on `WiFiClient::availableForWrite()`:
  arduino-esp32 2.0.x always returns 0, which made v2 hubs silently send nothing to any phone.
- v3 routing: ROUTING_ACK goes to every local phone except the sender (and floods); broadcasts
  skip the sender; a REGISTER retires that node's stale connection; a bundle a phone re-sends
  that the hub already holds is re-delivered locally (DM) or ignored (broadcast / ack), never
  re-flooded (v5: nor one whose receipt it already saw). Idle checks use signed
  `(int32_t)(millis() - lastSeen)` (unsigned wrapped instantly).
- Hop ceiling: hubs stop flooding at hop 8 (`HOP_LIMIT`); phones stop relaying at hop 10 (the
  strategies' `maxHops`), and a phone always hands a bundle to its destination, whatever its hop count.
- v4 (`DTN_FW_VERSION=4`) = v3 + the Bluetooth beacon above. Nothing else on the LoRa or
  TCP side changed, so v3 and v4 hubs interoperate; only v4+ hubs appear under Available hubs.
- v5 (`DTN_FW_VERSION=5`, current, Oct 2026) = v4 + a DM whose ROUTING_ACK already passed through the
  hub (its id is in the 256-entry receipt ring) is dropped when it arrives again, from a phone or over
  LoRa, instead of being buffered and flooded again (a phone that missed the receipt re-sent it after
  re-registering, and the copy then waited for a receipt that never came). Serial `[LC] AckedResend`.
  Nothing else changed: v3–v5 hubs interoperate; the beacon reports firmware 5.
