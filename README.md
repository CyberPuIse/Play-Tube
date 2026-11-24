# PlayTube

A lightweight, anonymous YouTube client for Android (Kotlin, Material 3, NewPipeExtractor).

- No Google account, no API keys, no tracking
- Package: `com.play.tube`

## Build

```bash
./gradlew assembleDebug      # debug APK -> app/build/outputs/apk/debug/
```

Without any extra files the app is signed with the standard Android debug key.
To sign with your own key, add `app/playtube.keystore` and `app/keystore.properties`
(`storePassword`, `keyAlias`, `keyPassword`). Never commit those two files.

## License and credits

PlayTube is free software, released under the **GNU General Public License v3.0**
(see [`LICENSE`](LICENSE)). It comes with **no warranty**.

PlayTube is a **modified version of [SparkTube](https://github.com/devfahim00/SparkTube) 1.4.2**
by devfahim00, modified by Hridoy Hasan Mishor in 2026. SparkTube is built on
[NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor) by the NewPipe team.

Third-party libraries and data sources are listed in [`NOTICE`](NOTICE) and inside the app
(Settings -> About app). Privacy: see [`PRIVACY.md`](PRIVACY.md).

Source code: https://github.com/CyberPuIse/Play-Tube

PlayTube is not affiliated with, endorsed by or sponsored by YouTube or Google.
YouTube is a trademark of Google LLC.
