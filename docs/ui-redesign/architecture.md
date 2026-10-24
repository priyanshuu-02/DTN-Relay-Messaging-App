# DTN Mesh Relay: UI architecture v4 (all 24 mockups, decisions applied)

This version applies your answers. It also adds Delete contact / Block and a bigger diagnostics log. Three questions are still open (section 11). No code has been written yet.

## 1. Ground rules
- **Nothing is dropped.** Every current feature stays. Research views move to Settings › Developer Tools. The active algorithm, route paths and routing math stay visible in the main screens (section 7).
- **Statuses keep their current sources:**

  | Status of | Comes from |
  |---|---|
  | Messages | `deliveryState()` |
  | Links | `transportChip()` / `connectionHeadline()` |
  | Who is on each link | `LinkInfo` |
  | Reachability | `PeerInfo` |

- **Project rules stay**, with one change (Queue):
  - Message info shows the decision saved at forward time.
  - "Delivered" only comes from a direct link or a receipt.
  - Logs never contain message text or keys.
- **No switches that would break the mesh.** Those settings show as read-only facts.

## 2. Navigation
- **First launch** (no display name yet) opens the main shell **on the Network tab**.
- **The shell:**
  - Top bar: title, Groups, Settings, avatar (→ My Identity).
  - Status card. Tapping it opens the **Connection links** sheet.
  - Bottom nav: **Network · Chats · Queue · Peers**.
- **Network tiles:** a tap keeps today's behaviour. The arrow or a long-press opens the link's page.
- **Everything else** is a page with a back arrow. Short choices are bottom sheets.

## 3. Main screens

### Status card + Connection links sheet
- **Status card:**
  - Network tab: state, links in use, node and buffer, plus the **active algorithm** (read-only).
  - Chats tab: link state + "N queued" (or "No live link").
  - Queue and Peers tabs: a small pill.
- **Connection links sheet:**
  - The 4 links, each with its state, purpose, an on/off switch and a › to its page.
  - Connect/Disconnect, Sync, **Clear buffer…** (asks to confirm).
  - The warning line and the long-press hint.

### Network
- Status card.
- 4 link tiles.
- Connect/Disconnect, Sync, Clear buffer….
- **Nearby devices:** how each is reachable, RSSI, ⋮ (Open chat / Info / Rename / Delete / Block), refresh.
- Add contact by QR.
- The algorithm can only be switched in Developer Tools.

### Chats + Chat
- **List:**
  - Search, plus filters All / Unread / Groups.
  - Each row shows the status of the last sent message (Queued 🕓 · **Sent ✓** · Delivered ✓✓ · Expired) and an unread badge.
  - Broadcast is pinned at the top. ✎ opens a New message sheet.
  - Blocked contacts don't appear.
- **Chat:**
  - Header: name, "Last seen just now · via BLE", ⋮ (Contact info / Rename / Safety number / Delete / Block).
  - Reachability banner, shown only while the contact is unreachable.
  - Day separators and bubbles, with status + last link under each sent message (e.g. "Delivered via BLE").
  - Join group cards for invites.
  - Composer without 📎. A byte counter appears near the 223-byte limit.
  - Long-press a bubble to open the Message page.

### Queue + Queued message
- **DM segments:**
  - Pending (not handed on yet)
  - Forwarded (handed on, waiting for the receipt)
  - Delivered
  - History (how each one left)

  The "N queued" counters still count DMs only.
