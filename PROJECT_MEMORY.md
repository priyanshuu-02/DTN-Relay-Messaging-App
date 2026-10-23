# DTN Mesh Relay — Project Memory & Pivot Context

> Living handoff document. Two parts:
> **Part A** = the target architecture we are building toward (the "why").
> **Part B** = exactly what has changed so far and what still remains (the "what/where/next").

---

# PART A — Target Architecture

## A.1 The core idea

A **Delay-Tolerant Network (DTN)** messaging system built from three transports, where a **single LoRa
device is a WiFi Access-Point hub** that many phones connect to at once. Long-range links are handled by a
**LoRa hub-to-hub backbone**; short-range links are **phone-to-phone (BLE / WiFi Direct)**; and messages
survive the lack of an end-to-end path via **store-carry-forward** and **data-mule** phones. The system is
**independent of Meshtastic** and runs **custom firmware** on the LoRa modules.

The old model (each phone owns one LoRa radio, driven through the Meshtastic app over AIDL) is abandoned.

## A.2 Topology

```
   REGION A (Hub A = ESP32 SoftAP)              REGION B (Hub B = ESP32 SoftAP)
   SSID "DTN-HUB-A", 192.168.4.1                SSID "DTN-HUB-B", 192.168.4.1
        ^   ^   ^  (phones join over WiFi,             ^   ^   ^
        |   |   |   framed TCP :9740)                   |   |   |
      A1  A2  A3                                       B1  B2  B3
        \__ BLE / WiFi-Direct __/                        \__ BLE / WiFi-Direct __/
                     |                                          ^
                     \___ phone A2 physically carries __________/  (data mule)

        Hub A  <========== long-range LoRa RF (SX1262) ==========>  Hub B
                controlled flooding + msg-ID dedup + TTL + hop-limit
```

## A.3 Requirements this architecture must satisfy

1. A single LoRa device connects to **multiple phones simultaneously over WiFi**.
2. All phones can talk **directly peer-to-peer** over WiFi or BLE.
3. Phones exchange messages directly; when a LoRa node comes in range, it **synchronizes all pending
   messages** from connected phones and forwards them across the **LoRa-to-LoRa** network.
4. A phone can receive & temporarily store messages, or forward immediately to a nearby phone. Phones on the
   same LoRa node sync pending messages to it; later, in another node's coverage, they sync there too —
   letting messages propagate through the LoRa network.
5. Even with **no LoRa node available**, phones still sync with each other over WiFi/BLE. Long-range =
   LoRa backbone; nearby = WiFi/BLE.
6. A third phone acts as a **data mule** (mobile store-carry-forward): A→C buffered while B is unreachable;
   C carries it; when B later meets C (via LoRa mesh or direct P2P), C delivers it. Reliable delivery
   without a contemporaneous end-to-end path.

## A.4 Roles & division of labor

- **Phone = the brain.** Creates messages, runs the store-carry-forward buffer, and does the *adaptive*
  encounter-based routing (PRoPHET / MaxProp / Double Q-Learning). Connects opportunistically to a hub
  (WiFi) and to other phones (BLE primary, WiFi Direct fallback).
- **LoRa hub = the bridge + backbone.** ESP32 + SX1262. Runs a WiFi SoftAP + framed TCP server for phones,
  keeps its own bundle buffer, and forwards hub-to-hub with **simple controlled flooding** (message-id
  dedup + TTL + hop-limit). Deliberately *not* smart — the intelligence lives on the phones.

## A.5 Key design decisions

- **BLE is the primary phone-to-phone transport** (WiFi Direct is fallback). While a phone is joined to a
  hub SoftAP as a WiFi STA, concurrent WiFi Direct is unreliable on many devices; BLE coexists cleanly.
- **Self-contained bundle format.** Because the hub-to-hub LoRa link has no outer envelope to carry
  addressing, origin + destination node ids live **inside** the DTN header (see B.1).
- **One stable identity per phone** shared across all transports (`LocalNodeIdentity`).
- **Broadcast** uses a reserved wire address `0xFFFFFFFF` so the hub can fan a bundle out to every phone.

## A.6 Self-contained wire header (32 bytes, big-endian)

| Offset | Size | Field | Notes |
|---|---|---|---|
| 0  | 16 | messageId (UUID) | dedup key on hubs |
| 16 | 4  | originNodeId (uint32) | `NodeId.toNodeNum32()` |
| 20 | 4  | destinationNodeId (uint32) | `0xFFFFFFFF` = broadcast |
| 24 | 2  | ttlMinutes (uint16) | counts down |
| 26 | 1  | hopCount | incremented per LoRa re-flood |
| 27 | 1  | messageType | `DtnMessageType.wireValue` |
| 28 | 1  | channel | 0 = primary |
| 29 | 1  | fragmentIndex | 0 = single frame |
| 30 | 1  | fragmentTotal | 1 = no fragmentation |
| 31 | 1  | reserved | 0x00 |

Usable single-frame payload over LoRa (SX126x, 255 B max): **223 bytes**. Keep ≲ 200 for airtime.

## A.7 Phone ⇄ Hub protocol (framed TCP, port 9740)

Frame = `[1B type][2B length BE][payload]`
- `HELLO` (0x03): hub → phone, 4B hub id, sent on connect (phone then treats the hub as a peer).
- `REGISTER` (0x01): phone → hub, 4B phone id (hub routes bundles back to this phone).
- `BUNDLE` (0x02): either direction, a self-contained DTN bundle.

## A.8 Phone ⇄ Phone protocols

- **BLE:** each phone runs a GATT server (advertises the DTN service UUID + its 4B id as service data)
  and scans/connects as client. Bundles are `[2B totalLen][wire]` chunked to the negotiated MTU.
- **WiFi Direct:** framed TCP on port 9734. `[4B wireLen][wire][4B idLen][id]`; `wireLen == 0` is a
  **HELLO** (identity-only) used to learn each peer's real id ↔ IP.

---

# PART B — Changes Made So Far & What Remains

Repo: `D:\PROJECTS\DTN CN CAPSTONE` · Android module `com.dtn.mesh` ("DtnMeshRelay").
`docs/meshtastic-ref/` is a read-only Meshtastic clone kept only as AIDL reference.

**Build/test status:** `:app:assembleDebug` builds successfully and the debug APK installs/runs on device.
Unit tests pass — DoubleQLearningEngine 13/13, DtnWireCodec 9/9, ProphetStrategy 15/15, AirtimeBudgetTracker
5/5, RadioDistanceEstimator 5/5 (the ProphetStrategy suite was rewritten to the RFC-6693 math in B.9; the
research-grounded routing + concurrency work is B.9–B.12).

## B.1 DONE — Self-contained wire header

- **`model/NodeId.kt`** — added `toNodeNum32()` (exact for `!hex`, FNV-1a hash otherwise), reserved
  `BROADCAST_NODE_NUM32 = 0xFFFFFFFF`; `fromNodeNum()` maps that back to `BROADCAST`; `toNodeNum32()`
  special-cases broadcast.
- **`model/DtnMessage.kt`** — header 24 → **32 bytes**; de-Meshtastic'd constants: `LORA_MAX_FRAME_BYTES=255`,
  `DTN_HEADER_BYTES=32`, `MAX_SINGLE_PACKET_PAYLOAD=223`. (`PORT_NUM_PRIVATE_APP` kept as a harmless legacy
  field.)
- **`receiver/DtnWireCodec.kt`** — rewritten. `encode()` writes origin+dest; `decode(wireBytes, rssi, snr)`
  reads them from the header (old `fromNodeId`/`destinationNodeId` params removed). Removed
  `estimateCreationTime()`.
- **`test/.../DtnWireCodecTest.kt`** — updated to the new signature; added origin/dest and non-hex-id
  round-trip tests.

## B.2 DONE — New transports

- **`model/LocalNodeIdentity.kt`** (NEW) — one stable `!hex` node id per install (derived from ANDROID_ID /
  build fingerprint), injected into all transports.
- **`receiver/LoRaHubTransportAdapter.kt`** (NEW) — phone joins hub SoftAP via `WifiNetworkSpecifier`
  (SSID prefix `DTN-HUB-`, bound socket keeps cellular default); framed TCP :9740 with HELLO/REGISTER/BUNDLE;
  treats the hub as an encountered peer so the existing orchestrator forwards buffered bundles to it.
- **`receiver/BleTransportAdapter.kt`** (NEW) — symmetric GATT server + advertiser + scanner + client;
  identity in advertisement service data; `[2B len][wire]` chunked to MTU. Primary P2P path.

## B.3 DONE — Transport layer rewired, Meshtastic retired

- **`receiver/MultiTransportManager.kt`** — now aggregates **BLE + WiFi Direct + LoRa hub**; send priority
  **BLE → WiFi Direct → LoRa hub**; helper methods `connectHub()`, `connectBle()`, `connectWifiDirect()`.
- **`receiver/WifiDirectTransportAdapter.kt`** — injects `LocalNodeIdentity` (single shared identity);
  removed the `!p2p_<name>` fabricated ids everywhere; added the **HELLO handshake** so peers are keyed by
  their **real** node id ↔ IP; removed unused `Build` import.
- **`ui/DtnViewModel.kt`** — `connect()` text updated; methods renamed/added: `connectHubOnly()`,
  `connectBleOnly()`, `connectWifiDirectOnly()`.
- **`receiver/MeshtasticTransportAdapter.kt`** — reduced to a retirement note (no code).
- **`AndroidManifest.xml`** — removed the Meshtastic `<queries>`; added `CHANGE_NETWORK_STATE`, BLE
  permissions (`BLUETOOTH_ADVERTISE/SCAN/CONNECT` + legacy), and `uses-feature bluetooth_le`.
- `ui/MainScreen.kt` — at the time of B.3 this only used `connect()`. It has since grown into the full
  analytics UI described in **B.8** (chats / network / lifecycle / log tabs, strategy switch, route detail,
  routing-math breakdown).

## B.4 DONE — Hub firmware

- **`firmware/platformio.ini`**, **`firmware/src/main.cpp`**, **`firmware/README.md`** (NEW) — Heltec WiFi
  LoRa 32 V3 (ESP32-S3 + SX1262), RadioLib. SoftAP + framed TCP + LoRa flood-with-dedup/TTL/hop-limit;
  sends HELLO on connect; **broadcast fan-out** to all connected phones for dest `0xFFFFFFFF`.

## B.5 DONE — Follow-ups

- Broadcast delivery end-to-end: reserved `0xFFFFFFFF` in `NodeId` + firmware `deliverLocal`/`routeBundle`
  fan-out and always-reflood for broadcasts.
- WiFi Direct real-identity handshake (see B.3).

## B.6 Design doc

- **`docs/dtn-hub-architecture.md`** — the original architecture proposal. NOTE: its firmware snippet
  predates the `HELLO` frame and the broadcast fan-out; the authoritative firmware is in `firmware/`.

## B.7 DONE — Foreground service, permissions, end-to-end receipts, routing & BLE fixes

Later sessions closed several Part C items and fixed real multi-hop / device bugs found on hardware.

