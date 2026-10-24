# DTN Mesh Relay — Privacy Policy

*Effective: 24 October 2026. Publisher: [your name or team], contact: [your email].*

DTN Mesh Relay ("the app") is an off-grid messenger: messages travel directly between phones and
optional LoRa hubs, and optionally over an internet relay. The publisher runs **no servers** and
receives **no data** from the app. There are no accounts, ads, analytics or crash-reporting services.

## Data stored on your phone

- Your identity: a key pair generated on the phone, your node id, and the display name you choose
  (optional). The node id is derived (hashed with the app's package name) from Android's app-specific
  device id (`ANDROID_ID`), so each phone has one identity: it stays the same when you clear the app's
  data or reinstall it, and changes only after a factory reset. Clearing data still creates new keys.
- Contacts you have met or scanned: their node ids, public keys, names and verification status, plus
  the contacts you blocked or deleted and when you last read each chat.
- Your chats, the store-and-forward buffer (messages you're carrying, including encrypted messages for
  other people), a message journal (when each message was created, forwarded, delivered), routing
  statistics and settings.

This data is excluded from Android cloud backups and device-to-device transfers. Uninstalling the app,
or clearing its storage, deletes all of it; only the node id comes back the same, because it is
derived again from the device id. The app also keeps a diagnostics log (link and routing events: ids,
counts and times, never message text or keys). Developer Tools › Export Data writes copies of the
research data and, with Export all logs, of these logs and the app's own Android system log to the
app's own storage folder, only when you tap export. They leave the phone only if you share them
yourself (Share).

## Data that leaves your phone

| What | Who receives it | Readable by them? |
|---|---|---|
| Direct and group messages | Nearby phones and LoRa hubs that carry them; the internet relay if on | No — end-to-end encrypted; only the recipient(s) can read them |
| Broadcasts ("Broadcast to All") | Anyone in reach; the internet relay if on | Yes — broadcasts are public by design |
| Your announce: node id, public keys, display name | Nearby phones and hubs; the internet relay if on | Yes — this is how others can message you securely |
| Your node id (your address on the mesh, derived from the device id) | Nearby phones, LoRa hubs and the relay, in every message header, announce and hub registration | Yes — needed to route messages to you |
| Message headers: message id, sender and recipient node ids, lifetime, hop count | Every phone, hub and relay that carries a message | Yes — needed to route messages |
| Delivery receipts | Phones, hubs and the relay on the way back to the sender | Yes (message ids only) |
| Online/offline status and time (internet relay only) | The relay broker and its other users | Yes |

**Internet relay (optional, off until you turn it on).** By default it uses the public MQTT broker
`broker.hivemq.com`, operated by HiveMQ GmbH, which is shared by everyone who uses it. The broker sees
your IP address, your node id, when you're online, message headers and the data in the table above
— never the text of encrypted messages. Connections use TLS by default; on networks that block it, the
app may fall back to an unencrypted connection (encrypted messages stay encrypted). You can enter your
own broker in Settings. HiveMQ's handling of connection data is covered by its own privacy policy.

## Permissions

- **Bluetooth (Nearby devices)** — find and exchange messages with nearby phones.
- **Nearby Wi-Fi devices** — Wi-Fi Direct between phones and joining a LoRa hub's Wi-Fi.
- **Location (Android 12 and older only)** — those Android versions require it for Bluetooth / Wi-Fi
  scans. The app never reads or stores your location.
- **Camera** — scan contact and group QR codes (optional; codes can also be loaded from an image).
- **Notifications** — the "relay running" notification while the app keeps links alive.
- **Internet** — the optional internet relay.

## Retention

Messages in the buffer expire after their lifetime (4 hours by default). Chats, contacts and keys stay
on your phone until you delete them or uninstall the app. The node id persists until the phone is
factory reset (it is recomputed from the device id, so reinstalling gives the same one); other phones
keep it in their contact lists until they delete you. On the relay, your last online/offline status is kept by
the broker (as a retained message) until it is replaced.

## Children

The app is not directed at children under 13.

## Your choices

Leave the internet relay off, leave the display name empty, verify contacts in person, block or delete
contacts, clear the buffer, or uninstall the app to delete everything it stored. Because the publisher holds no data about you,
there is nothing to request from us; copies of messages you sent are under the control of the
recipients and carriers that received them.

## Changes

Updates to this policy are published at this address with a new effective date.

## Contact

[your email]
