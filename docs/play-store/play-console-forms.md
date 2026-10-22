# Play Console → App content: suggested answers

You're the developer of record, so double-check each answer against the current form wording.
These follow Google's definitions: "collected" means sent off the device by the app; data that's
end-to-end encrypted so only the sender and recipient can read it doesn't need to be declared.

## Data safety

**Does your app collect or share any of the required user data types?** Yes.

| Data type | Collected | Shared | Optional? | Purpose | Why |
|---|---|---|---|---|---|
| Personal info → Name | Yes | No | Optional | App functionality | The display name (if set) is sent in the signed announce |
| Messages → Other in-app messages | Yes | No | Optional | App functionality | Public broadcasts aren't encrypted. DMs and group messages are E2E encrypted and excluded |
| Device or other IDs | Yes | No | Required | App functionality | The app-generated node id + public keys identify you to other phones and the relay |

- **Processed ephemerally?** No.
- **Shared:** no. Transfers to other users and to the relay broker happen because the user sends a
  message or turns the relay on (user-initiated). There are no third-party SDKs (no ads, analytics or
  crash reporting).
- **Is all user data encrypted in transit?** **No.** Broadcasts and announces over Bluetooth / Wi-Fi
  Direct / LoRa aren't link-encrypted, and the relay can fall back to plain MQTT when TLS is blocked.
- **Can users request deletion?** Data lives only on the phone; uninstalling or clearing app storage
  deletes it. The developer holds none. (No accounts, so no account-deletion URL is required.)

## Privacy policy

Host `privacy-policy.md` at a public URL (GitHub Pages, Google Sites, a public Gist rendered as a page)
and paste the URL in **Privacy policy** and in the store listing. Fill in the name/email placeholders first.

## App access

All functionality is available without special access (no login).

## Ads

No, the app doesn't contain ads.

## Content rating (IARC questionnaire)

Category: **Communication / social**. Answer *Yes* to "users can interact or exchange content with
other users" (chat). *No* to violence, sexual content, profanity, drugs, gambling, and to sharing the
user's physical location (the app never reads location). No digital purchases.

## Target audience and content

Choose **18 and over** (or 13+). Don't select ages under 13: the app has unmoderated user-to-user
messaging, and child audiences bring the Families policy requirements.

## Foreground service permissions (required for targetSdk 34+)

- Type: **Connected device** (`FOREGROUND_SERVICE_CONNECTED_DEVICE`).
- Description to paste:
  > DTN Mesh Relay is a store-carry-forward messenger. While the user has tapped Connect, a foreground
  > service keeps the Bluetooth LE / Wi-Fi Direct links to nearby phones and the Wi-Fi link to the
  > user's LoRa hub open, so messages the phone is carrying are handed on as people meet, even when the
  > app is in the background. It starts only after the user taps Connect, shows a persistent
  > notification, and stops when the user taps Disconnect.
- Impact if deferred or interrupted: messages stop moving between phones; links drop.
- **Video link (required):** a 30–60 s screen recording uploaded unlisted (e.g. YouTube): tap Connect →
  the notification appears → leave the app → a message from a second phone still arrives.

## Permissions declarations

None of the special-access permissions that need a Console declaration are used (no SMS/Call log, no
background location, no QUERY_ALL_PACKAGES, no exact alarms, no accessibility). Location is requested
only on Android 12L and older, for Bluetooth / Wi-Fi scanning, and is capped with `maxSdkVersion=32`.

## Other declarations

Government app: No · Financial features: None · Health: No · News: No.