- **Foreground service (was C.3).** `service/DtnForegroundService.kt` exists and is registered in the
  manifest with `foregroundServiceType="connectedDevice"` (+ `FOREGROUND_SERVICE` /
  `FOREGROUND_SERVICE_CONNECTED_DEVICE` permissions). It hosts the orchestrator and is started from
  `DtnViewModel.connect()`. `connect()` also calls `orchestrator.start()` directly (idempotent) so encounter
  → `contacts` persistence runs even if the service's async start is delayed — this fixed the "peers show in
  the raw NODE log but never appear in the chat/peer list" bug.
- **Runtime permissions (was C.3).** `MainActivity` requests BLE / location / nearby-devices at launch via
  `registerForActivityResult(RequestMultiplePermissions)`.
- **End-to-end delivery receipts (was C.3).** Hop-level transport status is now explicitly treated as
  informational only and NEVER clears the buffer. Real end-to-end confirmation uses `ROUTING_ACK` receipt
  bundles: the destination records delivery, receipts propagate back through mules, and a carrier clears its
  custody copy only on a receipt (`BUFFER_CLEARED`) / direct delivery / expiry.
- **Dead Meshtastic files (was C.1).** `app/src/main/aidl/org/meshtastic/**` and
  `app/src/main/kotlin/org/meshtastic/**` are deleted; `MeshtasticTransportAdapter` is gone; `build.gradle.kts`
  no longer enables the `aidl` build feature.
- **PROPHET multi-hop fixes** (`routing/ProphetStrategy.kt`, `ProphetConfig`):
  - `findNextHopToward` edge threshold moved off the hard-coded `0.5` to a configurable `pFloorPath` (0.1)
    so transitive predictability (~0.14 for a solid 2-hop link) forms an edge.
  - Stage-2 zero-gradient veto fixed: when neither this node nor the peer has any belief for the destination
    (`P ≈ 0` both sides) a small `relayBaseFloor` (0.02) exploratory relay is allowed instead of a hard
    `null`, so bundles no longer dead-end in sparse test nets.
  - NOTE (still true): the path finder is bounded to a 2-hop horizon because we only cache DIRECT peers'
    P-vectors. Genuine deeper routing needs 2nd-degree summary propagation (distance-vector) — see C.3.
- **Reverse-echo fix** (`DtnOrchestrator`, `BleTransportAdapter`, `InboundPacket`): the wire header only
  carries the ORIGIN, so an A→B→C→D chain had C echo the bundle straight back to B. `InboundPacket.viaPeer`
  now carries the immediate previous hop and the orchestrator refuses to send a bundle back to it.
- **BLE "online from inbound traffic" fix.** Peers were only marked online from `scanCallback` (needs the
  scan-response service data). On radios that suspend active scanning during a GATT link (Qualcomm/OnePlus),
  that data never arrives, so a peer stayed "offline" while actively exchanging data. `accumulateInbound` now
  learns the address→node mapping from handshake bundles (`ROUTING_SUMMARY`/`ROUTING_ACK`, whose origin IS
  the neighbour) and emits an online encounter for any inbound write via a shared debounced
  `emitOnlineEncounter`. This also lets the phone send back (bidirectional). WiFi Direct and the LoRa hub
  already marked peers online from inbound traffic.
- **Lifecycle-log thread safety.** `MessageLifecycleLog` route/entry updates were an unsynchronized
  read-modify-write on a `StateFlow`; concurrent RX/TX for the same message could insert two records with the
  same id and crash the Compose `LazyColumn` ("Key … already used"). The mutations are now `synchronized`,
  and the UI lists use `distinctBy`/indexed keys as defence in depth.

## B.8 DONE — Analytics UI (`ui/MainScreen.kt`, `ui/DtnViewModel.kt`)

`MainScreen` is now the full app UI, not just a Connect button:

- **Tabs:** Chats (broadcast + per-peer conversations), Network, Lifecycle, Log.
- **Network tab:** connection/overview summary (active peers, buffer, DELIVERED vs RECEIVED split), a
  **routing protocol switch** (PROPHET / MaxProp, backed by `DtnViewModel.setStrategy`), peer rows with
  signal bars + last-active, local route observations (tap a route for a detail dialog), and the live buffer.
- **Route detail dialog:** message content, node-id → nickname mapping, prev/this/next hop (local node shown
  as **YOU**), timestamps, the routing algorithm used (`RouteRecord.strategy`), and an expandable
  **routing-math breakdown** (`DtnViewModel.explainRoute`) with per-carrier scores and per-strategy formulas
  for both PROPHET and MaxProp.
- **Lifecycle tab:** a buffer-history section (how each buffered message left the buffer) plus a filterable,
  expandable event log. New `LifecycleEvent.DROPPED` covers housekeeping drops; `EXPIRED`/`DROPPED` are now
  actually emitted from `ForwardingWorker`.

## B.9 DONE — PRoPHET brought to RFC 6693 (fixes the P-saturation instability)

The classic variant added an `ε^d` distance term on every encounter, which let a single close ping drive
`P → 1.0` and strand messages in Stage-2 (a peer at `P = 1.0` looks unbeatable forever). Replaced with the
**RFC 6693 / PRoPHETv2** formulation (`routing/ProphetStrategy.kt`, `ProphetConfig`). Citations:
Lindgren, Doria, Davies, Grasic — *Probabilistic Routing Protocol for Intermittently Connected Networks*,
**RFC 6693** (IRTF DTNRG, 2014); original Lindgren et al., *SIGMOBILE MC2R* 7(3), 2003.

- **Delta-capped encounter update.** `P(a,b) = P_old + (1 − δ − P_old) · P_enc`, so predictability
  asymptotes to `1 − δ` and can never reach exactly 1.0. `ProphetConfig.pDelta = 0.01` (cap `0.99`);
  `pEncounter` (`P_init`) `= 0.75`.
- **Adaptive encounter weight (`adaptiveEncounter`).** First contact or a gap `≥ I_typ` uses the full
  `P_enc`; a re-encounter faster than `I_typ` is scaled by `interval / I_typ`, so a BLE peer that stays in
  range and re-triggers every second stops over-inflating `P`. `typicalEncounterIntervalMs (I_typ) = 30 000`;
  set to `0` to disable (the unit tests use `0` for deterministic assertions).
- **Aging** unchanged in spirit: `P · γ^k` over `k` elapsed `agingIntervalMs` windows; `γ = 0.98`.
- **Transitivity** unchanged: `P(a,c) = P(a,c) + (1 − P(a,c)) · P(a,b) · P(b,c) · β`; `β = 0.25`.
- **Removed:** `epsilonDistance` and `distanceBoost()` / the `ε^d` additive term (root cause of the
  saturation). The distance-aware idea now lives only in the opt-in **STABLE-PROPHET** variant
  (`stabilityAware = true`), which multiplies `P_enc` by a smoothed-RSSI + contact-recurrence link-quality
  weight instead of adding an unbounded term.
- **Tests rewritten** (`test/.../ProphetStrategyTest.kt`, 15 tests) to assert the RFC behaviour: delta cap,
  never-reaches-1.0, adaptive damping of rapid re-encounters, stability-aware non-saturation, plus the
  existing aging/transitivity/stage-1-2/hop-limit/broadcast/export cases.
- **Orchestrator ENCOUNTER console log** updated — no more `ε` reference; it now prints the δ cap and the
  adaptive `I_typ` (or "link-quality modulated" in stability-aware mode).

## B.10 DONE — MaxProp upgraded to true cost-based (Dijkstra) routing

The old MaxProp was a single-hop proxy (rank by the peer's own `f`). Replaced with the graph cost-routing
from Burgess, Gallagher, Jensen, Levine — *MaxProp: Routing for Vehicle-Based Disruption-Tolerant Networks*,
**IEEE INFOCOM 2006** (`routing/MaxPropStrategy.kt`).

- **Delivery-likelihood edges.** Each node's `f`-vector (encounters(peer) / Σ encounters, normalised to 1)
  is the per-edge delivery likelihood. We cache neighbours' vectors in `peerFTables` (populated from the
  `ROUTING_SUMMARY` handshake), giving a small multi-hop cost graph.
- **`findMinCostNextHop(dest, onlinePeers)`** runs **Dijkstra** over edge cost `(1 − f)` from a synthetic
  source node `ME = "__self__"`. A directly-online destination short-circuits to cost `1 − f(dest)`.
  Returns `(nextHop, totalPathCost)`; unreachable destinations yield `Double.MAX_VALUE`.
- **`rankForForwardingWithTopology(candidates, peerId, onlinePeers)`** — forwards to a peer only when it is
  the **min-cost next hop** toward the destination, with priority falling as path cost rises. Direct
  delivery = priority 1.0; broadcast = 0.9; a hard `maxHops = 10` guard drops runaway bundles.
- **Head-start for new packets** (Burgess §III-C): bundles at `hopCount ≤ newPacketHopThreshold (1)` get a
  `headStartBoost (1.5)` priority multiplier so freshly-injected messages spread before older ones.
- **Orchestrator** (`DtnOrchestrator.sendToSingle`) has a MaxProp branch that calls
  `rankForForwardingWithTopology(...)` for the relay-approval set (direct delivery + broadcast still bypass
  any strategy veto).

## B.11 DONE — Multi-phone robustness + latency (concurrent send path)

Audit finding: the orchestrator ran a **single serial event loop** that *awaited* blocking BLE sends
(`ensureReady` ~8 s connect, ~2 s/chunk). One slow or unreachable peer stalled the entire mesh for tens of
seconds, and the 4 s periodic flush re-sent the full receipt set to every peer each tick. Reworked the send
path in `service/DtnOrchestrator.kt`. The design borrows *flood-with-suppression* / dedup ideas from
Meshtastic managed flooding and Briar/Bramble pairwise sync (offer only what the peer lacks).

- **Coalesced flush.** `flushToOnlinePeers()` takes `flushMutex.tryLock()`; if a flush is already running it
  just sets `flushPending = true` and returns, and the running flush re-runs one final pass. Bursty triggers
  (RX + encounter + periodic tick) collapse into a single follow-up instead of stacking up.
- **Parallel per-peer fan-out.** `flushOnce()` launches each peer in a `coroutineScope`, bounded by
  `sendSemaphore = Semaphore(MAX_CONCURRENT_SENDS = 4)`, so a slow peer no longer blocks the others. Each
  peer's writes are serialised by a **per-peer `Mutex` (`peerLock`)** so concurrent flushes can never
  interleave writes to the same GATT link (the chunk-ACK path assumes one outstanding write per address).
- **Connect backoff.** A failed send marks `connectBackoffUntil[peer] = now + CONNECT_BACKOFF_MS (10 s)`;
  backed-off peers are skipped so we don't pay the ~8 s connect timeout again every flush. Any successful
  send clears it, and a fresh online encounter clears it immediately.
- **Receipt dedup + short-circuit.** `sendDeliveryReceipts` tracks per-peer already-sent UIDs
  (`receiptsSentTo`) and sends only *new* receipts; if a bundle send fails it stops and keeps the rest for
  next time. This kills the per-tick receipt storm that grew with delivery count.
- **Broadcast fan-out correctness.** A broadcast is cleared from the buffer only after it has been confirmed
  sent to **every** currently-online peer (`broadcastsSentTo.containsAll(onlineIds)`), fixing a gap where a
  mid-loop failure could clear a broadcast a peer never received.
