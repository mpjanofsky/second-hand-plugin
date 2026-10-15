# Developing Second Hand

## Build and test

Install Python 3, JDK 17 and an Android SDK with a platform and build-tools, then run:

```bash
scripts/check.sh
```

It runs the tests (`scripts/test.py`) and writes `dist/echo-wall-clock-plugin-<version>.zip`.

## Try a build on a kiosk

Use **Plugin Manager > Developer Tools > Install from ZIP**. A plugin installed from a local ZIP can't be replaced
by the GitHub version, and the other way around: uninstall first to switch sources. Uninstalling deletes the
plugin's settings.

`scripts/ks.py` installs a ZIP and reads or changes settings through the Remote Admin API (`install <zip>`,
`state`, `set key=value ...`, `screenshot <file>`). It reads `KS_ADMIN_URL` and `KS_ADMIN_AUTH` from the
environment or from a gitignored `.local/LOCAL.md`.

## How it is organized

- `src/io/github/mpjanofsky/kiosk/secondhandplugin/SecondHandPlugin.java` connects the plugin to Kiosk Satellite. The rules live in plain Java classes
  beside it and are tested off-device against `fixtures/`.
- `tests/` are plain Java classes with a `main` method and `assert`s; `scripts/test.py` finds and runs them.
- `sdk/` and `tools/` come from the Kiosk Satellite plugin template and aren't edited here.

## How the plugin is built

- `protocol/` is plain Java with no Android: `Framer` and `Deframer` (the clock's SPP framing and checksum), `Messages` (the exact bytes a real Echo sends), `ClockTime`, `AlertToken`. `fixtures/golden-frames.txt` holds the expected bytes, produced by an independent implementation of the protocol; `ProtocolTest` checks every message and every split of the byte stream against it.
- `ClockRules` turns timer events and the time into the commands to send. It has no threads and no clock of its own, so `ClockRulesTest` drives every state by hand.
- `ClockLink` keeps one connection alive on its own thread (retry with backoff, handshake, resend after a reconnect, the half-hour `timeinfo`). It talks to a `ClockTransport`; `ClockLinkTest` uses a fake clock, `AndroidTransport` is the real one.
- `ClockService` and `SecondHandPlugin` are the Android and Kiosk Satellite side: the timer broadcast receiver, pairing, the status line. Callbacks only queue work and return.

The plugin reaches Android by two routes the Kiosk Satellite SDK doesn't provide: a `Context` from `ActivityThread.currentApplication()`, and Android's hidden `BluetoothDevice.createRfcommSocket(4)` (the clock's data channel can't be found by UUID). Both are marked `ks-rules: allow` in the code.

## Trying it on a kiosk

- Install with `scripts/ks.py install dist/echo-wall-clock-plugin-<version>.zip`, then `scripts/ks.py state` for the status line.
- Pairing needs a person: the clock pulsing orange, and a tap on Android's pairing prompt. `adb logcat` shows what the Bluetooth stack does (`BluetoothBondStateMachine`).
- Fake a voice timer without speaking, with a 26-character timer id:

```bash
adb shell am broadcast -a me.jxl.kiosk_satellite.event.VS_TIMER_STARTED \
  --es timer_id TESTA00000000000000000000A --ei total_seconds 60 --ei seconds_left 60 --ez is_active true
```

  The other actions are `VS_TIMER_UPDATED` (paused when `is_active` is false), `VS_TIMER_CANCELLED`, `VS_TIMER_FINISHED` and `VS_TIMER_DISMISSED`.
