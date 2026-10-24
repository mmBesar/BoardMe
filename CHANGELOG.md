# Changelog

All notable changes to BoardMe are listed here, newest first.
Format based on [Keep a Changelog](https://keepachangelog.com/).

## [Unreleased]

### Fixed
- Home-screen widget: tapping it now opens the app (CI compile error with `actionStartActivity`).

## [0.1.0] - not released yet

### Added
- Connect to a Kanboard server with a username and personal access token.
- Works with plain `http://` (after a warning) and with self-signed `https://` certificates (fingerprint shown, then only that exact certificate is trusted).
- Projects, board columns (swipe between them), tasks: create, edit, move, close, reopen, delete, due dates.
- Archive projects, read cached boards offline.
- Home-screen widget per project.
- Local-only mode and JSON backup / restore.
- Catppuccin colours (Latte / Macchiato, Mauve accent), Android 6.0 and newer.
- GitHub Actions: CI (tests, lint, debug APK) and signed release on `v*` tags.
