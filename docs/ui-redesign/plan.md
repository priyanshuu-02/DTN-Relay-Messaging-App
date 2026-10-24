# DTN Mesh Relay: UI redesign plan (locked)

Builds on **Architecture v4** (`architecture.md`) and **Visual design** (`visual-design.md`); this plan supersedes architecture §10–11. Unread state and the blocked list are stored in DataStore. The only possible database change is one contacts column for decision 14 (if needed: a real Room migration + migration test). The only protocol addition is the hub Bluetooth beacon. Group invites travel as ordinary encrypted DMs.

## Decisions
| # | Decision |
|---|---|
| 1 | The app opens on **Network** |
| 2 | Queue: the DM segments, plus a separate **Broadcasts & groups** segment. The DM counters don't change |
| 3 | The algorithm switch exists only in **Developer Tools › Routing & Tuning**. The Network card shows the active algorithm (read-only) |
| 4 | Keep "Sent ✓" |
| 5 | About 12 bundled vector icons, no new library |
| 6 | "Clear buffer…" |
| 7 | Switches that would break the mesh are shown as read-only facts |
| 8 | **Available hubs:** each hub announces itself over Bluetooth (firmware beacon): its signal, phones on it, DMs waiting, and how well it hears the other hubs over LoRa. Join connects to that hub's Wi-Fi. Auto-join stays the default, but it picks the strongest hub heard instead of whichever `DTN-HUB-*` Android finds first |
| 9 | **Chat long-press → a simple status sheet:** status, sent/delivered times, and how it travelled: "Received via BLE / Wi-Fi Direct / LoRa hub (hub A9B0) / Internet relay" on incoming messages, "Delivered via …" / "Sent via …" on outgoing ones, plus "Full trace in Developer Tools ›". The full Message page opens from Developer Tools and the Queue |
| 10 | Create group: adding members is optional. Invites go as encrypted DMs and show a Join card |
| 11 | Export: dataset switches, date range, history + Share, and **Export all logs** |
| 12 | Benchmarks: a "messages handled" counter, so there's a real delivery rate. No Run, time range or scenario |
| 13 | **Delete contact** and **Block**. Blocked contacts still get delivery receipts |
| 14 | **One identity per phone:** apps can't read the phone's MAC (Android returns `02:00:00:00:00:00`), so the node id comes from Android's per-app device id (`ANDROID_ID`, hashed with the package name). Clearing data or reinstalling keeps the same id; only a factory reset changes it. The keys are still new after a clear: a newer announce replaces old keys (stale replays ignored); unverified contacts switch automatically with a "safety number changed" note; QR-verified contacts need a re-scan or an Accept tap. Every phone gets a new id once when this ships (re-add contacts) |

## Phases
Each phase ends with a build, unit tests, an install on the Redmi, and a quick check by you. You can safely stop after any phase.

### Phase 1: Shell + logging core (large)
- **Theme and icons:** colours, text styles and shapes in `ui/theme`, plus the bundled icons.
- **Shared components:** status card/pill, setting row, info note, device row, chips/segmented control, search field, empty state, stat tile, timeline item, radio card, value slider, bar chart, confirm dialog.
- **Navigation:** Navigation Compose (version pinned), start tab = Network, bottom nav, top bars, Connection links sheet.
- **Nothing lost:** today's tab contents run inside the new shell until they're replaced.
- **Diagnostics log:**
  - Every line gets a level + source. The last 2,000 lines stay in memory; a 2 MB rotating file keeps the rest.
  - The current logs are routed through it.
  - New log lines from v4 §8 are added, including the "held because…" routing reasons and Wi-Fi Direct time-to-link.
- **Tests:** log ring and file rotation, level counts.

### Phase 2: Network, Chats, Chat, Peers, Contact, Delete / Block (large)
- **Network tab:** link tiles, actions, nearby devices, add by QR.
- **Chats:**
  - search
  - All / Unread / Groups filters (unread uses a last-read time stored in DataStore)
  - pinned Broadcast
  - New message sheet
- **Chat page:**
  - header and reachability banner
  - bubbles with a status line ("Delivered via BLE")
  - composer
  - long-press opens the **simple status sheet** (with the "Received via …" / "Delivered via …" line)
- **Peers:** Contacts | Nearby devices (with Discover).
- **Contact page:** safety number, routing scores, rename.
- **Delete contact:** removes the contact from the list and deletes the chat.
- **Block** (a set of node ids in DataStore):
  - Their DMs are dropped after the receipt is sent.
  - They're never auto-added, and no direct announces go to them.
  - Their group posts are hidden and their announces ignored.
  - Relaying their bundles for others is unchanged.
- **One identity per phone** (decision 14): id derived from `ANDROID_ID` + package (never 0 / `0xFFFFFFFF`), key-change rule and notices, data-safety + privacy docs updated.
- **Tests:** unread counting, search filters, block rules, id derivation (stable, differs per package), key-change rules.

