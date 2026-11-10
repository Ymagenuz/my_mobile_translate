# SnipDo Translate for Android

**English** | [简体中文](README.zh-CN.md)

The Android counterpart of [SnipDo Translate](https://github.com/Ymagenuz/my_snipdo_translate) for Windows: translation, dictionary lookup and OCR through your own OpenAI-compatible API key, with the same prompts, providers and behaviour. It needs Android 8.0 or later, on-screen selection included.

## Ways to translate

- **Select text in any app**, then choose **SnipDo Translate** from the selection menu. The result opens in a sheet over that app.
- **Share** text or an image to the app.
- **Translate clipboard** tile in Quick Settings.
- **Select on screen** tile, or the optional accessibility shortcut: pick words directly from whatever is on the screen.
- **Camera** and **Photo** in the app, using the same pens as on-screen selection.
- Or type in the app. Short terms are looked up in the dictionary automatically.

## Selecting on the screen

Pull down the shade and tap **Select on screen**. Android asks whether to share the screen with SnipDo; tap **Start** or **Share screen**. If you turned on the optional accessibility service (below), it doesn't ask, and the service's shortcut works too. SnipDo takes one screenshot and stops capturing, and the screen then looks frozen, apart from a floating toolbar. The text is scanned on the phone, and nothing is shown or sent until you act. You can also take a screenshot as usual and share it to SnipDo, which opens it with the same pens.

| Tool | What it does |
|---|---|
| ✎ Text pen | Tap toggles a word. Drag selects every word in between, in reading order. |
| + Plus pen | Adds every word the stroke touches, whatever the reading order. Tap it again to change its width. |
| − Minus pen | Removes every word the stroke touches. It has its own width. |
| ◯ Lasso | Circle a region to add the words inside it. |
| ¶ Paragraph pen | Tap toggles a whole paragraph. |
| ▭ Area pen | Drag a rectangle to have the vision model read just that region, for what the scan can't read: Russian, handwriting, stylised text. |

The toolbar also has undo and redo, select all, clear, a reading-order or selection-order toggle, and the scan language (中, A, あ, 한). Drag it by either ⠿ in its bottom corners; it snaps to the nearest side, remembers its place and collapses to a pill. Two fingers pinch and pan the frozen image. Confirm with **Translate** or **Copy**, or tap **Edit** to correct what the scan misread before anything is sent. After a translation, **Edit** in the result does the same and translates again. Once something is translated, swiping the result down, tapping outside it or Back returns to the live app; **Select again** goes back to your selection to change it. ✕ on the toolbar also returns to the app.

Translations fade in as they stream, as on the desktop. Android's **Remove animations** setting turns this off.

### Setup

Add the **Select on screen** tile to Quick Settings, from **Settings → Screen selection** in the app or by editing Quick Settings. New tiles usually go on the last page; tap the pencil (Edit) button in Quick Settings to move it.

That's all. Screen selection uses Android's standard screen-capture permission, so there is nothing to turn on, it works the same on every phone, and clearing SnipDo from recent apps doesn't stop it. Android asks every time, and briefly shows its screen-sharing indicator while SnipDo takes the screenshot.

### Optional: the accessibility service

On Android 12 and later, screen selection can also run as an accessibility service, as it did in version 0.1. While it's on, the tile captures straight away without Android asking, and the frozen screen opens as an overlay over everything. Its shortcut, a floating button or gesture, starts a selection from any screen without opening Quick Settings.

1. In the app: **Settings → Screen selection → Accessibility settings**, and turn on **SnipDo screen selection**.
2. Android blocks this for apps installed from outside a store. If it says the setting is restricted, open **App info**, tap the **⋮** menu, choose **Allow restricted settings**, and try step 1 again. The menu varies by phone maker.
3. Optional: on the same accessibility page, turn on the **SnipDo screen selection shortcut**.

Whenever the service isn't running, the tile falls back to the screen-capture permission above, so it always works. Some phones turn the service off: Xiaomi's HyperOS does when SnipDo is cleared from recent apps. **Settings → Screen selection** shows whether it's on.

The overlay works like the screen-capture way: part of a result can be selected, with a menu of its own for **Copy** and **Select all**, since Android's text toolbar doesn't open over the overlay; **Edit** gets **Cut** and **Paste** too. Messages such as **Copied** show inside it, and the status and navigation bars aren't scanned, so **Select all** doesn't pick up the clock. The overlay covers the system bars with their frozen picture, so with three-button navigation use ✕ or swipe the result away to leave.

## Privacy

- Screen selection captures only after you tap the tile and allow it in Android's dialog. It takes a single screenshot and stops straight away.
- The optional accessibility service can only take a screenshot, and only when you tap the tile or its shortcut. It's configured so it can't read other apps' content.
- Screenshots stay in memory and are never written to storage or history. The scan runs on the phone.
- Nothing is sent until you confirm, and then only the words you selected. The area pen and **Read with AI** send only that image.
- API keys are encrypted with a key held in the Android Keystore. Backups and device transfers are turned off.
- ML Kit's own usage logging is disabled in the app: with its upload path removed, logged events are discarded on the phone.
- Update checks ask GitHub for the latest release and reveal only the app version. **Update now** downloads the APK only from this project's GitHub release, checks it against the release's SHA-256 file and that it carries the same signature, then Android asks you to confirm installing it. The first time, Android also asks you to let SnipDo install apps.

## Differences from the desktop app

- Custom providers must use HTTPS, because plain HTTP would send API keys across shared networks.
- Not included yet: LaTeX typesetting (formulas are protected during translation and shown as source), **Locate in translation**, and Markdown formatting in the input box.
- Russian isn't supported by the on-device scan; use the area pen.

## Building

The toolchain lives in `D:\Android` and is used per command, with no system changes: JDK 21 in `jdk-21`, the SDK in `Sdk`, Gradle's cache in `gradle-home`. Set `SNIPDO_ANDROID_ROOT` to use another location.

```powershell
.\scripts\gradle.ps1 testDebugUnitTest          # tests on the PC
.\scripts\gradle.ps1 connectedDebugAndroidTest  # tests on a device or emulator
.\scripts\gradle.ps1 assembleDebug              # debug APK
.\scripts\gradle.ps1 assembleQa                 # optimised code, installable on an emulator
.\scripts\test_summary.ps1                      # how many tests really ran
```

Always check the `qa` build before a release. Release builds shrink code, which can break things that work in debug: ML Kit crashed this way until `app/proguard-rules.pro` kept what it needs.

`scripts/generate_golden.py` records the desktop app's prompts and text rules, which the tests compare against. Run it again if those change on the desktop. `scripts/mock_llm.py` is a local stand-in API for end-to-end checks without a real key.

## Signing

Release APKs are signed with `D:\Android\keys\snipdo-release.jks`; its passwords are in the git-ignored `keystore.properties`. **Back up both files somewhere safe.** If they're lost, future APKs can't update installed copies.

## Publishing a release

1. Raise `versionCode` and `versionName` in `app/build.gradle.kts`, and add an entry to both changelogs.
2. Run `.\scripts\build_release.ps1`.
3. Create a full GitHub release tagged `v<version>` with `dist\SnipDoTranslate.apk` and `dist\SnipDoTranslate.apk.sha256`.

Apps check this project's latest release at most once every 6 hours and show ⬇ next to **Settings** when a newer one is available.
