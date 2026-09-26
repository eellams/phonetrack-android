# PhoneTrack for Android

> [!NOTE]
> **This is a fork.** [lazzurs/phonetrack-android](https://github.com/lazzurs/phonetrack-android) is maintained by [@lazzurs](https://github.com/lazzurs) and tracks the upstream project at [eneiluj/phonetrack-android on GitLab](https://gitlab.com/eneiluj/phonetrack-android). All credit for PhoneTrack itself goes to [eneiluj](https://gitlab.com/eneiluj) and the upstream contributors — this fork exists to add GitHub Actions CI/CD (build + automated releases) and other maintenance around the original app. Please report bugs and feature requests for the app itself [upstream](https://gitlab.com/eneiluj/phonetrack-android/issues); use [this fork's issue tracker](https://github.com/lazzurs/phonetrack-android/issues) only for fork-specific build/release/tooling issues.

[![Build](https://github.com/lazzurs/phonetrack-android/actions/workflows/build.yml/badge.svg)](https://github.com/lazzurs/phonetrack-android/actions/workflows/build.yml)
[![Latest release](https://img.shields.io/github/v/release/lazzurs/phonetrack-android)](https://github.com/lazzurs/phonetrack-android/releases)

An android app to log locations to [PhoneTrack Nextcloud app](https://gitlab.com/eneiluj/phonetrack-oc) or any custom server.

[![Crowdin](https://d322cqt584bo4o.cloudfront.net/phonetrack/localized.svg)](https://crowdin.com/project/phonetrack)

This is still a beta version.

This app is originally a fork of [Nextcloud Notes for Android](https://github.com/stefan-niedermann/nextcloud-notes).
It is also largely insired by [µLogger for Android](https://github.com/bfabiszewski/ulogger-android) for the logging service part.
Many thanks to their developers :heart: !

What's different from other logging apps ?

PhoneTrack Android app can manage multiple configurations (called log jobs)
and log locations at different frequencies to different destinations in parallel.
This means you can simultaneously have :

* a low frequency log job which logs one point per hour (for example) to keep track of where you go
* a high frequency log job which logs one point every five seconds (for example) to record a hike and potentially share it for others to be able to see your position in real time
* a medium frequency log job which logs one point every five minutes (for example) to share with your friends to help them finding you if you got separated during a city walk

Log jobs are independent and can be individually activated.

PhoneTrack-Android can log to [PhoneTrack Nextcloud app](https://gitlab.com/eneiluj/phonetrack-oc) with a `PhoneTrack log job`
or to any server which can receive HTTP GET or POST requests with a `custom log job`.

This app is pretty simple so it has a low inpact on battery life. There has been a few comparisons with other loggers in [#8](https://gitlab.com/eneiluj/phonetrack-android/issues/8#note_130524605) and in [#175 of Nextcloud PhoneTrack](https://gitlab.com/eneiluj/phonetrack-oc/issues/175#note_130338568).

## Features
* Create multiple log jobs to run in the background to log positions to different places
* Send locations to PhoneTrack Nextcloud app
* Send locations to custom server with GET or POST requests
* Store positions when network is not available
* Remote control by SMS:
    * get position
    * activate alarm
    * start all logjobs
    * stop all logjobs
    * create a logjob
* Launch on system startup
* Display devices of a Nextcloud PhoneTrack session on a map
* Dark theme
* Translated in many languages on [Crowdin](https://crowdin.com/project/phonetrack)


## :link: Requirements
* Android >= 4.3

If you want to log to Nextcloud PhoneTrack :

* [Nextcloud](https://nextcloud.com/) instance running
* [Nextcloud PhoneTrack](https://gitlab.com/eneiluj/phonetrack-oc) app enabled

## User documentation

[Over there](https://gitlab.com/eneiluj/phonetrack-android/wikis/userdoc)

## Install

Requires **Android 14 or newer** (the Android versions that still get security updates; see
[RELEASING.md](RELEASING.md#supported-android-versions)). Release 0.2.0 still runs on Android 8.0+.


* APK Direct download : [this fork's GitHub Releases](https://github.com/lazzurs/phonetrack-android/releases) (built automatically by GitHub Actions on every tag). Each release has `phonetrack-<version>-normal.apk` (use this one), `phonetrack-<version>-play.apk`, and `SHA256SUMS.txt` to verify the download. How releases are made: [RELEASING.md](RELEASING.md)
* Upstream also publishes builds via [Gitlab CI artifacts](https://gitlab.com/eneiluj/phonetrack-android/pipelines)
* The original app (a separate app, `net.eneiluj.nextcloud.phonetrack`) is on F-Droid: [![PhoneTrack App on fdroid.org](https://gitlab.com/eneiluj/phonetrack-android/wikis/uploads/57bb389a0c40f5cb81dc1ae21a314adb/fd.png)](https://f-droid.org/packages/net.eneiluj.nextcloud.phonetrack/)

### A note on release signing

Tagged releases from this fork are signed with a dedicated key held only as encrypted GitHub Actions secrets (`RELEASE_KEYSTORE_BASE64` / `RELEASE_KEYSTORE_PASSWORD`) on this repo — it is never committed to git. This keeps the signature consistent across successive releases so APKs install as upgrades over one another. It is **not** the same key as upstream's or F-Droid's builds, and since 0.2.0 this fork also has its own application ID, `io.github.lazzurs.phonetrack` (the original is `net.eneiluj.nextcloud.phonetrack`): the two are separate apps that can be installed side by side. Nothing is shared between them, so set the fork up again before removing the original. If you build release APKs yourself without those secrets set, Gradle falls back to signing with your local debug key instead (see [`app/build.gradle.kts`](app/build.gradle.kts)), which is fine for local testing but won't match the signature of the published releases.

## Build

If you want to build this app yourself, clone this repository :

``` bash
git clone --recurse-submodules https://github.com/lazzurs/phonetrack-android
```

or download the [main branch latest archive](https://github.com/lazzurs/phonetrack-android/archive/refs/heads/main.zip).

Then open/import the project in Android studio and build it, or run `./gradlew assembleNormalDebug` from the command line. See [`.github/workflows/build.yml`](.github/workflows/build.yml) for the exact CI build steps.

## Donate

* [Donate with Liberapay : ![Donate with Liberapay](https://liberapay.com/assets/widgets/donate.svg)](https://liberapay.com/eneiluj/donate)
* [Donate with Paypal : <img src="https://gitlab.com/eneiluj/phonetrack-android/wikis/uploads/3ef4665a2c25662265681d6304f71b43/paypal-donate-button.png" width="80"/>](https://www.paypal.com/cgi-bin/webscr?cmd=_s-xclick&hosted_button_id=66PALMY8SF5JE)


## :eyes: Screenshots

[1<img src="https://gitlab.com/eneiluj/phonetrack-android/wikis/uploads/b29ea339fbb3a1dede386847e710fb49/1.png" width="300"/>](https://gitlab.com/eneiluj/phonetrack-android/wikis/uploads/b29ea339fbb3a1dede386847e710fb49/1.png)
[2<img src="https://gitlab.com/eneiluj/phonetrack-android/wikis/uploads/c21cf9f1c58bd2341073b9a910d0d5ae/2.png" width="300"/>](https://gitlab.com/eneiluj/phonetrack-android/wikis/uploads/c21cf9f1c58bd2341073b9a910d0d5ae/2.png)
[3<img src="https://gitlab.com/eneiluj/phonetrack-android/wikis/uploads/bfbbe80b0dd35303f81154a727b732d3/3.png" width="300"/>](https://gitlab.com/eneiluj/phonetrack-android/wikis/uploads/bfbbe80b0dd35303f81154a727b732d3/3.png)
[4<img src="https://gitlab.com/eneiluj/phonetrack-android/wikis/uploads/cd1110b94bcd634fbbb314a590a7e3e9/4.png" width="300"/>](https://gitlab.com/eneiluj/phonetrack-android/wikis/uploads/cd1110b94bcd634fbbb314a590a7e3e9/4.png)
[5<img src="https://gitlab.com/eneiluj/phonetrack-android/wikis/uploads/82ab4bba86fab9f3e09ace3875ed2e67/5.png" width="300"/>](https://gitlab.com/eneiluj/phonetrack-android/wikis/uploads/82ab4bba86fab9f3e09ace3875ed2e67/5.png)
[6<img src="https://gitlab.com/eneiluj/phonetrack-android/wikis/uploads/2cea3c089c828540629246dc6f394ad5/6.png" width="300"/>](https://gitlab.com/eneiluj/phonetrack-android/wikis/uploads/2cea3c089c828540629246dc6f394ad5/6.png)

## :notebook: License
This project is licensed under the [GNU GENERAL PUBLIC LICENSE](/LICENSE).

## :twisted_rightwards_arrows: Alternatives

You can log to PhoneTrack with other Android apps (and one IOS one). Check out the [list of compatible loggers in PhoneTrack wiki](https://gitlab.com/eneiluj/phonetrack-oc/wikis/userdoc#logging-methods)
