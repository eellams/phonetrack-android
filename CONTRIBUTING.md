# Contributing guide

> This repository is [lazzurs/phonetrack-android](https://github.com/lazzurs/phonetrack-android), a fork of
> [eneiluj/phonetrack-android](https://gitlab.com/eneiluj/phonetrack-android) that adds GitHub Actions CI/CD.
> For bugs or features in the app itself, please use the upstream tracker linked below. Use
> [this fork's issue tracker](https://github.com/lazzurs/phonetrack-android/issues) only for problems with this
> fork's build, release, or tooling setup.

## Submitting bug reports

If you find a bug, feel free to [open an issue](https://gitlab.com/eneiluj/phonetrack-android/issues). But please provide these information in the comment:

### Copy & Paste
    **Android version:** e. g. 7.1.2 Nougat

    **Device**: e. g. LG G4

    **System language**: English (US), German, ...

    **App version:** e. g. v0.0.6

    **App source:** self-built, project wiki, F-droid ?

    **Steps to reproduce:**
      1. open the app
      2. click on a log job
      3. use the top left back-arrow
      4. ...

## Commit messages (this fork)

Release notes and `CHANGELOG.md` are generated from commit messages, so they follow
[Conventional Commits](https://www.conventionalcommits.org), e.g. `fix: keep the alarm volume`
or `fix(security): only accept SMS from allowed senders`. CI checks it; the types and the release
process are in [RELEASING.md](RELEASING.md).

Please don't change the app version (`appVersionName` / `appVersionCode` in `gradle.properties`) in a
pull request: merging a new version publishes a release, so CI rejects that from forks. Releases are
prepared by the maintainers.

## Adding new features

If you want to contribute on the code, you can fork the project, commit your changes and make a pull request. For bigger features/changes, it's better to first [open an issue](https://gitlab.com/eneiluj/phonetrack-android/issues) and ask if this feature is needed or wanted – it would be very disappointing if you add a new cool feature and your pull request will be rejected by some reasons you didn't thought about.