- **Event loop stays hot.** All flush triggers (`FlushBuffer`, the 3 RX paths, encounter) now call the
  non-blocking `requestFlush()` (`scope.launch { flushToOnlinePeers() }`); the per-encounter routing summary
  is launched off-loop under the peer's lock. The external `triggerFlush()` API (ViewModel) is unchanged.
- Offline handling clears `connectBackoffUntil` / `receiptsSentTo` / `peerLocks` for the departed peer.

## B.12 DONE — UI visual polish (no logic/log/math changes)

Purely presentational refinements to `ui/MainScreen.kt`; all log text, formulas, data bindings, and control
flow are byte-for-byte unchanged (verified by compile + the full unit suite).

- Enriched the `Palette`: added `PrimaryLight`, soft status tints (`AccentSoft`/`WarningSoft`/`ErrorSoft`/
  `PrimarySoft`/`BroadcastSoft`), a `TopBarGradient` (blue horizontal gradient) and a `BroadcastGradient`;
  softened the app background.
- Gradient app bars on both the home screen and the chat-detail header.
- `ConnectionCard`: the status dot now sits in a soft-tinted badge; slightly stronger elevation + rounder
  corners. Same status text and `Node · strategy · Buffer` line.
- `BroadcastCard` / `PeerCard`: elevation `1→2 dp`, radius `14→16 dp`; broadcast avatar uses the gradient.
- `NetworkSummaryCard`: now a gradient "hero" card. `StrategyChip`: unselected chips get a hairline border.
- `LogTab` / `LifecycleTab` left visually as-is to preserve the log color-coding and readability (the
  functional research-export control later added to `LogTab` is **B.13.2**, not a visual change).

## B.13 DONE — Background-worker DI crash, research-export UI, routing-math display fix

A correctness pass turned up one critical background crash, one fully-built-but-unreachable feature, and one
stale on-screen formula. All three fixed; `:app:compileDebugKotlin` is clean, 47/47 unit tests pass, and
`:app:assembleDebug` produces the debug APK.

### B.13.1 CRITICAL — `ForwardingWorker` could not be constructed in the background (Hilt × WorkManager)

- **Root cause.** `scheduler/ForwardingWorker.kt` is a `@HiltWorker` with an `@AssistedInject` constructor
  (injects `MessageQueueManager`, `StrategySelector`, `ForwardingDecisionDao`, `DtnOrchestrator`,
  `MessageLifecycleLog` alongside the assisted `Context` / `WorkerParameters`). But `DtnMeshApplication` did
  **not** implement `Configuration.Provider` / inject `HiltWorkerFactory`, and `AndroidManifest.xml` did not
  remove WorkManager's default `WorkManagerInitializer`. So the default worker factory tried to reflect on a
  `(Context, WorkerParameters)` constructor that doesn't exist and threw
  `NoSuchMethodException: com.dtn.mesh.scheduler.ForwardingWorker.<init> [Context, WorkerParameters]` the
  moment the periodic cycle fired. The worker **is** scheduled (`DtnForegroundService` →
  `WorkManagerSetup.enqueueForwardingWorker`, a 15-min `PeriodicWorkRequest`), so all background housekeeping
  — TTL expiry, forward-timeout revert, old-message purge, routing aging, Q-update draining, buffer-pressure
  enforcement, and P-threshold drops — silently failed once the app was backgrounded or the screen was off.
- **Fix.**
  - `DtnMeshApplication` now implements `androidx.work.Configuration.Provider`, `@Inject`s a
    `HiltWorkerFactory`, and exposes `override val workManagerConfiguration` (the **property** form — correct
    for WorkManager 2.10.0, where `getWorkManagerConfiguration()` is deprecated).
  - `AndroidManifest.xml` adds the standard removal block: a `<provider>` for
    `androidx.startup.InitializationProvider` (`tools:node="merge"`) whose `WorkManagerInitializer` meta-data
    is `tools:node="remove"`d, so WorkManager uses our Hilt-aware on-demand configuration instead of the
    default initializer.
  - No Gradle change needed — `androidx.hilt:hilt-work` (+ its `hilt-compiler` kapt) and `work-runtime-ktx`
    were already declared.
- **Note.** The original report's dependency list (`DtnRepository`/`BundleStore`/`ForwardingEngine`/
  `BleTransportAdapter`) was inaccurate; the diagnosis was right but the actual injected types are the five
  listed above.

### B.13.2 Research-data export was implemented but had no UI entry point

- **Root cause.** `export/ResearchExporter.kt`, `export/FileExportHelper.kt`, and
  `DtnViewModel.exportData()` (+ the `exportPath` StateFlow) were complete, but nothing in
  `ui/MainScreen.kt` ever called `exportData()` — no button, icon, or menu — so the archive could never be
  produced from the app.
- **Fix.** Added a `ResearchExportCard` at the top of the **Log tab**: it collects `viewModel.exportPath`
  and shows an "Export telemetry" button wired to `viewModel.exportData()`, then displays the written path.
  The archive is `encounters.csv`, `contacts.csv`, `decisions.csv`, `messages.csv`, `q_tables.json`,
  `metadata.json`.

### B.13.3 Stale PRoPHET formula in the route-explanation card

- **Root cause.** `DtnViewModel.explainRoute()` still displayed the pre-RFC-6693 direct-encounter formula
  `P + (1−P)·P_init + ε^d`. The `ε^d` term was removed from the engine in **B.9**, so the on-screen math no
  longer matched the implementation.
- **Fix.** Updated the displayed formula to the delta-capped RFC form `P + (1−δ−P)·P_enc` (and the
  stability-aware `·q` link-quality variant when `stabilityAware = true`); the constants line now also shows
  `δ` and `I_typ`.
- **Verified NOT a bug.** The reported "Epidemic route card does nothing on tap" does **not** reproduce.
  `explainRoute()` has a full `EpidemicStrategy` branch that returns a non-null explanation; the
  routing-math card renders only `if (explanation != null)` and expands **inline** (it is not a dialog). The
  `else -> null` branch applies to Q-Learning, which then correctly hides the card (no dead header).

## B.14 DONE — Restored routing-math visibility for delivered/relayed messages

- **Symptom.** The message-detail dialog's expandable routing-math breakdown (path map, stat tiles,
  candidate carriers, formulas) had disappeared for most messages.
- **Root cause.** An earlier gate in `NetworkTab` was `if (!isForUs && !route.delivered)`. The
  `!route.delivered` half hid the breakdown for any message that had reached its destination — which, in a
  small test net, is nearly everything the user sends/relays (delivery is fast). The committed baseline
  showed the math for every route (`explanation = fullDest?.let { explainRoute(it) }`); the gate was added
  when addressing the "why is there a path map for a RECEIVED message?" request, but it over-reached.
- **Fix.** Gate relaxed to `if (!isForUs)` — the breakdown now shows for every message we route onward
  (in-transit *or* delivered/relayed, the delivered case shown retrospectively), and is hidden ONLY for
  messages addressed to this node (received), honouring the original request. `RoutingMathSection`'s
  subtitle switches to "Retrospective — how the path … was scored" when `route.delivered`.
- Default strategy is `StrategyType.PROPHET`, so the breakdown is populated out of the box; only Q-Learning
  and broadcast/blank destinations legitimately yield no breakdown.

## B.15 DONE — Network-tab crash (duplicate LazyColumn key), buffered-message details, received-message logic

Three linked Network-tab issues. Compiles clean, 47/47 unit tests pass, debug APK rebuilt.

### B.15.1 CRITICAL — Network tab crashed on open (duplicate `LazyColumn` key)

- **Root cause (latent, pre-existing — present in committed HEAD, not introduced this session).** The
  Network tab is one `LazyColumn` with both `items(routes, key = { it.msgId })` and
  `items(buffered, key = { it.msgId })`. Both keys are the **same 8-char prefix** —
  `RouteRecord.msgId = msgId.take(8)` and `BufferedMsgInfo.msgId = e.id.take(8)`. Any message that is
  simultaneously buffered **and** has a route observation (the normal case for anything you send that
  hasn't been delivered yet) produced the *same key in two different `items` blocks of one list* →
  Compose throws `IllegalArgumentException: Key "xxxxxxxx" was already used` the instant the tab composes.
- **Fix.** Namespaced the keys per section — `"peer-${nodeId}"`, `"route-${msgId}"`, `"buf-${msgId}"` — so
  the route and buffer sections can never collide. (Keys must be globally unique within a single
  `LazyColumn`, not just within one `items` block.)

### B.15.2 Buffered-message rows now open a detail view

- **Symptom.** Tapping a buffered message in the Network tab did nothing.
- **Fix.** `BufferRow` is now `clickable`; a `selectedBuffer` state drives a new `BufferDetailDialog`
  showing From/To/Status, TTL-left / time-in-buffer / hops / forwards tiles, a "why it's here"
  (store-carry-forward) explanation, and — because a buffered message is precisely one **awaiting an
  onward forwarding decision** — the routing-math breakdown (candidate carriers / best next hop toward its
  destination). The full destination id is resolved from the peer list (the row only carries the 8-char
  suffix); broadcast/unknown destinations show the fan-out note instead.

### B.15.3 Sensible routing logic for received (addressed-to-us) messages

- **Clarification from the user:** they never asked to *hide* routing info for received messages — they
  said the info shown (a forward-candidate "best next hop" map) *didn't make sense* for a message that had
  already arrived at its destination.
- **Fix.** `RouteDetailDialog` now branches: a message whose destination is this node shows a
  `ReceivedDeliverySection` (ORIGIN + ARRIVED-VIA tiles, the observed delivery path, and the delivering
  strategy — i.e. *how it reached us*, with an explicit "terminated here, no next-hop decision" note);
  messages we route onward still show the forward-scoring `RoutingMathSection`; anything else shows a short
  explanatory note. `RoutingMathSection` was refactored from taking a `RouteRecord` to explicit params
  (`exp`, `destLabel`, `retrospective`, `prevHop`, `nextHop`) so both the route dialog and the new buffer
  dialog reuse it without duplication.

## B.16 DONE — Deep audit fixes: buffer lifecycle, routing correctness, Q-learning, signal metrics

A 12-item audit turned up real contradictions/dead-ends. Fixed in five groups; compiles clean, 47/47 unit
tests pass, debug APK rebuilt. Each was verified against this checkout before editing.

### B.16.1 Buffer / message lifecycle
- **2-minute message drop (blocker).** `ForwardingWorker` Phase 7 dropped any buffered message whose
  destination `P < pMinThreshold` after a 120 s grace. In a DTN, `P = 0` for a not-yet-encountered
  destination, so store-carry-forward bundles (and anything a mule carried) were deleted after ~2 min
  despite a 4 h TTL. **Phase 7 removed entirely** — lifetime is now governed only by TTL (Phase 1) and
  buffer-pressure eviction (Phase 6).
- **Clear/expiry UI desync.** `clearBuffer()` / `expireMessages()` / `dropMessages()` changed DB status
  but notified no one, so chat bubbles stayed "buffered"/"sending…". Added
  `MessageQueueManager.terminalEvents: SharedFlow<TerminalEvent(msgId, reason)>` (reasons
  `expired`/`dropped`/`cleared`), emitted from those three methods (new `MessageDao.getActiveMessageIds` /
  `getExpiringMessageIds` capture the affected ids before the bulk UPDATE). `DtnViewModel` flips the bubble
  to expired/dropped (never overriding "✓ delivered") and logs a DROPPED lifecycle event for a manual
  Clear; `DtnOrchestrator` prunes `sentTo` on each terminal event.

### B.16.2 Routing correctness
- **PROPHET summary overflow.** The full P-table exceeded the 223-byte single-frame budget past ~8 nodes,
  so the orchestrator silently dropped every summary → transitivity/peer-P exchange stopped forever.
  `buildRoutingSummary()` now sends only the top entries by predictability that fit a `SUMMARY_BYTE_BUDGET`
  (200 B, capped at `maxSummaryEntries = 12`); `exportState()` still serialises the full table.
- **MaxProp bootstrap dead-end.** The Dijkstra "no known path" branch returned `null`, refusing to hand a
  bundle to any exploratory mule until a full cost-path already existed. Now falls back to a bounded
  low-priority forward (`MaxPropConfig.exploratoryFloor = 0.02`, parity with PROPHET `relayBaseFloor`).
- **PROPHET P not persisted.** `contacts.delivery_probability` was never written (always 0.0; lost on
  restart). The orchestrator now writes the canonical PROPHET P
  (`StrategySelector.prophetDeliveryProbability`, active-independent) after every encounter and re-seeds
  both PROPHET variants from the DB at startup (`seedRoutingProbabilitiesFromDb`, run on the single
  consumer coroutine to avoid racing the in-memory pTable).

### B.16.3 Q-learning
- **Zombie features.** `historicalSuccessRate` was always 0.0 (the attempt/success counters were never
  incremented) and `deliveryProbability` was the constant 0.5 stub under Q-learning. Now
  `incrementDeliveryAttempt` fires on each direct/relay forward, `incrementDeliverySuccess` on confirmed
  delivery, and the Q-state's `deliveryProbability` reads the canonical PROPHET P.
- **No real-time training.** `qEngine.update` only ran via the 15-min `PeriodicWorkRequest`. Added
  `OrchestratorEvent.DrainQUpdates`; `resolveRewardDelivered` enqueues it so freshly-assigned rewards are
  applied immediately — still on the single consumer coroutine (thread-safe), with `markApplied` dedup.

### B.16.4 Signal metrics
- **Hardcoded BLE link quality.** Inbound GATT writes stamped `rssi = -60, snr = 10`. Now the scan path
  caches each peer's real RSSI (`lastRssiByNode`) and inbound messages/encounters use it (BLE has no SNR →
  reported as 0 rather than a fabricated 10). Feeds true link quality to Stability-Aware PROPHET, the
  Q-learning `rssiNorm`, and the RSSI bars.

