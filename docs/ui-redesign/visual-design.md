# DTN Mesh Relay: visual design (companion to architecture v4)

These are the look and layout rules for every page, taken from the 24 mockups. Pages the mockups don't show use the same style.

## 1. Design system

### Colours
| Role | Colour | Used for |
|---|---|---|
| Background | `#F4F7FC` (pale blue-grey) | Screen background |
| Card | `#FFFFFF`, 1 dp border `#E6ECF5` | All cards and list groups |
| Primary | `#1769FF` | Main buttons, links, selected chips and tabs, FAB, sent bubbles |
| Primary soft | `#E8F0FF` | Selected pill, info notes, icon squares |
| Text | `#0F1B33` titles · `#5B6B82` subtitles · `#94A3B8` hints | |
| Green | `#22A447`, soft `#E7F6EC` | Online, Nearby, Delivered ✓✓, Active |
| Orange | `#F59E0B`, soft `#FFF4E0` | Queued, Searching, Waiting, warnings |
| Red | `#E5484D`, soft `#FDECEC` | Not joined, Disconnected, errors, Clear / Delete / Block |
| Purple | `#7C5CFF`, soft `#EFEAFF` | Sync button, groups, η slider |
| Grey | `#9AA4B2`, soft `#EEF1F5` | Off, Offline, "Internal event" |

**Algorithm colours** (used in tables, charts and chips):

| Algorithm | Colour |
|---|---|
| PRoPHET | blue |
| Stable | green |
| MaxProp | orange |
| DiMaxProp | purple |
| Epidemic | red |
| Q-learning | teal |

**Avatars** are colour circles with a white initial. The colour is fixed per contact (picked from their node id): blue, green, pink, purple, light blue or teal. Groups use a lavender circle with a group icon.

### Text styles
| Style | Size / weight | Example |
|---|---|---|
| App title | 20 sp bold, navy | "DTN Mesh Relay" |
| Page title | 22 sp semibold | "Settings" |
| Page subtitle | 13 sp, grey | "Developer Tools" |
| Section header | 12 sp semibold, uppercase, blue, letter-spaced | "PROFILE" |
| Row title | 16 sp semibold | "Vrishank" |
| Row subtitle | 13 sp, grey | "Seen just now · Bluetooth" |
| Small / meta | 12 sp | times, counts |
| Monospace | 12–13 sp | MSG ids, node ids, safety number, console |

### Shapes, spacing, icons
- **Corners:** cards and tiles 16 dp, buttons 12 dp, fields 12 dp, sheets 24 dp (top). Chips, pills and search fields are fully rounded. The FAB is a 56 dp circle.
- **Spacing:** 16 dp page padding, 16 dp inside cards, 12 dp between cards. List rows are 64–72 dp tall. Main buttons are 52 dp tall and full width.
- **Depth:** cards are flat with a thin border, or a very soft shadow. No gradients. The top bar is light and sits on the background.
- **Icons:** outlined, 24 dp. In lists they sit in a 40 dp soft-blue rounded square. Status dots are 8 dp.

### Shared components
- **Top bar (main tabs):**
  - "DTN Mesh Relay" on the left.
  - Groups icon, Settings gear, and a 36 dp blue avatar circle (My Identity) on the right.
- **Top bar (inner pages):**
  - ← arrow, title, and an optional grey subtitle or breadcrumb underneath.
  - An optional action on the right: ⓘ, filter, ⋮ or ✕.
- **Status card:** a white card with a coloured pill ("● Online · Store & forward") and a meta line ("via BLE · Node 97ef4bf4 · DIMAXPROP · Buffer 0"), plus a chevron. The pill colour follows the state: green on, orange starting, red problem, grey off.
- **Bottom nav:** white, with a thin top line and 4 icon + label items. The selected item is blue with a soft-blue pill behind its icon.
- **Buttons:**
  - Primary: blue fill, white text.
  - Tonal: lavender fill, purple text.
  - Outlined: blue border.
  - Destructive: red outline with red text and icon.
- **Chips:** filter chips are blue with white text when selected, and soft grey-blue with navy text when not. Segmented controls are 2–3 equal parts in a grey track; the selected part is blue.
- **Search field:** soft grey-blue pill with a 🔍 icon and grey placeholder.
- **Info notes:** rounded boxes with an icon on the left.
  - Blue ⓘ for info.
  - Amber ⚠ for optional or caution.
  - Red ⚠ for danger.
