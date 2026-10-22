# Publishing DTN Mesh Relay on Google Play

Everything the app side needs is done: target API 36, signed release AAB, R8-minified build,
backup exclusion, adaptive icon, 16 KB-page-safe native libs. What's left happens in Play Console.

| File | What it's for |
|---|---|
| [store-listing.md](store-listing.md) | Title, short and full description, category |
| [privacy-policy.md](privacy-policy.md) | Privacy policy — host it at a public URL |
| [play-console-forms.md](play-console-forms.md) | Answers for Data safety, permissions, foreground service, content rating, target audience |
| `graphics/` | 512×512 icon, 1024×500 feature graphic |
| `screenshots/` | Phone screenshots (1220×2440, within Play's 2:1 limit) |

## Build

```powershell
./gradlew.bat :app:bundleRelease :app:assembleRelease
```

- `app/build/outputs/bundle/release/app-release.aab` — upload this to Play.
- `app/build/outputs/apk/release/app-release.apk` — the same build as an installable APK (sideloading).
- Both are signed with the **upload key** in `keystore/dtn-upload.jks`; its passwords are in
  `keystore.properties`. Both files are git-ignored. **Back them up somewhere private** (password
  manager, encrypted drive). If the upload key is lost, Play support can reset it, but that takes days.
- Bump `versionCode` (and usually `versionName`) in `app/build.gradle.kts` for every upload.
- The R8 mapping file is embedded in the AAB, so Play de-obfuscates crash stack traces itself.

## Play Console checklist

1. Create a developer account (one-time fee) and verify your identity.
2. **Create app** → name "DTN Mesh Relay", default language, App, Free.
3. **App signing**: keep "Google-managed app signing key" (Play App Signing). Upload the AAB; the key
   in `keystore/` becomes your upload key.
4. **Store listing**: texts from `store-listing.md`, icon + feature graphic from `graphics/`, at least
   two screenshots from `screenshots/`, a contact email.
5. **App content** (Policy → App content), using `play-console-forms.md`:
   privacy policy URL · app access · ads · content rating · target audience · data safety ·
   foreground service declaration (needs a short demo video) · government / financial / health = none.
6. **Testing.** New *personal* developer accounts (created after 13 Nov 2023) must run a **closed test
   with at least 12 testers opted in for 14 days in a row** before production access is granted. The
   people you're sharing the APK with on WhatsApp can be those testers: add their Google accounts to a
   closed-testing list and send them the opt-in link.
7. Apply for production access → release.

## Before you hand out APKs

- The shared APK (`dist/DTN-Mesh-1.0.0.apk`) is signed with the upload key. Anyone who has an older
  **debug** build (installed from Android Studio or an earlier shared APK) must **uninstall it once**
  first — Android refuses to replace an app signed with a different key ("App not installed"). That
  uninstall deletes their keys, contacts and chats.
- Play installs are signed with Google's app-signing key, so moving from a sideloaded APK to the Play
  version needs one more uninstall. Once everyone installs from Play, updates are seamless.
- Android warns about installing from unknown sources and Play Protect may show "unknown developer"
  for sideloaded APKs. That's expected until the app is on Play.

## On-device check of the minified build

```powershell
./gradlew.bat :app:assembleStaging
adb install app/build/outputs/apk/staging/app-staging.apk   # com.dtn.mesh.staging, own data
adb uninstall com.dtn.mesh.staging                           # when done
```
