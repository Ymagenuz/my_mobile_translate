# Changelog

**English** | [简体中文](CHANGELOG.zh-CN.md)

## Unreleased

- Screen selection now uses Android's standard screen-capture permission instead of an accessibility service. Adding the tile is the only setup, it works on Android 8 and later, and clearing SnipDo from recent apps no longer turns it off, as it did on Xiaomi phones. Android asks each time before the screen is captured. The accessibility shortcut and its floating button are gone; share a screenshot to SnipDo for another way in.
- Part of a result on a selected screen can now be selected and copied.
- After a translation on a selected screen or photo, swiping the result down, tapping outside it or Back returns straight to the app. **Select again** in the result goes back to the pens instead. **Open in app** moved below the result.
- Opening and leaving a selected screen no longer plays the phone's app-switching animation, which HyperOS showed.
- **Update now** in Settings downloads a new version inside the app, checks its SHA-256 and signature, and hands it to Android's installer. **View on GitHub** still opens the release page.
- Messages during screen selection, such as **Copied** or **No text found**, now appear. Android used to hide them.

## 0.1.1 — 2026-11-09

- While a translation or lookup is waiting for its first text, the result shows a spinner with **Translating…** or **Looking up…**. Back-translation shows the same. With Android's **Remove animations** setting on, only the words show.

## 0.1.0 — not published

- Translate, look up words and back-translate through your own API key, with GPTSAPI, DeepSeek, OpenRouter or any OpenAI-compatible provider. Results stream in as Markdown and fade in as on the desktop, with **Stop**, **Try again** and history.
- Translate from any app's text-selection menu, from the share sheet, or from the clipboard with a Quick Settings tile.
- **Select on screen**: scan whatever is on the screen and pick words with text, plus, minus, lasso, paragraph and area pens. Start it from a Quick Settings tile or the feature's accessibility shortcut. **Edit** corrects what the scan misread before translating.
- Open photos and screenshots with the same pens, or read a whole image with the vision model.
- English and 简体中文 interface.