- **Setting row:** an icon square, a title and subtitle, then a switch or › on the right. Rows are grouped in white cards under section headers.
- **Device / contact row:**
  - 44 dp avatar, with the name and a subtitle beside it.
  - On the right, a status chip and a ⋮ menu.
  - Status chips: "● Nearby" green, "● Last seen" grey, Wi-Fi icon "Wi-Fi Direct", tower icon "LoRa hub", "📡 via LoRa (hub BDB8)", "🌐 Internet", "● Offline" grey.
- **Empty state:** an icon in a soft circle, a 16 sp title and a grey sentence underneath.
- **Confirm dialog:** a Material dialog. The destructive action is in red text.

## 2. Pages

**First launch.** White page with "DTN Mesh Relay" at the top ("Relay" in blue), then an antenna-and-nodes illustration, then "Welcome to DTN Mesh Relay" in large type (the app name in blue). Below that, a grey explanation, a "Display name" field with a person icon, a full-width blue "Continue →" button, and a small ⓘ "You can change this later in Settings".

**Network** (start tab):
- Status card.
- A card with the **4 link tiles in a row**. Each tile has a 48 dp circle icon whose tint follows its state (BLE green, Wi-Fi Direct orange, LoRa red, Internet grey), the label with an arrow ("BLE →"), and a coloured state line.
- Under the tiles, three equal buttons: **Disconnect** (blue fill; "Connect" when off), **Sync** (lavender), **⚠ Clear buffer…** (red outline).
- A "Nearby devices · 2" card with a refresh icon and device rows.
- At the bottom, a soft-blue "Add contact by QR ›" row.

**Connection links sheet.**
- A bottom sheet with a drag handle and the title "Connection links" (← and ✕).
- 4 large cards: an icon circle, the link name, a coloured "● state", a grey purpose line, and a switch and › on the right.
- Then the three buttons, a red caution note ("Clearing the buffer removes queued messages…") and a blue tip note.

**Link pages** (Bluetooth, Wi-Fi Direct, LoRa hub, Internet relay). All four share one layout:
1. A header card with the big icon, name and switch.
2. A status card with a coloured dot, a bold state and a grey "last scan just now". It's tinted red when Not joined or Disconnected.
3. A list card ("Nearby devices · 2" / "Available hubs · 2" with Retry scan) of rows: signal bars or RSSI, "Seen 2 min ago", and a blue **Join hub** button on hub rows.
4. A "behaviour" card with read-only facts (Scanning ✓, Advertising ✓).
5. A full-width blue **Refresh scan** button.
6. A blue ⓘ note at the bottom.

The Internet relay page instead shows a "Relay server" card with a "Manage relay settings ›" row.

**Chats:**
- Status card with two rows: "● Online · Store & forward ›" and "🗄 No live link · 3 messages queued ›".
- A search pill, then the chips **All · Unread · Groups**.
- Rows: avatar, bold name, a grey last message, the time on the right, and below the time either a coloured status ("🕓 Queued" orange, "✓✓ Delivered" green, "✓ Sent" grey) or a blue unread badge.
- A blue ✎ FAB at the bottom right. "📢 Broadcast to All" is pinned as the first row.

**Chat:**
- **Header:** ←, avatar, name, and a grey "Last seen just now · via BLE", with ⋮ on the right.
- **Banner:** an amber banner, only when the contact is unreachable, with action buttons.
- **Messages:** a centred "Today" separator, then the bubbles.
  - Incoming: light grey, left-aligned.
  - Outgoing: blue with white text, right-aligned. 18 dp corners with a small tail, time in small grey underneath.
  - Under each sent bubble, a coloured status line ("✓✓ Delivered via BLE" green, "🕓 Queued" orange).
  - A group invite shows as a card with a group icon, the name and a blue **Join group** button.
- **Composer:** a grey rounded "Message" field and a 48 dp blue round send button. A "142/198 bytes" counter appears near the limit.

