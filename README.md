<p align="center">
  <img src="art/klyro-icon.png" width="140" alt="Klyro">
</p>

# Klyro

Keyboard and mouse mapper for Android games. No root — it runs through Shizuku
or wireless debugging.

Put touch controls over the game, bind each one to a key or a mouse button, and play.
Made for phones with a USB/Bluetooth keyboard and mouse. It also runs on emulators,
with one limit (see below).

Alpha.

## What it does

- Edit the layout right over the game: drag controls in from the side drawer, resize them
  with the corner handle, tap one to change its key
- Any control can use a key, a Ctrl/Shift/Alt combo or a mouse button
- Control types: tap, repeat tap, WASD joystick, aim, fire, mouse unlock
- Mouse lock for aiming (cursor hidden, mouse turns the camera)
- Profiles per game, shared as `.krylogg` files
- English and Portuguese

## Getting started

1. Install Klyro and [Shizuku](https://shizuku.rikka.app/).
2. Start Shizuku and allow Klyro.
3. In Settings, run the touch test.
4. Add a game, open it and tap the floating button on the left edge.
5. Edit HUD, place the controls, set the keys, save.

## Emulators

Keys and touch work on LDPlayer and BlueStacks. Mouse-lock aiming doesn't: those emulators
handle the mouse on the Windows side and never pass the raw movement to Android, so no app
inside them can hide the cursor or read it. Use the emulator's own keymapping for the camera there.

## How it works

Android apps can't touch other apps, so Klyro starts a small helper with shell rights
(through Shizuku or ADB). The helper reads the keyboard and mouse from `/dev/input` and injects
multitouch through `InputManager`. While controls are on it also takes over the touchscreen and
re-sends your real touches together with the mapped ones, so Android doesn't drop either.
The app talks to the helper over a local socket that only accepts Klyro's own UID.

## Build

Android SDK (platform 36), NDK 27.1, JDK 17 or newer.

```
./gradlew assembleDebug
```

## Heads up

Some online games forbid keyboard/mouse mappers. Check the game's rules first.

## Third-party

- [XtMapper](https://github.com/Xtr126/XtMapper) (GPL-3.0)
- [keysync](https://github.com/aka-munan/keysync) (Apache-2.0)
- [libadb-android](https://github.com/MuntashirAkon/libadb-android)
- [Shizuku API](https://github.com/RikkaApps/Shizuku-API)
- [Tabler Icons](https://tabler.io/icons) (MIT)

## Copyright

Copyright (C) 2026 Hitorirt. All rights reserved under the terms of the GPL-3.0.

Klyro is free software: you can redistribute it and/or modify it under the GNU General Public
License v3.0. It comes with no warranty. See [LICENSE](LICENSE).
