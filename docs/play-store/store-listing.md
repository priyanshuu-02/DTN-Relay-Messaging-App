# Store listing

**App name** (30 max): `DTN Mesh Relay`

**Short description** (80 max — this one is exactly 80):

```
Off-grid messaging that hops phone to phone over Bluetooth, Wi-Fi Direct & LoRa.
```

**Category:** Communication · **Tags:** Messaging, Offline, Mesh network

**Contact email:** *(required — your address)* · **Website:** *(optional, e.g. the project repo)*

**Full description** (4000 max):

```
DTN Mesh Relay keeps you in touch where there's no mobile signal — treks, campuses, events, field work, disaster drills.

Messages hop from phone to phone. If your contact isn't in range, your phone keeps the message and hands it on to whoever it meets next, until it reaches them. This is called delay-tolerant networking (store, carry and forward), and it works without SIM cards, Wi-Fi networks or accounts.

HOW MESSAGES TRAVEL
• Bluetooth LE between nearby phones
• Wi-Fi Direct between phones when Bluetooth can't
• LoRa hubs (Heltec WiFi LoRa 32 V3 running the open DTN hub firmware) that relay messages kilometres apart
• An optional internet relay, so contacts with internet can reach each other anywhere

PRIVATE BY DESIGN
• Direct and group messages are end-to-end encrypted (X25519 + AES-256-GCM). Phones, hubs and the relay that carry them can't read them.
• Verify a contact's safety number in person by scanning each other's QR code.
• No account, no phone number, no ads, no analytics. Your keys, contacts and chats stay on your phone.
• Broadcasts are public by design — anyone in reach can read them.

SEE EXACTLY WHAT HAPPENED TO EVERY MESSAGE
• Clear status on every message: Queued, Sent, Delivered.
• Message info shows the link it used, the hops, the time it took and its full journey.
• Network and Lifecycle views show peers, routes and the store-and-forward buffer.

SMART ROUTING
Choose PRoPHET, MaxProp, Epidemic or a learning (Double Q-learning) router and compare how they perform on your own network.

GROUPS
Create an encrypted group and share its invite as a QR code. Anyone with the invite can read and post.

GOOD TO KNOW
• Needs Bluetooth LE. LoRa hubs are optional hardware.
• Delivery depends on people moving between each other — messages can take minutes or hours, and expire after a few hours if no path appears.
• The internet relay uses a public MQTT broker by default; it never sees the text of encrypted messages.
```

**Graphics:** `graphics/icon-512.png` (app icon), `graphics/feature-1024x500.png` (feature graphic),
`screenshots/*.png` (phone).
