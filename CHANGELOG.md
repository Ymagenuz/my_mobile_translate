# Changelog

**English** | [简体中文](CHANGELOG.zh-CN.md)

## 0.5.0 — 2026-11-17

- **Interpret**: a new page for a conversation between people who speak different languages. Each speaker has a button at the bottom, with their language above it: tap it to talk until you tap it again, or press and hold it while you talk. **+** adds speakers, up to six. While a speaker's microphone is open, their button shows its level as a moving wave. Every sentence is translated into the other speakers' languages as soon as it's heard, while listening carries on, and with **Read aloud** on the translations are spoken between sentences. The conversation shows as bubbles, the first speaker's on the left and the second's on the right. It uses the phone's speech recognition and text-to-speech, and your API key for the translation. A language the phone's speech recognition can't hear, such as anything but Chinese and English with Xiaomi's engine, is marked: its speaker can't talk but still gets translations. If your model accepts audio, turn on **Supports audio input** in **Settings → Translation service** and the model hears those languages instead: SnipDo records them, splits them into sentences at pauses, and has the model write each one down.
- A new look throughout: the app's blue and teal on cool grey, in light and dark, and drawn icons in place of the text buttons and symbols, which every phone maker's font drew differently. The app icon is redrawn and can take your wallpaper's colours as a themed icon on Android 13 and later, and both Quick Settings tiles have new icons.
- The main page puts **Paste**, **Dictionary**, **Translate** and a clear button in the text box's card. A button between the two languages swaps them, and **Default** is now named for what it does, **中文 / English**. Your latest translations are listed under **Recent**. Until both are done, a **Get set up** card offers to connect a translation service and to add the **Select on screen** tile.
- **Copy**, **Share** and **Back-translate** sit together under a result. In the sheet over another app or a selected screen they stay at the bottom with **Open in app**, however long the result is, and the sheet over another app gains **Edit**. Sharing from a selected screen closes the selection.
- On a selected screen, the toolbar's pens are grouped, **Translate** shows how many words are selected, and the toolbar moves by the bar along its top or the gaps in its rows; the two handles in its corners are gone. **Continue ↓** is now **Continue below**.
- Settings is a short page of rows that say how things stand, with the translation service (was **API**) and screen selection on pages of their own. **Save** stays at the bottom of the service's page, and **Open Settings** after a missing key or model goes straight there.
- History can be searched, and clearing it asks first.

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