**Message page.**
- A blue ⓘ note: "Forwarding is not final delivery…".
- **Status card:** a big coloured pill (DELIVERED ✓✓) and an explanation.
- **Route path card:** a horizontal chain of chips, "You → hub A9B0 → raushan", with the link name on each arrow (LoRa, BLE) and "2 hops".
- **Routing decision card:** the algorithm chip (in its algorithm colour), "saved at 21:43", and the numbers in a grey monospace box.
- **Journey card:** a vertical timeline. Each step has a coloured circle icon (Created blue, Buffered amber, Forwarded orange, Receipt green, Cleared grey), a bold title, the time, a detail line, and a pill on the right ("Internal event" grey, "Delivered" green).
- **Details card:** label/value rows (IDs in monospace) with "Copy all" in the corner.
- Full-width outlined **Export event details** at the bottom.

**Queue:**
- Status pill + "3 messages queued ›".
- A scrollable chip row: **Pending (3) · Forwarded · Delivered · History · Broadcasts & groups**.
- Message cards: avatar, "To: Nisha" in bold, a grey preview, the age, a grey "Hops 1 · TTL 239m · → hub A9B0 (LoRa)" line, an orange "🕓 Waiting for a route" (or a coloured state) at the bottom right, and ⋮.
- Broadcast cards show a lavender group/📢 avatar and "Reached 3 phones · Spreading".
- Empty state: "No more pending messages".

**Queued message:**
- Title "Queued message" with the MSG id in monospace.
- An urgency pill (QUEUED blue, TIME SENSITIVE orange, EXPIRING SOON red).
- A 2×2 grid of stat tiles (TTL LEFT, IN BUFFER, HOPS, FORWARDS), each a soft-tinted box with a small grey label and a big bold number.
- A "Why it's here" text.
- **Routing math** card:
  - two big tiles (MY SCORE, BEST NEXT HOP)
  - a path-map drawing (YOU → peers, the chosen one in green)
  - candidate-carrier rows with score bars
  - an expandable "Formulas & constants" in monospace
- A "Message details ›" link.

**Peers:**
- Status pill under the app title, then the large title "Peers".
- A segmented control: **Contacts | Nearby devices**.
- A search pill, then contact cards with status chips and ⋮ (Open chat, Info, Rename, Delete, Block).
- Nearby devices starts with a **Discover** setting row (switch), then "Discovered" rows with a blue **Add** button.
- Blue add-person FAB.

**Contact page:**
- A centred 72 dp avatar, the name, a status chip and the node id in monospace.
- Cards:
  - **Safety number:** a 4×4 monospace grid, with a "Verified ✓" green pill or a "Verify by QR" button.
  - **Routing:** stat tiles for score, encounters, contact time, deliveries and RSSI → distance.
  - **Actions:** Rename; **Delete contact** and **Block** as red text rows.

**Groups:**
- ← "Groups" with a ⊞ add icon on the right.
- A search pill and group cards: lavender avatar, name, "3 seen · ● Active", ›.
- Fixed at the bottom:
  - a full-width blue **＋ Create group**
  - two outlined buttons: **Scan invite QR** and **Upload invite**
  - a grey hint

**Create group:**
- A big lavender group illustration with a blue ＋.
- A "Group name" field.
- An "Add members (optional)" card with "Selected (2)" in blue, a search pill, and contact rows with checkboxes.
- A blue ⓘ note, a blue **Create group** button and a text "Cancel".

**Group page:**
- The invite QR in a white card, with Share.
- Channel and "Members seen" rows.
- A red **Leave group** text row.

**My Identity:**
- A white card with the QR, then "Node ID" in grey and **97ef4bf4** large with a copy icon.
- A "Safety number (256-bit fingerprint) ⓘ" card with a monospace 4×4 grid in a grey box and a copy icon.
- A full-width blue **Share my QR**, then two outlined buttons: **Scan QR** and **Upload QR**.
- A blue ⓘ trust note.

**Settings.** White grouped cards under blue uppercase headers:
- **PROFILE:** an avatar row, "Display name / Vrishank ›".
- **CONNECTIVITY:** Internet relay (switch), Relay server ›.
- **PRIVACY & DATA:** Privacy & encryption ›, Groups ›, Data & storage ›.
- **ADVANCED:** Developer Tools ›, shown highlighted with a soft-blue fill.
- **ABOUT:** About ›.

