# Changelog

All notable changes to this app are documented in this file. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the app uses
[Semantic Versioning](https://semver.org/spec/v2.0.0.html) as described in the README's
[Releasing](README.md#releasing) section.

## [Unreleased]

## [1.1.1] - 2026-10-28

It works with teamrowing-remote 1.0.0 and later.

### Changed

- An own launcher icon: the tempo above a progress bar, in place of the default Android icon.

## [1.1.0] - 2026-10-28

It works with teamrowing-remote 1.0.0 and later. The next track without internet access on the tablet
needs both apps in version 1.1.0.

### Added

- The next track works without internet access on the tablet: the remote app sends Spotify's
  queue over Bluetooth, and the display then makes no Spotify requests. With an older remote
  app, the display fetches the queue itself as before.

### Changed

- The app's version comes from the release tag, and builds after a release show the number of
  commits since then and the commit, e.g. 1.0.0-3-gabc1234.

## [1.0.0] - 2026-10-26

The first version. It works with teamrowing-remote 1.0.0.

### Added

- Full-screen team display: tempo, title and artist of the current track, progress bar with
  position and duration, and the next track.
- Spotify login (Authorization Code flow with PKCE) and polling of the playback state and queue.
- Session preparation: BPM lookup of a playlist's tracks (ReccoBeats, Deezer as fallback), with
  corrections (÷2, ×2, −1, +1, typed value, "No tempo").
- Display settings with live preview: shown elements, tempo size, current/next split, color scheme
  and accent color.
- Tablet mode: the screen stays on and the system bars are hidden.
- Sync of the BPM values with teamrowing-remote over Bluetooth, and instant track changes, pause
  and seeking from the remote app's playback updates.
- Spotify's autoplay tracks after the end of a playlist are not shown as next track.

[Unreleased]: https://github.com/martingregoire/teamrowing-display/compare/v1.1.1...HEAD
[1.1.1]: https://github.com/martingregoire/teamrowing-display/compare/v1.1.0...v1.1.1
[1.1.0]: https://github.com/martingregoire/teamrowing-display/compare/v1.0.0...v1.1.0
[1.0.0]: https://github.com/martingregoire/teamrowing-display/releases/tag/v1.0.0
