# Third-party notices

Rattlegram Plus Plus is released under the BSD Zero Clause License (see [LICENSE](LICENSE)). It includes or uses the following third-party software.

## Included in the source

### libsbc — Bluetooth SBC audio codec
- Source: https://github.com/google/libsbc (commit recorded in `app/src/main/cpp/sbc/UPSTREAM.txt`)
- Location: `app/src/main/cpp/sbc/`
- License: Apache License 2.0 — full text in `app/src/main/cpp/sbc/LICENSE`
- Copyright Google LLC

### aicodix modem, code and dsp
- Sources: https://github.com/aicodix/modem, https://github.com/aicodix/code, https://github.com/aicodix/dsp
- Location: `app/src/main/cpp/`
- License: BSD Zero Clause License
- Copyright aicodix GmbH

### Terminus font (fancy header)
- Source: http://terminus-font.sourceforge.net
- License: SIL Open Font License

## Build dependencies (downloaded by Gradle)

### ZXing and zxing-android-embedded — QR codes
- Sources: https://github.com/zxing/zxing, https://github.com/journeyapps/zxing-android-embedded
- License: Apache License 2.0

### AndroidX and Material Components
- Source: https://developer.android.com
- License: Apache License 2.0

## Protocol reference

The BTech UV-Pro / Vero VR-N76 Bluetooth audio and control protocol details were learned from
[HTCommander](https://github.com/Ylianst/HTCommander) by Ylian Saint-Hilaire (Apache License 2.0).
The implementation in `RadioLink.java` is independent and does not contain HTCommander code.
