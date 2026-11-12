# Keypad mapping

Settings → Keypad mapping lets you use a keypad that Sidekit doesn't recognise, or move Sidephone keys. It is off by default: Sidekit receives keys as they arrive and detects the module from them, as before.

## Custom mapping

Tap **Mapping** to switch from Automatic to Custom. Custom mapping uses the module that is currently detected, and **Layout** chooses a different one: T9, Mini QWERTY, Sundial or Game controller. While Custom is on, detection is paused, so a key can't switch the module.

Each module has its own list of controls, which you remap separately. The list includes Back, Options, the D-pad and every typing key. Choose a control, then press the button you want to use for it. Press the button a second time to save it. Keys you haven't changed keep their default role, so a partial mapping doesn't disable working keys.

A button can have only one role. If you choose a button that already has a role, Sidekit shows the old role and removes it when you save. That control then returns to its default key. To move the Back or Options button, tap **Save**. Pressing Back or Options again doesn't save it, so trying to leave doesn't remap it by accident.

While Sidekit waits for a key, it captures every press, including the release, so a key you press doesn't trigger anything when the screen closes. You can leave the wait in these ways:

- Tap **Cancel**.
- Press the Sidephone's top-left key. With the keypad accessibility service on, it sends the system Back.
- Wait 10 seconds.

**Reset keys**, in Options or at the bottom of the list, clears the module's mapping. **Use automatic** in Options turns custom mapping off. Touch always works on these screens.

## Key tester

The key tester shows the key code and scan code for each press, the input device that sent it, and the control it becomes. Use it to set up a new keypad or to report how a device's keys behave.

Sidekit only treats the keypad modules' own input devices as keypads. A headset button or an external keyboard is ignored for module detection, even when it sends the same key code.

## Sundial holds

The Sundial has no Back or Options key. Hold **Tab** for Back and hold **Enter** for Options. Hold the left/right edges (**Previous** / **Next**) for left/right; a tap on them still sends the media command. Keypad holds follow Android's long-press timeout (`ViewConfiguration.getLongPressTimeout()`, 400 ms by default), so a device or accessibility tool that changes the system value also changes them. The Back-to-dialer hold stays at 600 ms.

## Scope

- Mappings are saved for each input device, by device name. A key from another device isn't remapped, even if it sends the same code. Examples are an external keyboard and a headset button. Device names, unlike device ids, stay the same across restarts and reconnections.
- Keys without an Android key code are recognised by their scan code.
- Remapping applies in Sidekit and in Sideport projections. Other apps receive the keys unchanged.
- The Sidephone's top-left key (CALL) becomes Back through the keypad accessibility service, before Sidekit receives it. That key can't be remapped. You can make another button act as Back.

## Verification

`./gradlew :app:testDebugUnitTest --tests '*KeypadMapping*' --tests '*KeypadKeysTest' --tests '*SettingsUiTest' :app:assembleDebug`

The screenshots in `app/screenshots/keypad_mapping_*.png` are Compose and Roborazzi captures at the Sidephone canvas size. They aren't photos of the hardware.
