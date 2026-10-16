# DTN Mesh Relay — project steering

Always-on context for this workspace. Keep it short and accurate; update it when
architecture or commands change.

## What this is
An off-grid **delay-tolerant-network (DTN)** messenger. Phones store-carry-forward
self-contained bundles over **BLE** (primary P2P), **WiFi Direct** (fallback), and a
custom **ESP32-S3 LoRa hub** (Heltec WiFi LoRa 32 V3). Hubs flood bundles hub-to-hub
over LoRa. An optional **internet transport** (MQTT / relay) is being added. It is
**independent of Meshtastic** — `docs/meshtastic-ref/` is read-only reference only.

Routing on phones: PRoPHET (RFC 6693), MaxProp (Dijkstra), Epidemic, Double Q-learning.
End-to-end delivery receipts let carriers drop custody copies.

## Layout
- `app/` — Android app, package `com.dtn.mesh`, Kotlin + Compose + Hilt + Room + WorkManager.
  - `service/DtnOrchestrator.kt` — core single-consumer event loop (send/receive/receipts/flush).
  - `receiver/` — transports (`BleTransportAdapter`, `WifiDirectTransportAdapter`,
    `LoRaHubTransportAdapter`), `MultiTransportManager`, `DtnWireCodec` (32-byte header).
  - `queue/` — `MessageQueueManager` (store-carry-forward buffer, Room-backed).
  - `routing/`, `learning/` — strategies + Q-learning. `database/` — Room. `ui/` — Compose.
- `firmware/` — Heltec V3 hub firmware (PlatformIO, single `src/main.cpp`).
- `PROJECT_MEMORY.md` — living design + changelog (Parts A target / B done / C todo / D audit).
- `docs/dtn-hub-architecture.md` — architecture proposal.

## Build & test (Windows PowerShell)
- App build: `./gradlew.bat :app:assembleDebug`
- Unit tests: `./gradlew.bat :app:testDebugUnitTest`  (JVM tests under `app/src/test`)
- Install: `adb install -r app/build/outputs/apk/debug/app-debug.apk`
  (adb lives at `%USERPROFILE%\AppData\Local\Android\Sdk\platform-tools\adb.exe`)
- Firmware build: `pio run` in `firmware/` (`pio` at `%USERPROFILE%\.platformio\penv\Scripts\pio.exe`)
- Firmware flash: `pio run -t upload --upload-port COMxx` (one board at a time)
- Toolchain here: JDK 21, Gradle 8.9 wrapper, AGP 8.7.3, Kotlin 2.0.21, compileSdk/targetSdk 35, minSdk 26.

## Rules / conventions
- Never use `fallbackToDestructiveMigration` for new schema changes — write a real Room
  `Migration` and a migration test. The buffer, chats, contacts and keys must survive upgrades.
- No secrets in source (no hard-coded Wi-Fi passphrases/keys in committed code once settings land).
- Encryption is end-to-end: encrypt at the origin, decrypt only at the destination. Relays,
  hubs and the internet relay must stay payload-agnostic (header-only routing).
- TTL is relative minutes and must only ever count DOWN across hops; never refresh it.
- Match the existing style of the file you edit. Prefer dedicated tools over shell for file edits.
- `docs/meshtastic-ref/**` is vendored reference — do not edit or build it.
