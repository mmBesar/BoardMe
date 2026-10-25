# Changelog

All notable changes to BoardMe are listed here, newest first.
Format based on [Keep a Changelog](https://keepachangelog.com/).

## [Unreleased]

## [0.3.0] - not released yet

### Added
- Markdown in task descriptions (headings, bold, italic, strike-through, code, code blocks, lists, checkboxes, quotes, links) with a Preview button in the task editor; cards show a clean short preview.
- Right-to-left and mixed text: every title, description, field and search box follows the direction of its own text (Arabic/Hebrew mixed with English works).
- Settings now has Account (server, user, security, Sync now, change password or token, edit server address or user, log out), Appearance, and About.
- Option to hide descriptions on cards.

### Changed
- Login field is now "Password or personal access token" (both work).
- Drag and drop: a short sideways move switches column, and the dragged card's centre (not the finger) decides where it lands.
- "Connection" and "Switch mode" moved from the menu into Settings.

## [0.2.0] - 2026-10-25

### Added
- **Offline-first**: every change (create, edit, move, close, reopen, delete) is saved on the phone at once and sent to the server in the background, in order. If there is no connection it waits and is sent automatically when you are back online, even if the app was closed. A banner shows offline / waiting / problem status; cards waiting to sync are marked.
- Appearance settings: all four Catppuccin themes (Latte, Frappé, Macchiato, Mocha), Automatic, System colours (Android 12+), pure-black AMOLED option, all 14 Catppuccin accents for every theme, and a custom accent colour picker. The widget follows the same theme.
- Drag and drop: press and hold a task, drag it to reorder or into another column (the board scrolls and switches columns at the screen edges).
- Task colour and priority (shown on cards, editable, synced).
- Search tasks by title or description.
- "Sync now" and "Update token" (when the server rejects the saved login) and "Review" (when the certificate changed).
- Tasks created offline are never duplicated if a send is repeated (reference tag).

### Changed
- Moving a task keeps its swimlane and uses its exact position.
- Backup files are stricter (an empty `{}` is refused); size limit 8 MB.
- Database upgraded to version 2 (existing data is kept).

### Fixed
- Several problems found in the audit: certificate re-trust flow, token not saved after a failed test, race between sync and edits, strict column loading, cached credentials.
- Home-screen widget: tapping it now opens the app (CI compile error with `actionStartActivity`).

## [0.1.0] - 2026-10-24

### Added
- Connect to a Kanboard server with a username and personal access token.
- Works with plain `http://` (after a warning) and with self-signed `https://` certificates (fingerprint shown, then only that exact certificate is trusted).
- Projects, board columns (swipe between them), tasks: create, edit, move, close, reopen, delete, due dates.
- Archive projects, read cached boards offline.
- Home-screen widget per project.
- Local-only mode and JSON backup / restore.
- Catppuccin colours (Latte / Macchiato, Mauve accent), Android 6.0 and newer.
- GitHub Actions: CI (tests, lint, debug APK) and signed release on `v*` tags.
