# SnipDo Translate for Android

**English** | [简体中文](README.zh-CN.md)

The Android counterpart of [SnipDo Translate](https://github.com/Ymagenuz/my_snipdo_translate) for Windows: translation, dictionary lookup and OCR through your own OpenAI-compatible API key, with the same prompts, providers and behaviour. It needs Android 8.0 or later; on-screen selection needs Android 12 or later.

## Ways to translate

- **Select text in any app**, then choose **SnipDo Translate** from the selection menu. The result opens in a sheet over that app.
- **Share** text or an image to the app.
- **Translate clipboard** tile in Quick Settings.
- **Select on screen** tile or accessibility shortcut: pick words directly from whatever is on the screen.
- **Camera** and **Photo** in the app, using the same pens as on-screen selection.
- Or type in the app. Short terms are looked up in the dictionary automatically.

## Selecting on the screen

Pull down the shade and tap **Select on screen**, or press the feature's accessibility shortcut if you turned it on. The screen freezes and looks unchanged, apart from a floating toolbar. The text is scanned on the phone, and nothing is shown or sent until you act.

| Tool | What it does |
|---|---|
| ✎ Text pen | Tap toggles a word. Drag selects every word in between, in reading order. |
| + Plus pen | Adds every word the stroke touches, whatever the reading order. Tap it again to change its width. |
| − Minus pen | Removes every word the stroke touches. It has its own width. |
| ◯ Lasso | Circle a region to add the words inside it. |
| ¶ Paragraph pen | Tap toggles a whole paragraph. |
| ▭ Area pen | Drag a rectangle to have the vision model read just that region, for what the scan can't read: Russian, handwriting, stylised text. |

The toolbar also has undo and redo, select all, clear, a reading-order or selection-order toggle, and the scan language (中, A, あ, 한). Drag it by either ⠿ in its bottom corners; it snaps to the nearest side, remembers its place and collapses to a pill. Two fingers pinch and pan the frozen image. Confirm with **Translate** or **Copy**, or tap **Edit** to correct what the scan misread before anything is sent. After a translation, **Edit** in the result does the same and translates again. Closing the result returns to your selection; Back or ✕ returns to the live app.

Translations fade in as they stream, as on the desktop. Android's **Remove animations** setting turns this off.

### One-time setup

Screen selection is an Android accessibility service, which lets it capture without asking every time.

1. In the app: **Settings → Screen selection → Accessibility settings**, and turn on **SnipDo screen selection**.
2. Android blocks this for apps installed from outside a store. If it says the setting is restricted, open **App info**, tap the **⋮** menu, choose **Allow restricted settings**, and try step 1 again. The menu varies by phone maker.
3. Add the **Select on screen** tile to Quick Settings. New tiles usually go on the last page; tap the pencil (Edit) button in Quick Settings to move it.
4. Optional: on the same accessibility page, turn on the **SnipDo screen selection shortcut**. Its floating button or gesture then starts a selection on any screen without opening Quick Settings.

Some phone makers stop background services to save battery. If the tile stops working, exempt the app in your battery settings.

## Privacy

- The accessibility service can only take a screenshot, and only when you tap the tile or its shortcut. It's configured so it can't read other apps' content.
- Screenshots stay in memory and are never written to storage or history. The scan runs on the phone.
- Nothing is sent until you confirm, and then only the words you selected. The area pen and **Read with AI** send only that image.
- API keys are encrypted with a key held in the Android Keystore. Backups and device transfers are turned off.
- ML Kit's own usage logging is disabled in the app: with its upload path removed, logged events are discarded on the phone.
- Update checks ask GitHub for the latest release and reveal only the app version.

## Differences from the desktop app

- Custom providers must use HTTPS, because plain HTTP would send API keys across shared networks.
- Not included yet: LaTeX typesetting (formulas are protected during translation and shown as source), **Locate in translation**, and Markdown formatting in the input box.
- Russian isn't supported by the on-device scan; use the area pen.
- Updates open the release page in your browser rather than installing inside the app.

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