### B.16.5 Dead-ends assessed
- **RadioDistanceEstimator** — kept: it's live diagnostic logging (the `dist≈Xm` encounter line) with unit
  tests, intentionally not in the RFC-6693 math. Not dead.
- **AirtimeBudgetTracker** — removed the orphaned Hilt provider (nothing injected it). The class + tests
  stay as a building block for a future direct-LoRa transport; phone-side LoRa airtime doesn't exist today
  (the ESP32 hub owns the LoRa link).
- **WiFi-Direct `connectedPeers` deadlock** — does NOT reproduce in this checkout: `handleGroupInfo` has the
  client send a HELLO to the group owner on group formation, and the owner replies with its own HELLO, so
  both sides populate `connectedPeers`. The audit item was from an older revision.

---

# PART C — What Still Needs to Change (to fully realize the architecture)

Ordered roughly by priority.

### C.1 Cleanup / correctness
- ~~Delete dead Meshtastic files~~ — **DONE** (see B.7): AIDL + `org.meshtastic` sources removed, `aidl`
  build feature dropped.
- ~~Run a full Gradle build~~ — **DONE**: `:app:assembleDebug` builds and installs on device.
- **Update `docs/dtn-hub-architecture.md`** — §7 protocol table and §9.2 firmware snippet still predate the
  `HELLO` frame + broadcast fan-out; `firmware/` is authoritative.
- Doc comments across `database/`, `queue/`, `MeshTransport.kt` still say "Meshtastic" (port_num 256,
  `mesh_packet_id`) — harmless legacy naming, worth a cleanup pass.

### C.2 Real-device validation (transports need hardware testing)
> Note: the **orchestrator-side** multi-phone concurrency (parallel bounded fan-out, per-peer locking,
> connect backoff, receipt dedup, flush coalescing) is now handled — see **B.11**. What remains here is
> **hardware** validation of the transport adapters themselves.
- **BLE** (`BleTransportAdapter`): validate MTU negotiation, chunk pacing/back-pressure (current writes are
  fire-and-forget — likely need `onCharacteristicWrite`-driven sequential pacing), reconnection, and the
  `writeCharacteristic` deprecation path on Android 13+ (new API overload).
- **LoRa hub** (`LoRaHubTransportAdapter`): test `WifiNetworkSpecifier` auto-join on API 29+, the legacy
  (<29) manual-join path, socket reconnection when the hub drops, and multi-phone concurrency on the ESP32
  SoftAP (~4–8 clients).
- **WiFi Direct**: verify the HELLO handshake bootstraps both directions and that client-to-client (not just
  client-to-owner) delivery works within a legacy P2P group.

### C.3 Features still missing for the full architecture
- ~~Foreground service~~ — **DONE** (see B.7): real `foregroundServiceType="connectedDevice"` service hosts
  the orchestrator.
- ~~Runtime permission requests~~ — **DONE** (see B.7): `MainActivity` requests them at launch.
- ~~End-to-end delivery semantics~~ — **DONE** (see B.7): `ROUTING_ACK` receipt bundles; hop-level status is
  informational only.
- **Deeper multi-hop routing (distance-vector).** The live-path finder is capped at a 2-hop horizon because
  peers only exchange their DIRECT P-vectors. To route past 2 hops, propagate 2nd-degree summaries (each peer
  includes a bounded digest of its own reachable nodes) and turn `findNextHopToward` into a max-product
  path search. This is the main routing item left.
- **Fragmentation** for payloads > 223 bytes (header has fragmentIndex/Total fields, but no reassembly logic).
- **Hub → phone id learning for routing** — the hub is treated as a single peer; it does not advertise which
  destination phones are reachable through it. Fine for flooding, but a "route hint" would reduce airtime.

### C.4 Firmware hardening (before scaling past a few hubs)
- **LoRa duty-cycle pacing** (regional legal limits) — none yet.
- **Over-the-air encryption** (AES) — none yet; WPA2 only protects the SoftAP link.
- Per-hub config: set unique `HUB_ID` + `AP_SSID` before flashing each device; set `LORA_FREQ` to the legal
  band (868 EU / 915 US / 433 many-Asia).
- Keep `HDR_MIN` and offsets in `main.cpp` in sync with any `DtnWireCodec` header change.

### C.5 Cosmetic / tech-debt
- Doc comments across `database/`, `queue/`, `MeshTransport.kt` still mention Meshtastic ACK / packet-id
  semantics — harmless, worth a cleanup pass.
- `MeshTransport` interface KDoc still describes the old Meshtastic contract; `mesh_packet_id` column name is
  now generic.


---

# PART E — 2026-10-16 session: E2E encryption, groups, internet relay, Heltec V3 firmware v2, UI

