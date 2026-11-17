# Changelog

**English** | [简体中文](CHANGELOG.zh-CN.md)

## Unreleased

- **Interpret**: a new page for a conversation between people who speak different languages. Each speaker has a button at the bottom, with their language above it: tap it to talk until you tap it again, or press and hold it while you talk. **+** adds speakers, up to six. Every sentence is translated into the other speakers' languages as soon as it's heard, while listening carries on, and with **Read aloud** on the translations are spoken between sentences. It uses the phone's speech recognition and text-to-speech, and your API key for the translation. A language the phone's speech recognition can't hear, such as anything but Chinese and English with Xiaomi's engine, is marked: its speaker can't talk but still gets translations. If your model accepts audio, turn on **Supports audio input** in **Settings → API** and the model hears those languages instead: SnipDo records them, splits them into sentences at pauses, and has the model write each one down.

## 0.4.0 — 2026-11-16

- With the accessibility service, a selection can carry on past the bottom of the screen. When your selection reaches the bottom, **Continue ↓** keeps the words and leaves the frozen screen with a small bar. What you kept stays marked on the live app in a colour of its own and moves with the text in apps that report their scrolling. Scroll the app, then tap **Capture** in the bar, the tile or the shortcut. The lines you already have keep that colour on the new screen and can't be selected twice, even where the scan doesn't find their text again, and a line the edge cut off is replaced by its whole version. **Translate**, **Edit** and **Copy** use every part as one text. Only the words are kept between screens.

## 0.3.0 — 2026-11-10

- The accessibility service from 0.1 is back as an option for screen selection, on Android 12 and later. Turn on **SnipDo screen selection** in Accessibility settings and the tile captures straight away without Android asking, and the feature's floating button or gesture starts a selection from any screen. Whenever it isn't running, including after a phone turns it off, the tile asks for screen-capture permission as in 0.2. **Settings → Screen selection** shows whether it's on and links to its setup.
- The accessibility overlay picks up 0.2's improvements to the selected screen: part of a result can be selected, with an in-overlay menu for **Copy** and **Select all**, and **Edit** gets **Cut** and **Paste**, none of which opened over the overlay in 0.1. Messages such as **Copied** or **No text found** show inside it, where Android hid them in 0.1. The status and navigation bars aren't scanned any more, so **Select all** no longer picks up the clock. Dismissing a result returns to the app, and **Select again** goes back to the pens, as on the screen-capture way.

## 0.2.0 — 2026-11-10

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
