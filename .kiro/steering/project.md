# DTN Mesh Relay — project steering

Always-on context for this workspace. Keep it short and accurate; update it when
architecture or commands change.

## What this is
An off-grid **delay-tolerant-network (DTN)** messenger. Phones store-carry-forward
self-contained bundles over **BLE** (primary P2P), **WiFi Direct** (fallback), and a
custom **ESP32-S3 LoRa hub** (Heltec WiFi LoRa 32 V3). Hubs flood bundles hub-to-hub
over LoRa. An optional **internet relay** (MQTT, public HiveMQ by default) reaches contacts
anywhere. It is **independent of Meshtastic** — `docs/meshtastic-ref/` is read-only reference only.

Routing on phones: PRoPHET (RFC 6693), MaxProp (Dijkstra), Epidemic, Double Q-learning.
End-to-end delivery receipts let carriers drop custody copies.

## Layout
- `app/` — Android app, package `com.dtn.mesh`, Kotlin + Compose + Hilt + Room + WorkManager.
  - `service/DtnOrchestrator.kt` — core single-consumer event loop (send/receive/receipts/flush).
  - `receiver/` — transports (`BleTransportAdapter`, `WifiDirectTransportAdapter`,
    `LoRaHubTransportAdapter`), `MultiTransportManager`, `DtnWireCodec` (32-byte header).
    Wi-Fi Direct = invitation-free groups (Android 10+): `WifiDirectGroupPolicy` (pure election)
    + `WifiDirectHints` (group credentials in the BLE advert). Each transport publishes a
    `LinkInfo` (summary + peers) for the chip details sheet. `MeshTransport.reachesDirectly`
    (BLE / Wi-Fi Direct / hub-as-target) vs `isRadioReachable` (also phones on our hub's roster).
  - `queue/` — `MessageQueueManager` (store-carry-forward buffer, Room-backed; also persists the
    chat row's delivered / expired / dropped status).
  - `service/MessageLifecycleLog.kt` — persistent message journal (events + per-message routes,
    `filesDir/message_journal_v1.log`). Only user DATA goes in it; control traffic → engine console.
  - `ui/MessageStatus.kt` — `deliveryState()`, the ONE status model for bubbles, routes and Message info.
  - `routing/`, `learning/` — strategies + Q-learning. `database/` — Room. `ui/` — Compose.
- `firmware/` — Heltec V3 hub firmware (PlatformIO, single `src/main.cpp`), currently v3
  (`DTN_FW_VERSION` in `platformio.ini`). Every hub must run v3: v2 hubs send nothing to phones.
- `PROJECT_MEMORY.md` — living design + changelog (Parts A target / B done / C todo / E–J sessions).
- `docs/dtn-hub-architecture.md` — architecture proposal. `docs/play-store/` — store listing,
  privacy policy, data safety, release checklist.

## Build & test (Windows PowerShell)
- App build: `./gradlew.bat :app:assembleDebug`
- Unit tests: `./gradlew.bat :app:testDebugUnitTest`  (JVM tests under `app/src/test`)
- Install: `adb install -r app/build/outputs/apk/debug/app-debug.apk`
  (adb lives at `$env:USERPROFILE\AppData\Local\Android\Sdk\platform-tools\adb.exe`)
- Release (Play): `./gradlew.bat :app:bundleRelease :app:assembleRelease` — R8-minified, signed with
  the upload key from the git-ignored `keystore.properties` + `keystore/dtn-upload.jks` (unsigned if absent).
- R8 check on a phone: `./gradlew.bat :app:assembleStaging` → `com.dtn.mesh.staging`, debug-signed,
  installs next to the debug app (own data). Uninstall it after testing.
- Firmware build: `pio run` in `firmware/` (`pio` at `$env:USERPROFILE\.platformio\penv\Scripts\pio.exe`)
- Firmware flash: `pio run -t upload --upload-port COMxx` (one board at a time)
- Toolchain here: JDK 21, Gradle 8.13 wrapper, AGP 8.13.2, Kotlin 2.0.21, compileSdk/targetSdk 36
  (Play's requirement since 31 Aug 2026), minSdk 26.

## Rules / conventions
- Never use `fallbackToDestructiveMigration` for new schema changes — write a real Room
  `Migration` and a migration test. The buffer, chats, contacts and keys must survive upgrades.
- No secrets in source (no hard-coded Wi-Fi passphrases/keys in committed code once settings land).
- Encryption is end-to-end: encrypt at the origin, decrypt only at the destination. Relays,
  hubs and the internet relay must stay payload-agnostic (header-only routing).
- TTL is relative minutes and must only ever count DOWN across hops; never refresh it.
- "Delivered" only from a link that reaches the destination itself (BLE, Wi-Fi Direct, hub as
  target) or an end-to-end receipt — never from a relay PUBACK, a hub flood, or a hand-off to a
  phone on the same hub (`SendPath.HUB_LOCAL`).
- Buffer counter / list / history show only DMs: anything addressed to `^all` (announces,
  broadcasts, group messages) spreads until its TTL and stays out of them (the event log keeps it).
- Announces issued before this phone's identity was created (`DtnIdentity.createdAtMs`) are
  ignored — they're this phone's old identity from before a data clear.
- Never block the main thread on network I/O (the MQTT publish-on-disconnect ANR). Never log
  message text. Never commit `keystore/`, `keystore.properties` or `dist/`.
- Match the existing style of the file you edit. Prefer dedicated tools over shell for file edits.
- `docs/meshtastic-ref/**` is vendored reference — do not edit or build it.
