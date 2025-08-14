# Rayhunter Companion

This app allows you to store a list of WiFi networks that run
[Rayhunter](https://github.com/EFForg/rayhunter), and connect to each of them
to open Rayhunter's admin UI in a WebView.

The only advantage of this app over just using your system's WiFi menu and a
normal browser is that this app forces Android to disable captive portal
detection and allows you to use your phone's regular cell connection for other
traffic. This is particularly useful if your Rayhunter device does not have an
active SIM card.

## How to build

Plug your phone in and:

```bash
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk 
```

## Roadmap

* Rename the app from `com.rayhunter.companion` to something better.
* Embed the Linux installer into this Android app, at least for TP-Link where
  the entire installation does not require USB. We already have aarch64-ubuntu
  installer binaries, and they work fine in Termux.

## License

Licensed under GPLv3, see LICENSE
