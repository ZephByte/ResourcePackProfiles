# Changelog

## Resource Pack Profiles - v1.0.0+26.2

> **First stable release.** Please report any issues on [GitHub](https://github.com/ZephByte/ResourcePackProfiles/issues).

First stable release of **Resource Pack Profiles** for Minecraft 26.2, a lightweight client-side Fabric mod that adds named load-order profiles to the resource pack screen.

### Changes
- **Custom icons** - profiles whose names differ only in spaces or symbols no longer overwrite each other's custom icon files
- **Export** - fixed a possible crash when exporting a profile while the profile list was being modified
- **File dialogs** - closing the profile or edit screen while an import/icon picker is open no longer reopens the dismissed screen

### Requirements
| | |
|---|---|
| Minecraft | 26.2 |
| Fabric Loader | 0.19.3+ |
| Fabric API | 0.152.2+26.2 |
| Fabric Language Kotlin | 1.13.12+kotlin.2.4.0 |
| Mod Menu | 20.0.0-beta.3 *(optional)* |

## Resource Pack Profiles - v1.0.0-beta.1+26.2

> **Beta release** - this is the first public build of the mod; expect rough edges and please report issues on [GitHub](https://github.com/ZephByte/ResourcePackProfiles/issues).

First public release of **Resource Pack Profiles** for Minecraft 26.2, a lightweight client-side Fabric mod that adds named load-order profiles to the resource pack screen.

### Features
- **Save profiles** - snapshot your current pack load order as a named profile with one click
- **Instant apply** - load any profile and have your packs reload automatically; no restarts needed
- **Live editing** - add, remove, and reorder packs within a profile; edits to the active profile re-apply automatically
- **Favorites** - star profiles to pin them to the top of the list
- **Custom icons** - assign your own image, or let the mod auto-generate a composite from your pack art
- **Import / Export** - share profiles as `.rpprofile` files (JSON with pack list + icon)
- **Missing pack detection** - flagged automatically; profiles still apply without the missing packs
- **Keyboard & narrator friendly** - full keyboard navigation and screen-reader support via Minecraft's native widgets

### Requirements
| | |
|---|---|
| Minecraft | 26.2 |
| Fabric Loader | 0.19.3+ |
| Fabric API | 0.152.2+26.2 |
| Fabric Language Kotlin | 1.13.12+kotlin.2.4.0 |
| Mod Menu | 20.0.0-beta.3 *(optional)* |
