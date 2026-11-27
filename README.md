# Reply Drafter

One tap in a chat app reads the conversation, opens the Claude app with it and a few of your own past messages, and Claude drafts a reply in your voice and the tone you pick. Then one more tap pastes Claude's reply into the chat's message box. It never sends anything for you, and it uses your Claude plan through the Claude app (no API key, no per-use charges).

Works with Gboard or any keyboard: nothing about your keyboard changes.

## How it works

1. You're in a chat (Messenger, WhatsApp, Google Messages, Signal, Instagram, Telegram and others).
2. Tap the ✍ bubble on the right edge, the **Reply with Claude** Quick Settings tile, or the accessibility button.
3. Pick a tone (Auto, Casual, Warm, Funny, Flirty, Firm, Professional, Short) and tap **Draft in Claude**.
4. The app scrolls the chat back to collect earlier messages (30 by default; 15, 30, 60 or 100 in settings), scrolls back down, and opens the Claude app with a ready prompt. Press send in Claude.
5. Copy Claude's reply, go back to the chat, tap the bubble and **Paste reply**. Read it over and send it yourself.

If an app hides its text from accessibility, the app sends Claude a screenshot of the chat instead.

## Privacy

- Nothing is read until you tap. The chat is read only to build that one prompt, then dropped.
- Only your own messages are kept on the phone, to learn your style. You can turn that off or clear it in the app.
- The chat reaches Claude only through the Claude app, when you press send there.

## Install (sideloaded APK)

1. Download the APK from the latest **Build APK** run on GitHub (Actions tab, the `reply-drafter-debug` artifact), unzip it and open the `.apk` on the phone.
2. Open Reply Drafter and follow **1. Turn it on**:
   - Android 13 and later block accessibility for sideloaded apps until you allow it once. Tap **Open app info**, then ⋮ (top right), **Allow restricted settings**, and confirm.
   - Tap **Open Accessibility**, find Reply Drafter under Downloaded apps, and turn it on.
   - If **Allow restricted settings** isn't in the menu yet, try turning the service on first. Android shows the menu item after one blocked try.
3. Optional: edit your Quick Settings and add the **Reply with Claude** tile.

Needs Android 11 or later and the Claude app by Anthropic.

## Building

- Android build: `./gradlew :app:assembleDebug` (AGP 9.5.1, Gradle 9.9.2, JDK 17+, compileSdk/targetSdk 37, minSdk 30). No libraries beyond the Android platform.
- The GitHub Actions workflow in `.github/workflows/build.yml` runs the unit tests and uploads the debug APK.
- Signing: every APK from CI is signed with one fixed key, so a new build installs over the old one. The key lives only in the repository's Actions secrets `SIGNING_KEYSTORE_B64` (the base64 of a PKCS12 file with alias `replydrafter`) and `SIGNING_PASSWORD`. Never commit it. Without those secrets, CI falls back to a throwaway debug key and the APK won't update an installed copy.
- `verify-core/` is a plain-JVM build that compiles and tests the chat parsing, style and prompt code (`app/src/main/kotlin/.../core`) without the Android SDK: `gradle -p verify-core test`.

## Known limits

- Who sent each message is guessed from which side of the screen the bubble is on. Group chats and centered system notices can confuse it.
- Scroll-back depends on the chat app exposing its message list to accessibility. If it doesn't, the app uses what's on screen (or a screenshot).
- The pasted reply comes from the clipboard, so copy Claude's reply before tapping **Paste reply**.
