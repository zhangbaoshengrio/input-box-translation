# Input Box Translate

Press **Space 3 times quickly** in any chat box and the whole text you typed is replaced with its translation. It is an [LSPosed](https://github.com/LSPosed/LSPosed) module that talks to any OpenAI-compatible API: DeepSeek, OpenAI, or a relay/proxy.

Type `你好世界`, tap Space three times, and the box now says `Hello world`.

**Download:** the latest APK is on the [Releases](../../releases) page.

## Requirements

- A rooted Android phone with LSPosed (Zygisk). Tested on Android 16 with KernelSU and LSPosed 2.2.0.
- An API key for an OpenAI-compatible chat completions endpoint.

## Install

1. Download the latest `input-box-translate-*.apk` from the [Releases](../../releases) page and install it.
2. Open LSPosed, go to Modules, and enable **Input Box Translate**.
3. Tick the apps you want it to work in (see below), then force-stop those apps.
4. Open **Input Box Translate**. It should say **Connected · LSPosed**. If it says Not connected, force-stop it and open it again.
5. Choose DeepSeek, OpenAI or Custom, paste your API key, tap **Test**, then **Save**. Once saved, the key box shows dots instead of the key.

## Use

In an enabled app, type some text and press Space 3 times within about 0.7 seconds. A "Translating…" toast appears and the text box is replaced with the translation. If the request fails, your text is left exactly as it was.

## Keyboard mode (Doubao IME)

Instead of ticking apps, you can tick the keyboard: enable **Doubao IME** (`com.bytedance.android.doubaoime`) in the module's LSPosed scope and untick the apps. The hook then runs inside the keyboard, so Space 3 times translates in every app. Use either the keyboard or the apps, not both, or the text is translated twice. The "翻译中…" toast may not show in this mode.

## Apps tested

- WhatsApp (`com.whatsapp`)
- WeChat (`com.tencent.mm`)
- Claude (`com.anthropic.claude`)
- ChatGPT (`com.openai.chatgpt`)
- Gemini (needs both `com.google.android.apps.bard` and the Google app `com.google.android.googlequicksearchbox` enabled)
- Edge (`com.microsoft.emmx`): address bar; web page fields are untested

Any other app can be added in LSPosed's scope for this module.

## Troubleshooting

- **The module app says "Not connected to LSPosed".** LSPosed connects the module app only when its process starts, and some phones freeze background apps, which drops the link. The app now restarts itself once to reconnect. If it still shows red, check that the module is enabled in LSPosed, then force-stop the module app and open it again. This only affects the settings screen; translation inside other apps keeps working.
- **Nothing happens when I press Space 3 times.** Check that the app is ticked in the module's LSPosed scope, then force-stop that app and reopen it. Press the spaces within about 0.7 seconds, and make sure the box is not empty.
- **"Translate failed" toast.** Open the module app and tap **Test** to see the exact error from the API (wrong key, wrong Base URL, wrong model name, or no network).
- **Gemini does nothing.** Tick both `com.google.android.apps.bard` and `com.google.android.googlequicksearchbox` in the scope.
- **Adding another app.** Tick it in the module's scope in LSPosed and force-stop it. Apps that draw their own text fields may not work.

## Settings

- **Base URL**: `https://api.deepseek.com`, `https://api.openai.com/v1`, or your relay's URL. The app calls `{base URL}/chat/completions`.
- **API key**: stored through LSPosed's remote preferences, not in the code or the APK.
- **Model**: for example `deepseek-chat` or `gpt-4o-mini`.
- **Translate into**: any language name, `English` by default.

## How it works

The module hooks `commitText` on whatever `InputConnection` the app uses. It finds that class at runtime through the framework's `RemoteInputConnectionImpl`, so it works with ordinary text boxes and with Jetpack Compose text fields alike. When it sees three spaces in quick succession, it reads the text around the cursor, asks the API for a translation on a background thread, then replaces the text. It skips the replacement if you kept typing while it waited.

## Privacy

Whatever is in the box when you press Space 3 times is sent to the API server you configured, and to a relay too if you use one. Only use a relay you trust. Password fields are not excluded in this version.

## Known limits

- Rooted phones with LSPosed only.
- Apps with custom-drawn text fields (some games) may not use `InputConnection`.
- Text in the box that is longer than about 4000 characters is not handled.
- Banking apps may detect hooks. Do not enable them in the scope.

## Build

Needs JDK 17 and the Android SDK (platform 37).

```
gradle :app:assembleRelease
```

The APK is signed with the debug key, which is fine for sideloading.
