# WordFlow Keyboard

An Android keyboard prototype with a local clipboard word queue and a **Next Word** action.

## MVP features
- Android `InputMethodService` keyboard service.
- Explicit **Load Clipboard** button.
- Insert the next queued word (including its following whitespace and punctuation) through Android's standard `InputConnection.commitText()` interface.
- Previous, Skip, Reset controls and queue progress.
- Clipboard contents stay on-device; no network permission or backend.

## Important compatibility note
This uses Android's standard input-method interface. It is not a way to defeat websites' paste or input restrictions. A site/editor may reject or ignore input, and compatibility varies by browser and editor. Test only on pages where this kind of input is permitted.

## Project structure
```text
IITM-keyboard-/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
└── app/
    ├── build.gradle.kts
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/wordflow/keyboard/WordFlowInputMethod.kt
        └── res/xml/wordflow_method.xml
```

## Build on Android
1. Open/import the repository in AndroidIDE (or Android Studio).
2. Let Gradle sync and install the debug APK.
3. On Android, open **Settings → System → Keyboard → On-screen keyboard** (menu names vary), enable **WordFlow Keyboard**, then select it from the keyboard switcher.
4. Open a normal text field, copy a short sample in another app, tap **Load Clipboard**, then **Next Word**.
5. Verify punctuation, spaces, and newlines in an editor you control.

## Limitations
This is a starter keyboard focused on word-by-word insertion, not a complete Gboard replacement. It does not yet include a full QWERTY layout, suggestions, autocorrect, voice typing, or language packs.