Big feature + hardening pass. Build is green (`:app:assembleDebug`) and all JVM unit tests pass
(55: the prior 47 + 8 new `DtnCryptoTest`). Firmware v2 compiles and was flashed to both hubs;
the LoRa hub-to-hub backbone was verified live (each hub hears the other's heartbeat on IN865).

## E.1 Hub firmware v2 (`firmware/`) — DONE + flashed
Full rewrite of `firmware/src/main.cpp` (+ `platformio.ini`). Verified on two Heltec WiFi LoRa 32 V3:
boot, SoftAP up, LoRa init on IN865 866.3 MHz, mutual heartbeat reception, airtime ≈0.02%.
- Correct Heltec V3 radio: explicit SPI (SCK9/MISO11/MOSI10/NSS8, DIO1-14/RST12/BUSY13), 1.8 V TCXO
  (+ automatic XTAL fallback), 22 dBm (region-capped), 140 mA OCP, boosted RX, preamble 16, sync 0x2B,
  DIO2 as RF switch.
- **Region config** (IN865 866.3 / EU868 869.525 / US915 906.875 @ BW500) stored in NVS; change at
  runtime over serial (`region in|eu|us`). Default IN865 for Andhra/India. Compile default via
  `-D DTN_DEFAULT_REGION`.
- **HUB_ID + SoftAP SSID suffix derived from the eFuse MAC** → identical binary flashed to every hub;
  they self-differentiate (observed `!65dab734` / `!64dab734`, SSID `DTN-HUB-B734`).
- Interrupt RX/TX/CAD **state machine** (no longer mis-reads TX-done as RX).
- **Listen-before-talk** (`scanChannel` CAD) + random jitter + overhear-suppression (cancels a queued
  relay when the same msgId is heard from another hub).
- **Rolling-hour airtime ledger** enforcing the region duty cycle (10% IN/EU).
- **TTL aged by buffer residence** before every re-flood; shared hop limit 8.
- **Non-blocking per-client TCP reader** (a slow/stalled phone can no longer freeze the hub); 255-byte MTU.
- **Store-and-forward**: buffered bundles replayed to a phone when it REGISTERs.
- **ROUTING_ACK delivered to local phones** (receipts now cross hubs).
- OLED status screen (Vext GPIO36, reset GPIO21, I²C 17/18), battery readout (GPIO1/37), watchdog.
- SoftAP SSID prefix `DTN-HUB-`, passphrase `dtnmesh123`, 192.168.4.1:9740 kept (app-compatible).
- Known: observed peer RSSI ≈ −107 dBm at close range → antennas should be attached (flagged to user).

## E.2 Cryptography (`crypto/`) — DONE (unit-tested)
End-to-end X25519 + AES-256-GCM, pure-Java (BouncyCastle `bcprov-jdk18on:1.78.1` + platform JCE), runs
on API 26+ (no dependency on the platform XDH provider which needs API 33).
- `DtnCrypto`: X25519 ECDH, Ed25519 sign/verify, HKDF-SHA256, AEAD seal/open, node-id derivation, fingerprint.
  Per-message AES key + nonce derived via HKDF(salt = messageId, info = suite‖origin‖dest‖frag) so each
  (key,nonce) is used once — overhead is just the 16-byte GCM tag. Suites: 0 plain, 1 DM, 2 channel.
- `KeyVault`: Android Keystore AES-256-GCM master key wrapping the identity blob at rest.
- `DtnIdentity`: X25519 + Ed25519 keypair, persisted (Keystore-wrapped file), **node id derived from the
  public keys** (`!%08x` of trunc(SHA-256(domain‖xPub‖edPub))) — replaces the spoofable ANDROID_ID hash.
  `LocalNodeIdentity` now delegates to it. NOTE: node ids changed → on-device identity resets once.
- `Announce`: signed identity/key broadcast (ANNOUNCE msgType 0x05) + verify; receivers check the
  node-id↔key binding before pinning (TOFU).
- `EnvelopeService`: sealText / open, choosing the suite by (dest, channel); AAD = masked header
  (ttl/hop zeroed). `GroupKeyStore`: per-channel 32-byte keys (Keystore-wrapped) + QR invite strings.
- `DtnCryptoTest`: round-trip, wrong-key, tamper-AAD, per-fragment keys, channel, nodeNum determinism,
  announce sign/verify.

## E.3 Wire format v2 — DONE
- Header byte 31 (was "reserved") is now **flags**: bits0-2 crypto suite, bit6-7 version. `DtnMessage.flags`,
  `DtnWireCodec` encode/decode flags + `aadHeader()`; `DtnMessageType.ANNOUNCE` added.
- **TTL now counts down**: `encode` writes `remainingTtlMs()` (not the original ttl). Fixes the
  "immortal bundle / latency ≈ 0" bug.
- `MessageEntity.flags` column added; `MessageQueueManager.toEntity/fromEntity` preserve `flags` and
  `messageType` so a mule re-emits an encrypted bundle intact (else the recipient couldn't decrypt).

## E.4 Pipeline / orchestrator — DONE
- Outgoing: `OutboundMessageService.sendText` → seal → persist plaintext to `ChatDao` → `ingest` →
  (optional) publish to internet relay. `sendAnnounce` floods our keys.
- Incoming (`DtnOrchestrator.onMessageReceived` rewritten):
  - ANNOUNCE → verify + pin keys (TOFU) + epidemic relay.
  - DM to us → decrypt → write chat IN → markDelivered + receipt.
  - **Broadcast/group → epidemic**: buffer for onward flooding, decrypt+show once if we're a recipient,
    **no receipt, no early markDelivered** (fixes receivers prematurely clearing broadcasts). Carries
    until TTL. `flushOnce` no longer clears broadcasts after fan-out.
  - unicast for someone else → opaque mule custody.
- **Security**: routing summaries are only trusted when `viaPeer == origin` (1-hop) — blocks the
  blackhole / identity-rebind attacks over relays/hub.
- **Single-frame only** for now: the composer enforces a UTF-8 byte budget (fixes the >223-byte
  head-of-line stall); multi-fragment reassembly is still TODO.

## E.5 Database v3 — DONE (destructive fallback kept; reset OK per user)
New tables `chat_messages` (persistent plaintext chat, decoupled from the forwarding buffer — fixes
history being purged with the buffer), `seen_bundles` (tombstone table, wired but not yet consulted —
resurrection fix is a follow-up), `groups`; contacts gained `x_pub/ed_pub/key_verified/key_first_seen_ms/
announce_name`. `exportSchema` + `room.schemaLocation` set. Real migrations still TODO before field use.

## E.6 Transports — DONE (partial)
- **WiFi Direct**: fixed the unbounded `senderIdLength` read (was an OOM remote-crash). (Socket timeouts
  / periodic discovery still TODO.)
- **InternetTransportAdapter (MQTT, HiveMQ)**: optional relay (off by default), topics `dtn/v1/node/<hex>`
  (DM) + `dtn/v1/bcast`. Bundles stay ciphertext, so a public broker (default `broker.hivemq.com:1883`)
  is fine; switchable to a laptop/Render broker in Settings. Origin publishes once; inbound merges into
  the orchestrator; dedup by messageId prevents loops. Works best when both ends are online (server-side
  offline mailbox is a future enhancement). NOT in the local send-priority chain.
- Still TODO: BLE reassembly reset on disconnect + length cap; LoRa-hub reconnect/backoff; `viaPeer` on
  LoRa + WiFi Direct inbound.

## E.7 UI — DONE (core)
- Top-bar actions on the home screen: 👥 Groups, 🔑 Identity, ⚙ Settings (dialogs in `ui/ChatExtras.kt`).
- **Identity dialog**: my QR (node id + public keys + name), safety number (fingerprint), scan a contact
  or group QR (zxing) → pins + verifies.
- **Settings dialog**: display name (used in announces), internet-relay toggle + MQTT broker URL.
- **Groups**: create (random key + invite QR), join by scanning an invite, group chat cards in the list;
  group messages use suite-2 channel encryption over broadcast.
- **Composer**: live UTF-8 byte counter + over-limit block; `composerError` surfaced (no-key / too-long).
- Chat now reads from `ChatDao` (persistent, dedup by id). `DtnViewModel` send path goes through
  `OutboundMessageService`; status driven by `deliveredIds`/`terminalEvents` → `ChatDao.updateStatus`.
- Platform: `allowBackup=false` (DB/keys no longer backed up); COARSE+FINE location requested.

## E.8 Steering (`.kiro/steering/`) — DONE
`project.md` (always) + `wire-protocol.md` (fileMatch receiver/firmware/model/crypto) so future sessions
start grounded: architecture, build/test commands, the wire header + crypto envelope, firmware invariants,
and the "no destructive migrations / no secrets in source / TTL counts down" rules.

## E.9 Dependencies added
`bouncycastle 1.78.1`, `zxing core 3.5.3` + `zxing-android-embedded 4.3.0`, `hivemq-mqtt-client 1.3.3`,
`androidx.datastore-preferences 1.1.1`. AGP packaging excludes added for Netty's duplicate META-INF.

## E.10 Still TODO (carried forward / newly opened)
- Multi-fragment messages (>223 B): reassembly + per-fragment dedup + whole-parent eviction.
- Consult `seen_bundles` tombstones in `onMessageReceived` to kill resurrection after the 1 h buffer purge
  (table exists; purge in `ForwardingWorker`).
- Hash-locked receipts (prevent forged ROUTING_ACK dropping custody) — hub + phone.
- Real Room migrations (drop destructive fallback) before any field deployment.
- Transport hardening: BLE reassembly reset + CRC, LoRa-hub reconnect loop, `viaPeer` on LoRa/WiFiDirect,
  WiFi Direct socket timeouts + periodic discovery.
- Confine routing/Q state to one dispatcher (ConcurrentModification risk under load).
- Hub-side internet gateway (AP+STA) and server-side offline mailbox for the relay.
- Foreground-service permission-before-start ordering; edge-to-edge insets on the chat screen; dark theme;
  split `MainScreen.kt`; targetSdk 36 (needs AGP ≥ 8.9) for a Play release.
- Firmware link-layer AES-CMAC MIC (network key) — deferred (would cut the 223-byte LoRa payload budget).


---

# PART F — 2026-10-16 (cont.): correctness / transport / fragmentation hardening

Second pass after the "Intermediate half" commit. Build green (`:app:assembleDebug`); all JVM unit
tests pass (59: the prior 55 + 3 `FragmenterTest` + the fragment crypto round-trip). Committed as a
second "Intermediate half" checkpoint.

## F.1 Transport robustness (Wave 1)
- `viaPeer` now set on LoRa-hub inbound (= hub id) and WiFi Direct inbound (= sender), so reverse-echo
  suppression and the 1-hop routing-summary trust check apply on those transports too.
- WiFi Direct: `soTimeout = 5 s` on accepted sockets (no more pinned IO threads); periodic
  re-discovery every 15 s in the sweeper (P2P discovery otherwise stops after ~2 min).
- BLE: reassembly buffer resets on GATT-server disconnect and rejects bogus frame lengths
  (re-syncs instead of buffering junk / swallowing good traffic).

## F.2 Concurrency (Wave 1)
- PRoPHET, MaxProp and the Q-tables now back their maps with `ConcurrentHashMap` (outer + inner),
  removing the ConcurrentModificationException risk when the flush/selection coroutines read while
  the event loop / WorkManager mutates — which previously could crash or silently fall back to
  approve-all (epidemic) flooding.

## F.3 Foreground-service ordering (Wave 1)
- `DtnViewModel.connect()` now connects the transports FIRST and starts the foreground service only
  on success, and logs any missing runtime permissions up front — no more zombie service + orchestrator
  when permissions are denied or Bluetooth is off.

## F.4 Resurrection tombstones (Wave 2)
- New use of the `seen_bundles` table: the orchestrator writes a tombstone when a message is
  DELIVERED (to us), EXPIRED, or user-CLEARED, and checks it before accepting any inbound DATA — so a
  late or re-flooded copy can't resurrect a message after the 1-hour buffer purge. A delivered DM
  still re-pushes its receipt. Pressure drops are intentionally NOT tombstoned (still re-acceptable).
  `ForwardingWorker` purges expired tombstones each cycle.

## F.5 LoRa-hub reconnect (Wave 2)
- `LoRaHubTransportAdapter` now runs one supervised reconnect loop with exponential backoff (1→30 s):
  it waits for the hub WiFi, connects, registers, pumps the read loop, and reconnects on any drop
  while connected is desired. The old one-shot `openSocketAndRun` is gone.

## F.6 Real Room migration (Wave 3)
- `AppDatabase` declares `autoMigrations = [AutoMigration(2, 3)]` (v2→v3 is purely additive: new
  contact key columns + `messages.flags` + the `chat_messages` / `seen_bundles` / `groups` tables).
  Added `@ColumnInfo(defaultValue = "0")` to the NOT NULL added columns so Room can generate it. The
  destructive fallback stays only as a safety net for unknown version jumps. Schemas committed under
  `app/schemas/`.

## F.7 Chat-screen insets (Wave 3)
- The chat header now uses `statusBarsPadding()` (gradient fills behind the status bar) and the chat
  column uses `imePadding()` so the keyboard no longer covers the composer under edge-to-edge.

## F.8 Message fragmentation (Wave 4) — the former #1 TODO
- Messages larger than one LoRa frame are now split across up to `Fragmenter.MAX_FRAGMENTS` (16)
  bundles. All fragments share a **canonical parent id** (UUID, last byte 0) but each carries a
  **distinct wire id** (last byte = index+1), so the existing id-based dedup on phones and hubs treats
  them as independent bundles (every fragment propagates) while the receiver recovers the parent by
  zeroing the last byte. Fragment index/total travel in the header; each fragment is sealed
  independently (its index feeds the AEAD nonce + AAD).
- `EnvelopeService` gained `sealBytes(...fragIndex, fragTotal)` and `openBytes(...)`. Reassembly joins
  the raw plaintext BYTES and decodes to text only once, so a multi-byte UTF-8 character that straddles
  a fragment boundary stays intact. Reassembly is in-memory, keyed by parent id, pruned after 10 min.
- Limitation: a fragmented message shows status "sent" (acknowledged per fragment, not tracked as one
  end-to-end delivery). Single-frame DMs still show the full SENDING → DELIVERED flow.
- `FragmenterTest` covers id derivation/recovery and a byte-exact multi-fragment crypto round-trip with
  a deliberately split multi-byte string.

## F.9 Still deferred (unchanged from E.10 unless noted)
- Hash-locked receipts (forged-ACK custody-drop protection) — cross-cuts phone + firmware + wire.
- Hub as an internet gateway (AP+STA) + a server-side offline mailbox for the relay.
- Firmware hub-to-hub link MIC (would cut the 223-byte LoRa payload budget).
- targetSdk 36 / AGP upgrade (only for a Play release); split `MainScreen.kt`; full dark theme.
- On-device end-to-end test (needs the user: tap Connect, approve the WiFi dialog, a second phone).
- Tombstones (F.4), reconnect (F.5), migration (F.6), fragmentation (F.8) are now DONE.

---

# PART G — 2026-10-16 (cont.): announce-buffer bloat fix + on-device connect findings

On-device bring-up (1 phone + both Heltec hubs over USB). Hub A OLED read `ph:1 hub:1` once the
phone joined; build green, 59 unit tests still pass. The fixes below are **query/logic only — no
entity/schema change**, so Room's identity hash is unchanged and reinstalling does NOT wipe the DB.

## G.1 Bug — the forwarding buffer grew ~1/min with "broadcast messages" the user never sent
- **Reported symptom.** With a single phone connected (no second phone), the buffer count climbed by
  one every minute or two; a "broadcast message" kept appearing in the buffer/Network view. Chat
  history was actually empty.
- **Root cause (confirmed by pulling the live Room DB via `run-as`).** Every buffered row was a
  `ANNOUNCE` originating from the phone itself (`!99a9769a`), dest `^all`, one per ~120 s. The periodic
  identity re-announce in `DtnViewModel` (so newly-met peers can TOFU-pin our keys) ingests each
  announce into the store-carry-forward buffer for epidemic relay. With no peer to hand them to and no
  supersede rule, every re-announce added a new buffered bundle (TTL-capped at ~5–6 live), and the
  user-visible buffer count/list showed this control traffic as mystery broadcasts.

## G.2 Fix
- **Supersede stale announces** (`MessageDao.supersedeAnnounces`, wired in `MessageQueueManager.ingest`):
  when a fresh `ANNOUNCE` is stored, that origin's previous active announces are hard-DELETEd (keys are
  identical; only the newest needs relaying). Keeps at most one announce per node in the buffer. Applies
  to announces received from other nodes too (one per origin), not just our own.
- **Hide control traffic from the UI** (`MessageDao.observeBufferedCount` / `observeBufferedMessages`):
  both now filter `message_type = 'DATA'`, so the "Buffer N" badge and Network list reflect only real
  user messages. Control bundles still live in the buffer for the protocol; the orchestrator's relay
  path (`getAllBuffered`) is unchanged.
- **Eased cadence** (`DtnViewModel`): re-announce every **5 min** (was 2), well inside the 10-min
  announce TTL — less LoRa airtime, same key propagation.
- **Verified against the real pulled DB:** DATA-only buffered count = 0 (was showing the announces);
  the supersede DELETE collapses 6 active announces → 1. Build green, 59 tests pass, APK reinstalled
  and launched clean (no DB wipe).

## G.3 On-device connect findings (behaviour notes, not code changes)
- **LoRa hub join is a WiFi SoftAP step, easy to miss.** Pressing **Connect** fires a one-shot
  `WifiNetworkSpecifier` request for SSID prefix `DTN-HUB-`; Android shows a system "connect to
  DTN-HUB-xxxx?" dialog that must be approved. On API 29+ the app binds only to that requested network
  (a manual join from system WiFi settings is NOT picked up). If the dialog is dismissed, the hub stays
  at `ph:0` and the app sits on "Connecting…". Re-trigger via Disconnect→Connect. Nearby-devices +
  location permissions must be granted or the dialog never appears.
- **"Connected" badge = local mesh radios only** (BLE / WiFi Direct / LoRa hub). The internet (MQTT)
  relay is a separate opt-in toggle and deliberately does not change that badge.

## G.4 Newly opened / still deferred
- **Dedicated "Connect to Hub" button + hub-connected indicator.** `connectHubOnly()` exists in the
  ViewModel but isn't surfaced as its own button; the hub join currently rides the main Connect. A
  dedicated, retryable control would remove the "stuck on Connecting" confusion.
- **Namespace the MQTT broadcast topic.** The internet relay publishes/subscribes `dtn/v1/bcast` on the
  public `broker.hivemq.com`, a globally shared topic — fine for testing, but anyone on that broker can
  see ciphertext/headers or inject broadcasts. Namespace (or move to the planned private relay) before
  real use. `No-Local` is also not set, so the origin receives its own publish back (currently harmless:
  deduped by message id in the orchestrator).
- Everything in F.9 that was still open remains open.

---

# PART H — 2026-10-16 (cont.): connection model, internet presence, groups, QR, UX overhaul

User report: 14 UX issues (2.1–2.14, no 2.12) + "groups are not working" + portrait QR scanning with
image upload + "a QR-scanned contact out of range shows offline although their internet works" +
"Bluetooth off → stuck on Connecting". Build green, **85 JVM tests pass** (59 + 26 new). **No Room
schema change** (no migration, no DB wipe); 32-byte wire header and hub firmware unchanged.

## H.1 Root causes found
- **Groups:** channels were allocated sequentially per phone (everyone's first group = channel 1),
  so joining another phone's group **silently overwrote** your own group (PK = channel) and traffic
  collided; inbound group/broadcast rows stored no sender (every bubble said `grp:1`); the chat header
  showed `grp:1`; invite parsing broke on `:` in names.
- **Broadcast storm:** the flush rate-limit exempted broadcasts, so every buffered broadcast / group
  message / announce was re-sent to every online peer **every 4 s for its whole TTL** (a GATT connect
  + write per bundle per peer).
- **Stuck "Connecting…":** `MultiTransportManager` computed the aggregate state as a **snapshot at
  connect time** — never recomputed, so a hub joining later (or BLE failing with Bluetooth off) was
  never reflected.
- **Internet contacts offline / undelivered:** the MQTT relay had no presence, wasn't routable, wasn't
  in the flush peer set, carried no receipts, published only once at send time, used a random client id,
  never tracked disconnects, and never retried a failed first connect.
- **Silent loss:** a DM we couldn't decrypt yet (sender scanned our QR but we lack their keys) was still
  acknowledged → sender saw "delivered", user never saw it.
- **Scanner landscape:** zxing's `CaptureActivity` is hard-locked to `sensorLandscape` in its manifest.

## H.2 Transport / connection model
- `receiver/TransportStatus.kt` (`TransportKind`, per-transport state + wanted set, reactive aggregate);
  `receiver/RadioStatusMonitor.kt` (Bluetooth / Wi-Fi / location / validated-internet flows).
- `MultiTransportManager`: `transportStatus`/`connectionState` derived via `combine` of all four
  adapters; `connect(kind)` / `disconnect(kind)`; BLE stops when Bluetooth turns off and restarts when it
  returns (if wanted); send priority BLE → Wi-Fi Direct → hub-target → **internet (peer present)** →
  hub flood. `MeshTransport` gained `transportStatus`, `internetPeers`, `isRadioReachable()`.
- BLE / Wi-Fi Direct emit offline events for their peers when individually switched off; the hub adapter
  unregisters a stale `WifiNetworkSpecifier` request before "tap to retry".
- ViewModel: foreground service + orchestrator run exactly while any transport is wanted.

## H.3 Internet relay (presence + delivery)
- `receiver/InternetFrames.kt` (`[D7 01][4B publisher][wire]` framing + `Presence` codec).
- `InternetTransportAdapter` rewritten: stable client id `dtn-<hex8>`, Last Will `off` + retained `on`
  presence on `dtn/v1/presence/<hex8>`, 60 s heartbeat + stale sweep, global publish consumer,
  re-subscribe on every connect, state from connected/disconnected listeners, auto-reconnect incl. a
  failed first attempt, `sendMessage()` publishes to the node topic and awaits PUBACK, own-echo and
  relayed ROUTING_SUMMARY dropped.
- Orchestrator: flush includes present contacts (radio link wins); relay semantics = DMs addressed to the
  peer only, kept until the end-to-end receipt (PUBACK ≠ delivery), retried on the 30 s window; broadcasts
  pushed once per presence session (catch-up); no third-party custody; no benchmark / Q pollution.
  Presence changes trigger a flush; leaving the relay ends the session. Origin publishes only
  broadcast/group/announce to `bcast`; DMs go through the flush.
- Undecryptable DMs for us are no longer acknowledged (row forgotten, no tombstone) so a retransmission
  after the sender's announce is accepted.

## H.4 Groups
- `crypto/QrPayloads.kt`: `GroupInvite` (key-derived channel `1 + SHA-256("DTN-grp-ch-v1"‖key)[0] mod
  255`, colon-safe parsing, legacy invites still parse) and `IdentityQr` (java.util.Base64 → testable).
- `GroupKeyStore`: new groups take a key-derived free slot; `joinGroup` returns
  `Joined / AlreadyMember / Conflict` and **never overwrites** a different group.
- Group/broadcast inbound rows store the origin → bubbles show the sender; header shows the group name,
  members seen, and an info sheet (invite QR, share, leave). Leave group added.
- Broadcast fan-out: once per peer per encounter/presence session, never echoed to whoever handed it over.

## H.5 QR
- `ui/QrScanner.kt`: portrait Compose scanner (embedded zxing `BarcodeView`, TextureView preview,
  viewfinder overlay matching the framing rect, torch, haptic), camera-permission handling, **upload from
  gallery** (Photo Picker → multi-pass ZXing decode with alpha flattening + downscale retry), share QR as a
  PNG via FileProvider (`res/xml/file_paths.xml`), async crisp `QrImage`.
- `ui/QrCodec.kt`: pure encode/decode (two binarizers × normal/inverted, then PURE_BARCODE fallback).

## H.6 UX issues
2.1 Clear asks first (with count) · 2.2 Settings fully staged (`saveSettings`) · 2.3 every broadcast id
form labelled, group routes show the group name, broadcast routes say FLOODING · 2.4 path map: canvas for
lines only, labels are ellipsized Compose text, rows scale with font size · 2.5 benchmark table in a
horizontal scroller with fixed columns · 2.6 SENT ✓ / RELAYED / RECEIVED · 2.7 full 64-hex safety number
(+ per-contact safety number with "Mark as verified") · 2.8 Network peers open the chat · 2.9 four
tappable transport chips + preflight checklist (permissions / Bluetooth / Wi-Fi / location, "use internet
instead") · 2.10 strategy-specific peer metric (P / f / Q fwd-wait) and a real Q-learning route breakdown
(`ForwardingStateBuilder` shared with the orchestrator, `DoubleQLearningEngine.qValues`) · 2.11 QR built off
the main thread at module resolution · 2.13 grapheme-safe avatar initials · 2.14 permission prompts with
"App settings". Chat banner offers "Turn on Bluetooth" / "Use internet relay" when a contact is unreachable.

## H.7 Verification status
- Build + 85 unit tests green. **Not yet verified on device**: the phone dropped off adb during this
  session, so install + on-device smoke test (scanner, chips, presence) are still to do.

## H.8 Known limits
- Presence and the publisher prefix are unauthenticated on the public broker (spoofing only causes extra
  encrypted publishes / a skipped push; content is never exposed). The `dtn/v1` namespace is shared.
- Relay delivery needs the relay on at both ends; the public broker keeps no offline queue (the sender's
  buffer + presence does the store-and-forward).
- Radio direct delivery still counts a successful GATT write as delivery (unchanged).

---

# PART I — 2026-10-22: field-bug round (ANR, relay, Wi-Fi Direct, message status) + Play Store readiness

User reports after installing Part H: Disconnect "crashes" (mostly with the relay on), relay won't
link on most phones, Wi-Fi Direct shows "unavailable" after Wi-Fi off→on, chat UI needs polish (keep
the 4 transport chips), Message details say IN TRANSIT for delivered messages / details missing, make
it publishable on Google Play, build an APK to share. **No Room schema change** (journal is a file).

## I.1 Root causes (verified on the phone: logs, /data/anr, pulled DB, nc probes)
- **"Crash" = ANR.** `InternetTransportAdapter.disconnect()` published the presence `off` with a
  blocking HiveMQ call on the main thread (`MqttPublishFlowables.add` → `Object.wait`), reached from
  `DtnViewModel.disconnectAll` / `toggleTransport` → "Input dispatching timed out".
- **Relay never linked:** broken IPv6. `broker.hivemq.com` has AAAA records; on the user's network
  every IPv6 connect black-holes (`nc -6` timed out even for google.com) while IPv4 works. The client
  chose IPv6 and timed out on each retry.
- **Wi-Fi Direct after Wi-Fi off→on:** the adapter went DISCONNECTED on P2P-disabled and never
  resumed. It also auto-connected to *any* P2P device (a TV, strangers' phones) → invitation pop-ups,
  a stuck negotiation and `discoverPeers` BUSY (reason 2).
- **"In transit" though delivered:** route records were in memory (lost on restart) and only some
  paths flipped them; three different status computations; the chat row's DELIVERED/EXPIRED was
  written only by a ViewModel collector (lost while the screen was closed); receipts for a buffer copy
  in FORWARDING state never updated the chat row.
- **Noise:** announces/receipts/summaries appeared as routes and journal events; announce/receipt
  expiries were journaled as EXPIRED messages.

## I.2 Fixes
- **Relay:** publishes on one dedicated thread, only while CONNECTED, `runInterruptible` + 8 s cap;
  `disconnect()` returns at once and tears down in the background (session generations).
  `receiver/RelayEndpoints.kt` (pure): own DNS, **IPv4 first**, HiveMQ fallbacks TLS 8883 → TCP 1883 →
  WSS 8884, rotated per failed attempt; default broker `ssl://broker.hivemq.com:8883`. Diagnostics
  line ("Connected · TLS 3.120.13.97:8883" / "Attempt n failed (…) · next …") in Settings + Log.
- **Hub:** `onLost` → CONNECTING + re-request after 4 s; Wi-Fi follower re-requests on Wi-Fi return.
- **Wi-Fi Direct:** stays wanted across Wi-Fi off (chip "Wi-Fi off" → "Searching" on return);
  advertises DNS-SD `_dtnmesh._tcp` and connects **only** to devices advertising it; cancels a stale
  connect at start and after 3 BUSY rounds; the sticky P2P-enabled broadcast no longer double-starts.
- **Delivery semantics:** `SendPath.reachesTargetDirectly` — only BLE, Wi-Fi Direct or the hub that IS
  the target proves delivery; relay / hub flood wait for the end-to-end receipt.
- **Chat status persisted where it happens:** `MessageQueueManager` (markDelivered / confirmDelivered,
  expiry, clear, drops, buffer-pressure evictions — the latter previously silent). New
  `ChatDao.markOutgoingDelivered` / `markOutgoingEnded` never downgrade DELIVERED or touch IN rows.
- **Persistent journal:** `MessageLifecycleLog` → `filesDir/message_journal_v1.log` via `JournalCodec`
  (tab-separated, escaped, compaction at 6000 lines; 1000 events / 300 routes in memory). Identical
  event+detail for one message within 10 min is collapsed (30 s relay retries). CREATED/BUFFERED are
  written by `OutboundMessageService`; a relay broadcast publish is journaled once acknowledged.
  Control traffic → engine console only.
- **One status model:** `ui/MessageStatus.kt` `deliveryState()` (Queued 🕓 · Sent ✓ · Delivered ✓✓ ·
  Received · Spreading · Carrying · Expired/Dropped !) used by bubbles, route rows and Message info.
- **Chat UI:** date separators, sender runs, bubbles ≤ 80 % width, tap/long-press menu (Message info,
  Copy), incoming "via <link>", contrast-safe status colours, empty-state cards (encryption notes),
  multi-line composer with per-chat placeholder, Send disabled when blank/over budget, byte counter from
  75 % of the 200 B single-frame budget, auto-scroll on new messages, IME double-gap fix
  (`consumeWindowInsets`), notice instead of composer after leaving a group. Chat list: last-message
  time + tick, conversations sorted by recency. Headline "Online via …" only (chips unchanged).
- **`ui/MessageInfoDialog.kt` (live):** state pill + explanation, content, conversation, from/to,
  sent/delivered (+ duration), link, hops, routed-by, path, journey timeline; routing math only for DMs
  routed onward. Opened from bubbles, route rows, Lifecycle events and buffer-history rows.

## I.3 Play Store readiness
- AGP 8.13.2 / Gradle 8.13, compileSdk + targetSdk 36 (Play requirement since 31 Aug 2026),
  versionCode 2 / versionName 1.0.0, `buildConfig` on (Settings → About & privacy).
- Release: R8 minify + resource shrinking, `app/proguard-rules.pro` (HiveMQ: keep Netty/JCTools member
  names + `-dontwarn` for optional deps). Upload key `keystore/dtn-upload.jks` + `keystore.properties`
  (git-ignored; release is unsigned without them). `staging` build type = release code, debug-signed,
  `.staging` suffix, for on-device R8 tests beside the debug app.
- Manifest: location `maxSdkVersion=32` (PermissionHelper asks only ≤ API 32 — BLE/Wi-Fi Direct use the
  `neverForLocation` permissions on 12+/13+), WRITE_EXTERNAL_STORAGE removed (export uses the
  app-specific dir), `dataExtractionRules` exclude everything (keys/chats never leave the phone),
  adaptive launcher icon with monochrome layer, notification icon + tap-to-open.
- 16 KB pages: the only native libs (AndroidX `graphics.path`, `datastore_shared_counter`) have 16 KB
  LOAD alignment and are 16 KB zip-aligned in the APK.
- Store kit: `docs/play-store/` (listing, privacy policy, data safety, permissions/FGS declaration,
  release checklist, graphics + screenshots).

## I.4 Verified on device (Redmi 23090RA98I, Android 16)
- Relay connects in 1–3 s over TLS/IPv4; Disconnect with the relay on → no ANR, also across rapid
  connect/disconnect cycles. Wi-Fi Direct discovers without location on API 36; Wi-Fi off → "Wi-Fi off",
  on → "Searching" within 4 s. Chat UI, menu, Message info and keyboard layout checked by screenshot.
- R8 staging build: launches, Hilt/Room/WorkManager fine, relay connects (tested against
  broker.emqx.io so the throwaway identity never reached real users), journal + Message info correct.
- 109 JVM unit tests (new: RelayEndpoints, JournalCodec, deliveryState, date/time labels).

## I.5 Known limits / next
- A release-signed APK can't replace a debug-signed install (different key): one uninstall (loses
  keys/chats); moving to the Play build later needs one more (Play App Signing key).
- Play still needs: developer account, a public privacy-policy URL, the Console forms (texts prepared).
- Multi-fragment messages are tracked as one route (the first fragment's receipt marks it delivered);
  the composer keeps messages single-frame (≤ 200 B), so the UI can't create them today.

---

# PART J — 2026-10-22/23: LoRa hub fixed (firmware v3 + roster), invitation-free Wi-Fi Direct groups, buffer + presence fixes

User reports: Wi-Fi Direct broken / invitation pop-ups, "I can see myself", contacts shown online
over the internet long after they left, messages "Sent" while offline, announcements still counted
as buffered; then "LoRa not working — 3 phones on the hub, nothing sent or received, all offline to
each other", "link multiple devices, show Linked + who is connected", QR torch/upload too low.
**No Room schema change.**

## J.1 Root causes
- **LoRa hub sent nothing to any phone (v2 firmware).** `sendFrame` returned early when
  `sock.availableForWrite() < 3+len`; arduino-esp32 2.0.17's `WiFiClient` doesn't implement it, so
  `Print::availableForWrite()` is always 0 → no HELLO, no deliveries, no replays. Phones never learned
  the hub id and never flushed to it (dashboard: `out=0`, `dlv=0`).
- The v2 idle check `millis() - lastSeenMs > CLIENT_IDLE_MS` wrapped when `lastSeenMs` was stamped a
  millisecond after `now` → instant "Phone timed out (idle)" loops and ack floods.
- Phones on one hub had no way to know about each other; the routing strategies never handed DMs
  to the hub (it sends no routing summaries, so PROPHET had nothing to rank it by).
- "I can see myself": after *Clear storage* the phone has a new identity while its old identity's
  announces still circulate (other phones' buffers, hub replays) and got pinned as a contact.
- Buffer counter/list still included broadcast + group DATA (only control types were filtered).
- QR scanner: from Android 15, `Configuration.screenHeightDp` includes the system bars and Compose
  sizes a full-width dialog's content from it, while the dialog window sits between the bars → the
  content was ~90 dp taller than its window and the bottom buttons landed on the navigation bar.
- Earlier in the round: own-origin bundles echoed back were processed (self-contact), retained
  relay presence never went stale, and a relay DM publish was recorded as a hand-off ("Sent").

## J.2 Firmware v3 (`firmware/src/main.cpp`, `DTN_FW_VERSION=3`)
- Per-client 4 KB non-blocking TX queue (`enqueueFrame` whole frames only, `flushClient` with
  `send(MSG_DONTWAIT)`); `dropClient`; a REGISTER retires a stale duplicate connection.
- `deliverLocal(dest, data, len, skipClient)`: never echoes to the phone that handed it in;
  ROUTING_ACK goes to every local phone (receipts addressed to the hub reach the phones too).
- `pumpReplay` (cursor per client): after REGISTER, aged-TTL copies of bundles for that phone,
  broadcasts and acks, skipping the phone's own.
- ROSTER 0x05 (on change + every 30 s); PING 0x04 / zero-length frames refresh `lastSeenMs`;
  signed idle check. A held bundle re-sent by a phone: DM re-delivered locally, never re-flooded;
  broadcast/ack ignored. Dashboard lists phones (idle, txq) and `phones: in/out/dropped`.
- Flashed to the COM11 hub (`!64dab734`). **Any other hub (e.g. COM13) still runs v2 → broken.**

## J.3 App — LoRa hub
- `LoRaHubTransportAdapter`: PING every 30 s, ROSTER parsing (`parseRoster`, tested) → `hubPeers`
  and online/offline encounters (rssi −200 = no own signal; the orchestrator keeps the last real
  RSSI), `hubId`, `linkInfo`; re-request the hub network 8 s after it's lost (hub reboot ≈ 6 s).
- `MeshTransport`: `hubPeers`, `isHub`, `isHubPeer`, `reachesDirectly` (BLE / Wi-Fi Direct /
  hub-as-target); `isRadioReachable` also covers roster phones.
- `MultiTransportManager.sendMessageVia`: BLE → Wi-Fi Direct → hub (target is the hub) →
  **same hub** (`SendPath.HUB_LOCAL`, only when the bundle's destination is that phone) → relay →
  hub flood. `HUB_LOCAL.reachesTargetDirectly = false` → delivery waits for the receipt.
- `DtnOrchestrator`: the hub gets every DM once per hub session, bypassing the strategy, unless the
  destination is reachable directly / via the roster / on the relay. Roster-only phones get only
  DMs addressed to them (no broadcasts, receipts, custody or routing summaries — the hub fans those
  out). Rate-limit exemption uses `reachesDirectly`. An offline event is ignored while another link
  still reaches the peer. Routing summaries only over direct links (never to the hub).
- Announces issued before `DtnIdentity.createdAtMs` (identity file mtime) are neither pinned nor
  relayed (live peers re-announce every 5 min, so a genuine one is only delayed).

## J.4 App — Wi-Fi Direct groups (Android 10+, no invitations)
- `WifiDirectGroupPolicy` (pure, tested): join the best group (most members, then lowest owner);
  lowest node id creates (others wait 25 s, then create); empty owners merge; idle owner disbands
  after 120 s alone.
- `WifiDirectHints` (tested): BLE scan-response service data `[4B id][1B flags][4B nonce]`; group
  `DIRECT-DT-<hex>`, passphrase = SHA-256-derived 20 hex chars. DNS-SD TXT `v=2,id,g,k,n` as a
  second discovery path. Join/create via `WifiP2pConfig.Builder` → nobody accepts anything.
- `WifiDirectTransportAdapter` rewritten: 3 s decision loop, discovery only while searching or an
  empty owner, form timeout 30 s, failed-group backoff 30 s, HELLO heartbeat 10 s, stale 30 s, owner
  ROSTER frame (−2) so members link to each other. CONNECTED (chip "Linked · N phones") only with
  ≥ 1 linked DTN phone. Fixed a `ConcurrentHashMap` `!in` (checked values, not keys).

## J.5 App — UI
- Chips: "Active · N nearby", "Linked · N phones", "Connected · N phones", "Needs Android 10" (grey).
  Tapping an ON/BUSY chip opens **link details** (summary, every device with name/role/IP, facts,
  Turn off); OFF turns the link on; PROBLEM fixes/retries (chip look unchanged).
- Peers on our hub show "📡 Online via LoRa hub" (`PeerInfo.hubOnly`).
- Buffer counter, Network buffered list and Lifecycle buffer history: DMs only (`destination_node_id
  != '^all'`); the event log still shows broadcasts.
- QR scanner content sized to the window between the bars (`rememberBetweenBarsHeight`), buttons
  52 dp tall, 72 dp above the navigation bar.

## J.6 Verified
- 140 JVM unit tests (new: `WifiDirectGroupPolicyTest`, `WifiDirectHintsTest`, `RosterParsingTest`,
  chip counts / Android-10 chip in `UiLogicTest`).
- Redmi (Android 16) + COM11 hub with two other phones: chip "Connected · 2 phones", details list
  them, both show "Online via LoRa hub"; a DM went `HUB_LOCAL` and turned Delivered ✓✓ from the
  end-to-end receipt ~4 s later. QR scanner and Wi-Fi Direct details checked by screenshot.
- **Not verified:** multi-phone Wi-Fi Direct groups (only one phone on adb), hub-to-hub LoRa.

## J.7 Known limits / next
- Every phone needs this build: old builds don't PING (the hub drops them every 2 min → roster
  flapping, 22-bundle replays) and still send Wi-Fi Direct invitations.
- Flash v3 to every hub. After a hub reboot each phone shows the system "Connect to device" dialog.
- A phone joined to a hub's SoftAP and in a Wi-Fi Direct group shares one radio channel; some
  phones can't do both at once.

---

# PART K — 2026-10-23: DiMaxProp, Q-learning audit + paper features, saved routing decisions, Discover

User asks: LoRa-to-LoRa test, Wi-Fi Direct links faster ("Searching for phones to link", keep
scanning as is), check Q-learning and add DiMaxProp (the team's paper: Kar, Dalai, Raina, Tiwari,
Shrivastwa, Upadhyay), Message info must show the algorithm a message was actually routed with
(receiver: sender's algorithm), optional Discover button. **DB v3 → v4** (AutoMigration + spec).

## K.1 Q-learning audit (found in code, fixed)
- Tables and ε were never saved: every launch restarted from an empty table at ε = 1 (fully random).
  → `QTableCodec` + `QTableStore` (`filesDir/q_tables_v2.bin`, tagged with `QLearningConfig.stateSchema`,
  loaded once per process, saved 5 s after updates and on stop).
- The flush runs every 4 s and re-drew an ε-greedy action each time, but only one decision per 30 s
  was recorded, so the forward that happened was often not the one rewarded. → the choice is kept
  per (bundle, carrier) for 30 s (`qChoice`).
- Exploration picked uniformly among STORE/FORWARD/WAIT/DROP; WAIT/DROP are no-ops in the
  orchestrator (3 of 4 random picks held, learning split three ways). → `QLearningConfig.actions`
  = STORE, FORWARD.
- Unseen states (all Q = 0) always held, so a fresh table almost never forwarded. → tie-break prior =
  the DiMaxProp rule (forward when the carrier's cost to D is lower). A prior, not an override.
- Q-learning phones sent an empty summary, so no multi-hop information was ever exchanged. → they
  send DiMaxProp's summary.
- SNR is constant on every link here (BLE has none, others are placeholders) → 1 bucket.
- Decisions were recorded for bundles the loop never sends to that carrier (its own / its sender). → skipped.

## K.2 Q-learning per the paper (§VI)
- State: 9 old features + C_DiMaxProp(B,D) (normalised by 2.0, 3 buckets), hop count / 8 (2), the
  relay's reported buffer occupancy (2). Buckets rebalanced: 10,368 states (was 10,800).
- Reward: +1.0 forward that delivered · +0.3 baseline · −0.3 expired (unchanged) plus the immediate
  progress reward η·(C(A,D) − C(B,D)) per FORWARD (unreachable = 3.0, none if neither knows a path),
  applied on the event loop (`OrchestratorEvent.QShaping`) and stored in `shaping_reward`.
- New columns on `forwarding_decisions`: `state_dimaxprop_cost`, `state_hop_count_norm`,
  `state_relay_buffer`, `shaping_reward`.

## K.3 DiMaxProp (`routing/DiMaxPropStrategy.kt`)
- Hop cost `(1 − f) + λ·d_norm` (λ only where a distance exists), Dijkstra, rule: forward if
  C(B,D) < C(A,D) or B is the first hop of A's cheapest path; cold start = bounded exploratory copy
  (as MaxProp). λ = 0 reproduces MaxProp's cost (unit-tested), λ default 0.5, η default 0.2, both in
  Settings → Routing tuning (DataStore; the orchestrator follows them live).
- d_norm = BLE distance (RSSI → log-distance, `RadioDistanceEstimator`) / 50 m. Encounters now carry
  their transport (`NodeEncounterEvent.transport`); only BLE gets a distance (Wi-Fi Direct reports a
  fixed −30, hub −40 / −200). Hub-roster sightings no longer train PRoPHET / MaxProp / DiMaxProp.
- Compact summary (0xD1 marker) with f, d and buffer occupancy, ≤ 36 entries, recognised by its
  marker on receipt. 6th button in the algorithm picker; benchmarks key by name.
- Not done (outside the app): the paper's ONE-simulator study. λ / η "best values" need field or
  simulator runs; the Settings sliders exist so they can be varied per run.

## K.4 Message details
- Every forward saves its decision text in the journal route (`RouteRecord.decision`, `decisionAtMs`;
  JournalCodec fields 16–18, older lines still load). PRoPHET / MaxProp / DiMaxProp `explain()`,
  Q-learning note (ε, Q values, inputs, costs, progress reward), Epidemic, direct / hub / same-hub
  texts. Message info shows it; the live `explainRoute` math was removed from Message info (kept in
  the Network tab's buffered-message view, where "what would happen now" is the point).
- Receiver: DM plaintext byte 0 = sender's algorithm (`AlgorithmCode`, inside the encryption) →
  `RouteRecord.senderStrategy`, shown as "Sender's algorithm". Old phones would show a stray
  character; all phones are being updated.

## K.5 Discover
- `contacts.is_added`; migration marks 1:1 chat partners and QR-verified contacts added. Chat list =
  added contacts (+ any with chat history); "Discovered" = other phones with keys, with Add.
- Discover switch (home, off by default, stays on until turned off). On: broadcast announces as
  before. Off: direct announce to each added contact (TTL 30 min, ≤ 1 / 30 min, immediately on QR
  add / Add / name change); only the addressee pins the keys. Someone who DMs us is auto-added.
  `supersedeAnnounces` is now per (origin, destination).

## K.6 Wi-Fi Direct
- Create-wait 25 s → 8 s, failed-join retry 30 s → 8 s, 1 s ticks while forming / joining, HELLO
  every second for 15 s after joining. Scanning unchanged. Chip: "Searching for phones to link".

## K.7 Verified / not verified
- 168 JVM unit tests (new: DiMaxPropStrategyTest 17, QLearningPaperTest 7, AlgorithmCodeTest 2,
  JournalCodec +2). Instrumented `MigrationTest` (3 → 4) added under `app/src/androidTest`.
- Not verified on devices yet: DiMaxProp / Q-learning behaviour between phones, Discover between
  phones, LoRa-to-LoRa (needs the second hub flashed with v3).

## K.8 Two-hub bench test (2026-10-23 evening)
- Both boards had advertised `DTN-HUB-B734`: `HUB_ID` came from the low bytes of `getEfuseMac()`,
  which are the shared Espressif OUI. Now id = MAC bytes 2..5, SSID = bytes 4..5 (COM11
  `!da64a9b0` / `DTN-HUB-A9B0`, COM3 `!da65bdb8` / `DTN-HUB-BDB8`).
- CAD aborts an ongoing receive: a hub with a queue missed the other's back-to-back frames. It now
  waits while PREAMBLE_DETECTED / HEADER_VALID is latched. LoRa peer table = directly heard hub
  heartbeats only (phone origins used to count as hubs). `[RF]` rx/tx log lines (RSSI/SNR),
  `ping` / `txpow` serial commands, OLED top line = last frame heard.
- Bench (≈30 cm): −9 / 0 / +10 / +22 dBm TX → −30 / −20 / −11 / ≥ −7 dBm RX (saturated), SNR +11 dB,
  ≈21 dB path loss. Link budget at 22 dBm SF9 BW125 ≈ 151 dB (−129 dBm sensitivity); estimated
  ranges in the session notes, not measured (walk test with the OLED readout still to do).
- Cross-hub DM verified: Redmi on hub A → raushan on hub B, Delivered ✓✓ by receipt in ~2 s.
- Phones on the other hub showed "Offline": nothing told a phone they exist. → hub heartbeats carry
  the hub's phones, hubs pass the other hubs' phones to their own as MESH_ROSTER (0x06), the app
  shows them "Online · via LoRa (hub XXXX)" (`MeshTransport.meshPeers`, `PeerInfo.meshHub`).
  Presence only: routing unchanged (the hub already floods every DM it can't hand over itself).
- Known gap: a LoRa flood is sent once per hub; a frame lost to a collision isn't re-flooded when
  the phone re-sends (the hub already holds it), so that DM waits for the next hub session.
