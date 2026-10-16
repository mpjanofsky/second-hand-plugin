<p align="center"><img src="docs/banner.svg" alt="Second Hand: your Echo Wall Clock, without the Echo" width="720"></p>

<p align="center">
<a href="LICENSE"><img alt="License" src="https://img.shields.io/github/license/mpjanofsky/second-hand-plugin"></a>
<a href="https://github.com/mpjanofsky/second-hand-plugin/releases/latest"><img alt="Latest release" src="https://img.shields.io/github/v/release/mpjanofsky/second-hand-plugin"></a>
<img alt="Downloads" src="https://img.shields.io/github/downloads/mpjanofsky/second-hand-plugin/total">
</p>

# ⏱️ Second Hand Plugin for Kiosk Satellite

Second Hand gives your Echo Wall Clock a second life. It connects the clock straight to your Kiosk Satellite kiosk, so the clock shows your kiosk's voice timers and keeps the right time, the way it does with an Echo. The clock needs no changes.

## ✨ Features

- ⏲️ **Voice timers on the clock:** say "set a timer for 10 minutes" to your kiosk and the clock shows it. Pause, resume, add time or cancel by voice and the clock follows.
- 🔢 **Several timers at once:** run more than one timer and the clock shows them together (tested with four).
- 🔔 **A pulse when time is up:** the clock pulses once when a timer ends, and stops when you stop the timer.
- 🕐 **The right time:** Second Hand sets the clock's time and keeps it in sync with your kiosk, including time zone changes.
- 🔁 **Looks after itself:** if the clock's batteries die or it goes out of range, it reconnects on its own.
- 🔒 **Local only:** no account, no cloud, and nothing to change on the clock.

Second Hand follows the timers of **the kiosk it runs on**. One kiosk, one clock.

## 📱 Requirements

| | |
|---|---|
| **You need** | An Echo Wall Clock, and a kiosk running Kiosk Satellite 2026.10.22 or newer with Bluetooth turned on and a Voice Satellite configured. |
| **Tested on** | An Echo Show 5 running LineageOS 18.1 (Android 11) |
| **Should work on** | Other Kiosk Satellite kiosks with Bluetooth |

## 🚀 Install

1. In Kiosk Satellite, open **Settings > Plugin Manager** (or **Plugin Manager** in Remote Admin) and turn on **Enable Plugins**.
2. Choose **Add plugin**, paste `https://github.com/mpjanofsky/second-hand-plugin`, and choose **Preview**.
3. Review the manifest and this README, then choose **Trust and install**.
4. Enable **Second Hand** with the switch on its row.

## 🕰️ Set up your clock

Pairing needs you at the kiosk and the clock. You can do step 1 from Remote Admin.

1. In Plugin Manager, tap Second Hand's row to open its page. Open the **Pair clock** action and turn on **Show in kiosk drawer**. (You can also run it with a gesture or a Home Assistant button.)
2. Open the kiosk drawer and tap **Pair clock**.
3. Press and hold the blue pairing button on the back of the clock (up to 10 seconds) until the light on the front pulses orange. That is pairing mode. Keep it pulsing: Second Hand looks for the clock for 90 seconds.
4. When Android asks to pair, choose **Pair** on the kiosk.

Open the plugin's row in Plugin Manager to check the status. It should say **Connected to the clock.** Start a voice timer and it shows on the clock.

An Echo Wall Clock connects to one device at a time. Pairing it with Second Hand disconnects it from whatever it was paired with before. To give it back, run **Forget clock**.

## ⚙️ Settings

The one setting, **Clock address**, fills in when you pair. Use **Forget clock** to pair a different clock.

Actions you can show in the kiosk drawer, give a gesture, or expose to Home Assistant:

| Action | What it does |
|---|---|
| **Pair clock** | Finds the clock while it is in pairing mode (light pulsing orange) and pairs with it. |
| **Reconnect to clock** | Tries to connect now instead of waiting. |
| **Forget clock** | Removes the pairing so the clock can be paired with something else. |

## 🔒 Privacy

Everything stays on the kiosk. Second Hand reads the kiosk's timer events and talks to the clock over Bluetooth. It doesn't use the internet and sends nothing about you or your kiosk anywhere. The clock's Bluetooth address is saved in the plugin's settings on the kiosk.

## 🧰 Troubleshooting

The status line shows in the plugin's row in Plugin Manager, on the kiosk or in Remote Admin.

| What you see | What to try |
|---|---|
| **No clock paired yet** or **The clock is not paired** | Run **Pair clock** with the clock in pairing mode. |
| **The clock was not found** | Run **Pair clock** again, and keep the clock in pairing mode (light pulsing orange) the whole time. |
| **Clock not reachable** or **Clock still not reachable** | Check the clock has batteries and is in range, and that nothing else has taken the pairing. Right after pairing, the first connection can take a few minutes and Second Hand keeps trying on its own. To try now, run **Reconnect to clock**. |
| The clock stays blank | Say a timer out loud to the kiosk itself. Second Hand only shows the kiosk's own timers. |

## 🙏 Credits

- The [Kiosk Satellite plugin template](https://github.com/jxlarrea/kiosk-satellite-plugin-hello-world) by jxlarrea.
- The Echo Wall Clock is Amazon's. Second Hand talks to it over Bluetooth and doesn't change the clock.

Not affiliated with or endorsed by Kiosk Satellite or Amazon.

## 💬 Feedback and contributing

- Found a bug, have an idea, or a kiosk it works on that isn't listed above? Open an issue.
- For developers: [building and changing it](docs/DEVELOPING.md).

🤖 Built with a lot of help from Claude, who worked with me on most of the design, code, tests and docs.

Code is Apache-2.0 (see [LICENSE](LICENSE) and [NOTICE](NOTICE)).