Sub-pages use the same row style:
- **Privacy:** a Discover switch and **Blocked contacts ›**. Blocked contacts are rows with a red-outline **Unblock**.
- **Data & storage:** a buffer-use bar, plus **Clear buffer…** in red.

**Relay Configuration:**
- An amber "Internet relay is optional" note.
- An "MQTT Broker Address" card with a field and a grey example.
- A "Connection" card with read-only rows (🔒 TLS 8883 → TCP 1883 → WebSocket 8884 · automatic; QoS 1; auto-reconnect 1–15 s), each with a grey info line.
- A status line in green or red.
- Two buttons fixed at the bottom: outlined **Test connection** and blue **✓ Save changes**.

**Developer Tools:**
- A blue ⓘ note: "For advanced users and researchers".
- 8 large white rows, each with a coloured icon square, a bold title, a grey subtitle and ›:
  - Event Log
  - Routing Engine
  - Buffer History
  - Routing & Tuning (subtitle lists the 6 algorithms)
  - Benchmarks
  - Export Data
  - Console
  - Relay Configuration

**Event Log:**
- Title + "Advanced diagnostic screen" with a filter icon, a blue ⓘ note and a search pill ("message ID, node ID or type").
- Chips **All · Flow · Delivery · ACK · Issues**.
- Event cards with a 4 dp **coloured left stripe**: green (ack, cleared), orange (forwarded, buffered), blue (created), red (issues). Each has an icon circle, a coloured title, the time on the right, monospace "MSG e1474a5e" and "FROM d10ec32d → TO 97ef4bf4", a grey detail line and ›.
- Outlined **Export events** fixed at the bottom.

**Routing Engine:**
- A summary card with 4 metric tiles (sent ✓, relayed ✓, received, buffered).
- A "Per-peer scores" table card: avatar, name, the active algorithm's value (P / f / Q) in its colour, RSSI and distance.
- An "Encounters & calculations" list: monospace lines, with calculation lines in blue.

**Buffer History.** A chip row (All · Delivered · Expired · Dropped) and cards: "To / From", buffered → cleared times, and a coloured result pill ("Delivered" green, "Expired" orange, "Dropped" red).

**Routing & Tuning:**
- **"Routing protocol" card:** a 2-column grid of 6 radio cards. Each has a radio button, an icon, a bold NAME and a grey one-word description. The selected card has a blue border and soft-blue fill.
- **"Experimental parameters" card:** the λ slider (blue) and the η slider (purple), each with its value in a small outlined box and 0.00 / 1.00 under the ends.
- **Cards:** "Benchmarks ›", "Formulas & constants" (expandable, monospace), and "Reset to defaults" with a red-outline **Reset**.
- A grey ⓘ note, then a blue **Save changes** and a light **Cancel**.

**Benchmarks:**
- A blue "Using collected data" note.
- A **table card**: a header row (Strategy · Deliveries · Delivery rate ↓ · Avg delay · Overhead ⓘ), one row per algorithm with its colour dot, numbers right-aligned, and the top row tinted soft blue.
- A **bar chart card** "Delivery rate by strategy": rounded bars in the algorithm colours, values on top, a 0–100% axis.
- An amber "Need more data" note when counts are small.
- A red-outline **Reset results** button.

**Export Data:**
- A blue "Nothing is uploaded automatically" note.
- A "Choose data to export" card of setting rows with switches: encounters & contacts, messages & delivery history, forwarding decisions, Q-tables & metadata, **all logs**.
- A "Date range" card with From / To pickers and a "Last 7 days" chip.
- A blue **Generate export** button, then a "Saved to … · Share" line and a "View export history ›" row.

**Console:**
- Title with a "Settings › Developer Tools › Console" breadcrumb and a filter icon. The filter icon opens the source picker: Bluetooth, Wi-Fi Direct, LoRa hub, Relay, Routing, Buffer, Receipts, Crypto, Presence, Q-learning, System.
- An info card, then tabs **All (24) · ⚠ Warnings (3) · ❗ Errors (1)**, and a search pill.
- A **dark terminal panel** (`#0F172A`, 12 dp corners), monospace 12 sp: "[21:04:28]" in grey, level tags (INFO cyan, WARN amber, ERROR red), then the text.
- Two buttons: tonal **Copy output** and red-tint **Clear view**.
- A grey note that clearing only affects the screen.