- **New segment: Broadcasts & groups.** This is the separate section you asked for. It lists user broadcasts and group messages this phone is spreading:
  - text (if readable here), group name, age
  - TTL left, and how many phones it has reached
  - status Spreading

  Announces and receipts stay out (they're control traffic and go to the logs).
- **Each DM row:** to/from, preview, age, hops, TTL left, forwards, next hop with its link, status.
- **Queued message page:**
  - TTL, time in the buffer, hops, forwards, why it's here
  - **live routing math**: my score, best next hop, path map, candidate carriers, formulas
  - a link to the Message page

### Peers + Contact
- **Segments:** Contacts | Nearby devices.
  - Nearby devices has the Discover switch, announced phones with Add, and phones in range.
  - Badges: Nearby / Wi-Fi Direct / LoRa hub / via LoRa (hub BDB8) / Internet / Offline.
  - The ＋ button: Scan QR / Upload QR / My QR.
- **Contact page:**
  - reachability
  - safety number, and whether it's verified
  - Rename
  - **Routing:** score, encounters, contact time, deliveries, RSSI → distance
  - **Delete contact…** and **Block…** (section 5)

### Message page (mockup 16 + today's Message info)
Opened from a bubble, the Queue, the Event Log or Buffer History. Sections:
1. **Status** + explanation
2. **Text** (own and received messages only)
3. **Route path** (hop by hop, link per hop, hops, handed over by / to)
4. **Routing decision** (saved algorithm + numbers; the sender's algorithm on received DMs)
5. **Journey timeline**
6. **Details** (ID, source, destination, link, initial TTL, hops, status), with Copy all and Export event details

How it opens from a chat is question 11.1.

## 4. Other pages

### Link pages
| Page | Contents |
|---|---|
| Bluetooth | Switch, "Active · 1 nearby · last scan just now". Nearby phones (name, last seen, RSSI, ~distance, Nearby / Last seen, ⋮). Refresh scan. Scanning and advertising shown as facts. |
| Wi-Fi Direct | Switch, "Searching for phones to link" or the group role. Linked phones (owner/member, address), group name. Refresh scan (new). "Discover" / "be found" shown as facts. |
| LoRa hub | Switch, "Not joined · tap to retry" / "Connected". Connected hub (id, Wi-Fi name), phones on this hub, phones on other hubs over LoRa. **Available hubs** with signal, phone count, Join, and Retry scan (how it scans: question 11.2). |
| Internet relay | Switch. Status: Connected · N contacts online / Connecting / Disconnected + last error. Relay server card → Relay Configuration. "When this is useful" note. |

### Relay Configuration (same page as Settings › Relay server)
- Optional-relay note, broker address, Save, **Test connection** (new).
- **Read-only facts:**
  - **Connection type:** the public broker rotates TLS 8883 → TCP 1883 → WebSocket 8884; a custom URL is used as written.
  - **QoS 1.**
  - **Auto-reconnect** every 1–15 s while the relay is on.

  None of these are new: the relay already works this way. Only the page showing them is new.
- **Dropped:** the timeout field.

### Groups + Create group
- **Groups:** list and group page as before (invite QR + share, channel, members seen, Leave).
- **Create group:**
  - name, capped so the invite fits one LoRa frame
  - **Add members (optional):** contacts with keys only
  - info note, Create / Cancel
- **Invites:**
  - Selected contacts get the invite as an encrypted DM and see a Join group card. They tap to join; they're never added automatically.
  - With no one selected, it works like today: share the QR or image.

### My Identity, First launch, Settings
- **My Identity:** QR, node ID + copy, safety number + copy, Share my QR, Scan QR, Upload QR.
- **First launch:** name + Continue.
- **Settings:**
  - Profile
  - Connectivity: Internet relay, Relay server ›
  - Privacy & data:
    - Privacy & encryption › (Discover, **Blocked contacts** with Unblock)
    - Groups ›
    - Data & storage › (buffer use, Clear buffer…)
  - Advanced: Developer Tools ›
  - About

## 5. Delete contact and Block (new)
Both are available from the Contact page, the chat ⋮ menu, and the ⋮ menus in Peers and Network. Each asks to confirm.

| | Delete contact… | Block… |
|---|---|---|
| Chat list, Contacts | Removed | Removed, hidden everywhere |
| Chat history | Deleted | Kept (hidden); deleted only if you also choose Delete |
| Their new DMs | Arrive and add them back (as today) | Dropped on arrival: not shown, not stored, never auto-added |
| Their announces / group posts | Shown as usual (they appear in Nearby devices) | Ignored / hidden |
| Our direct announces to them | Stop (they're no longer added) | Stop |
| Receipts for their DMs | — | Question 11.3 |
| Relaying their bundles for others | Unchanged | Unchanged: relays route on the header only |
| Undo | Add them again (QR / Add) | Settings › Privacy › Blocked contacts › Unblock |

- **Storage:**
  - Delete reuses the existing "added" flag and the existing delete-conversation call.
  - Block is a set of node ids in DataStore.
  - No Room change.

## 6. Developer Tools
| Page | Contents | Today it's in |
|---|---|---|
| Event Log | Search (new), filters All / Flow / Delivery / ACK / Issues, coloured cards → Message page, Export events | Lifecycle tab |
| Routing Engine | Encounters, per-algorithm calculations, per-peer score table, network summary | Log + Network tabs |
| Buffer History | → Message page | Lifecycle tab |
| Routing & Tuning | **The only algorithm switch**: 6 cards, λ / η sliders, Benchmarks ›, Reset to defaults (PRoPHET, λ 0.50, η 0.20), Save / Cancel, formulas and constants. The choice resets on restart; λ / η are saved | Network tab + Settings |
| Benchmarks | Per algorithm: deliveries, average delay, overhead, hops, **delivery rate** (new "messages handled" counter), bar chart. "Need more data" note. Reset | Network tab |
| Export Data | Dataset switches, date range, Generate, history + Share, **Export all logs** (section 8) | Log tab |
| Console | Diagnostics log: All / Warnings / Errors with counts, **Source filter** (the mockup's Filter button), search, Copy output, Clear view (screen only) | Log tab |
| Relay Configuration | Same page as Settings › Relay server | Settings dialog |

**Dropped:**
- Benchmarks: Run benchmark, time range, scenario
- Export: the CSV/JSON switch, network telemetry, size estimate

## 7. Essential routing information
| What | Main screens | Detail |
|---|---|---|
| Active algorithm | Network status card (read-only) | Developer Tools › Routing & Tuning (switch) |
| Route path | Queue rows (next hop + link), Message page › Route path | Event Log / Buffer History → Message page |
| Saved decision + numbers | Message page › Routing decision (+ sender's algorithm) | — |
| Live routing math | Queue › Queued message | Developer Tools › Routing Engine |
| Per-peer scores | Contact page › Routing | Developer Tools › Routing Engine |
| Algorithm comparison | — | Developer Tools › Benchmarks |

## 8. Diagnostics log (new: more logs)
**Format:**
- Each line has a time, a level (INFO / WARN / ERROR) and a source.
- It's kept in memory (last ~2,000 lines) and in a capped, rotating file on the phone (~2 MB). It survives restarts and can be exported.
- It never holds message text or keys.

**New lines, by source:**

| Source | What it records |
|---|---|
| System | App start (version, node id, Android version), permissions granted/denied, Bluetooth / Wi-Fi / location on or off, battery-saver and background limits, service start/stop |
| Bluetooth | Scan start/stop and failures (error code), advertising failures, connect/disconnect (status code, time to connect), MTU, write failures |
| Wi-Fi Direct | Role changes (searching / creating / owner / member), which group it picked and why, group created/joined/failed (reason code), **time to link**, members joined/left |
| LoRa hub | Hub Wi-Fi found/lost, connect/fail, hub id, roster and other-hub changes, ping failures, reconnect back-off, hub scan results |
| Relay | Endpoint tried (IPv4/IPv6, port), connected / failed (reason), contacts coming online/offline, publish timeouts |
| Routing | For each flush and each peer: messages offered / sent / held, **and why each was held**: algorithm said hold, already sent this hub session, sent under 30 s ago, destination reachable another way, came from that peer |
| Buffer | Stored, evicted (buffer full), expired, hop limit, duplicates ignored, fragments (n of m, timeouts) |
| Receipts | Generated, sent, received, receipt check failed, carrier copy cleared |
| Crypto | Decryption failures (unknown key / bad tag), bad announce signatures, ignored stale or self announces |
| Presence | Each peer online/offline, with the link and the reason |
| Q-learning | Table load/save, ε, episodes (decisions already go to the decisions table) |
| Timing | Flush duration, and time from send to Delivered per link |

**Export all logs** (Export Data) writes these into one export folder:
- the diagnostics file
- the engine console
- the message journal
- this app's own Android system log (logcat, our process only)
- optionally the research CSVs

## 9. Code structure and new state
- **Navigation:** Navigation Compose, already pulled in by hilt-navigation-compose 1.2.0; it should be declared explicitly, with the version pinned. One NavHost, tabs as a nested graph, start = `network`. Routes:
  - `chat/{key}`, `message/{id}`, `queued/{id}`, `contact/{node}`
  - `groups`, `group/{channel}`, `group/new`, `identity`
  - `settings/{page?}`, `relay`, `dev/{page?}`, `transport/{kind}`
  - plus the `links` sheet
- **ViewModel:** `DtnViewModel` stays the one shared source.
- **Packages** under `ui/`: `shell`, `components`, `theme`, `network`, `chats`, `queue`, `peers`, `groups`, `identity`, `settings`, `devtools`. `MainScreen.kt` (~2,400 lines) gets split along these lines.
- **Icons:** about 12 bundled vector icons. No new dependency.
- **New state, no Room schema change:**
  - unread times (DataStore)
  - blocked set (DataStore)
  - searches
  - the diagnostics log (levels, sources, file)
  - the benchmark counter
  - export options + all logs
  - invite-DM detection
  - routing reset
  - relay test
  - Wi-Fi Direct rescan and last-scan times
  - hub scan
- **Steering rules to update when implemented:**
  - the Queue "Broadcasts & groups" section
  - blocking behaviour
  - diagnostics log format

## 10. Plan
Each phase ends with an APK you can install. Nothing that works today breaks along the way.
1. **Shell + logging core:**
   - theme, icons and components
   - navigation, top bar, status card, bottom nav, Connection links sheet
   - the diagnostics log and the new log lines
2. **Network, Chats + Chat, Peers + Contact,** and Delete contact / Block.
3. **Queue** (including Broadcasts & groups), the Queued message page, the Message page.
4. **Groups + Create group, My Identity, First launch, Settings** (with Blocked contacts), Relay Configuration, and the 4 link pages with the hub scan. Hub firmware too, if 11.2 = B.
5. **Developer Tools:** all 8 pages, including Export all logs and the Console source filter.
6. **Clean-up:**
   - remove the old screens
   - unit tests for queue segments, unread, search, blocking, invite detection, log levels, export range
   - update docs and steering
   - final install on all phones

## 11. Open questions
1. **Message page from a chat:** you answered "no" to the routing sections being collapsed. Which did you mean?
   - **a)** The full page with everything expanded.
   - **b)** A simple status in the chat, with the full trace only in Developer Tools (the mockup's note).
2. **Available hubs:** how should the app find them?
   - **A. In-app Wi-Fi scan.** Android needs precise location permission for Wi-Fi scan results on every version, including 13+, and Location must be switched on. Today the app asks for location only up to Android 12L. This means a new location prompt on every phone and a privacy-policy note. Scans are limited to 4 per 2 minutes.
   - **B. Hubs announce themselves over Bluetooth.** Each hub sends a small Bluetooth beacon with its id, the phones on it and the hubs it hears. The app's existing Bluetooth scan lists them with signal and phone count, with no new permission. It needs a firmware update on both hubs. **(recommended)**
   - **C. Android's own network picker.** No new permission or code, but it's the system's dialog, not an in-app list.
3. **Block:** should the phone still send delivery receipts for a blocked person's DMs?
   - **With receipts:** they see Delivered, and other phones stop carrying the copies.
   - **Without:** they only see Sent ✓, and copies keep travelling until they expire. **(recommended: with receipts)**