### Phase 3: Queue + message pages (medium)
- **Queue segments:** Pending / Forwarded / Delivered / History / **Broadcasts & groups** (TTL left, phones reached; announces and receipts excluded).
- **Queued message page:** stat tiles and live routing math.
- **Message page** (full trace):
  - status
  - route-path chain
  - saved routing decision
  - journey timeline
  - details, Copy all, Export event details
- **Tests:** queue segmentation; check that the broadcast segment never shows control traffic.

### Phase 4: Groups, identity, settings, link pages, hub beacon (large)
- **Groups:** Groups list, group page, Create group (optional members → invite DMs → Join card in the chat). The group name is capped so the invite fits one LoRa frame.
- **My Identity, First launch.**
- **Settings:**
  - Privacy (Discover, Blocked contacts / Unblock)
  - Data & storage
  - About
- **Relay Configuration:** read-only facts and Test connection.
- **Link pages** ×4: read-only facts, Refresh scan, a Wi-Fi Direct rescan hook, last-scan times.
- **Firmware:**
  - Each hub sends a Bluetooth beacon carrying its hub id, firmware version, phones on it, DMs waiting, and the other hubs it hears with their LoRa signal (packed into one legacy advert).
  - Flash both boards.
  - Update `wire-protocol.md`.
- **App, hub beacon:**
  - The existing Bluetooth scan reads the beacons and lists **Available hubs**, sorted by signal with the best one marked (phones, DMs waiting, LoRa link to the other hubs).
  - Join requests that hub's exact Wi-Fi network; auto-join requests the strongest hub heard.
- **Tests:** invite detection and size, beacon parsing.

### Phase 5: Developer Tools (medium)
- **Hub page:** the Developer Tools entry screen that lists the pages below.
- **Event Log:** search, filters, coloured-stripe cards, Export events.
- **Routing Engine.**
- **Buffer History.**
- **Routing & Tuning:** the only algorithm switch, plus λ / η, reset to defaults, formulas.
- **Benchmarks:** table + bar chart, messages-handled counter.
- **Export Data:**
  - dataset switches and date range
  - history + Share (adds the export folder to the app's file sharing)
  - **Export all logs**: the diagnostics file, engine console, journal, and this app's own logcat
- **Console:** levels, source filter, search, copy, clear view.
- **Tests:** export range and dataset selection, benchmark delivery rate.

### Phase 6: Clean-up + release (small)
- **Remove the old screens:** delete the old `MainScreen` tabs and dialogs, after a checklist confirms every old feature has a new home (v4 §6–7).
- **Update docs:**
  - steering rules: Queue broadcasts segment, blocking, diagnostics log, chat simple status, hub beacon
  - `PROJECT_MEMORY.md`
- **Release check:**
  - full test run
  - debug + R8 release builds
  - install on all phones and walk through every page

### Phase 7: On-device review (after Phase 6)
- Install the final debug build on the attached phone (`adb install -r`, keeps data).
- Walk every page with adb (taps from `uiautomator dump` bounds), take a screenshot of each (`screencap`), and compare it with `visual-design.md`, `architecture.md` and the decisions: layout, text, spacing, clipped or overlapping text, empty states, dark-on-dark, broken navigation, missing routing info.
- Fix what's off, rebuild, reinstall, and re-shoot the pages that changed. Screenshots go to `_diag/screens/` (never committed); a before/after list goes in the report.

### Phase 8: Fallback after 5 s + group receipts (running)
- A DM still queued 5 s after buffering also goes over the LoRa hub and the internet relay (whichever is on); a failed BLE write tries Wi-Fi Direct at once.
- Group receipts: members send an encrypted DM receipt to the origin; bubbles show "Received by n of m", the sheet and Message page list each member (time + path).

### Phase 9: Multi-hop routing audit (all 6 algorithms)
- JVM simulation with the real strategy code: A → B → C → D → E, candidates F / G / H. Each algorithm must deliver over several hops and pick the better carrier; no ping-pong, TTL only counts down, hop limit, receipts clear carrier copies. Fix what fails.

### Between Phase 9 and 10: share the APK, then wait
- After Phase 9: build, copy `app-debug.apk` to `dist/` on the laptop and to the user's phone (`/sdcard/Download/`). No testing.
- The user tries it on other phones and sends feedback; Phase 10 starts only after that (feedback fixes first).

### Phase 10: Final device round (replaces the skipped Phase 7 part D)
1. Install on the user's phone; screenshot every page; fix; rebuild; reinstall (only the user's phone is needed for this).
2. Final build → push the APK to the user's phone (`/sdcard/Download/`) and keep a copy on the laptop; the user shares it with Vrishank, who installs it over his current app (same signing key, data kept). Wait for his confirmation.
3. LoRa tests with both phones on different hubs: DM both ways, store-and-forward, group message with per-member receipts, fallback.
