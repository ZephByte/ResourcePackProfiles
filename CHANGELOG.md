# Changelog

## Resource Pack Profiles - v1.1.0

Adds a **pack update checker** to **Resource Pack Profiles** for Minecraft 26.1, 26.2, and 26.3.

### Changes
- **Update checker** - check a single pack (in the profile editor), a whole profile, or every profile for newer versions on Modrinth using the new ↻ buttons
- **Safe updates** - updated packs are downloaded as new files and saved as an `(updated)` copy of the profile, so your existing packs and profiles keep working
- **Verified downloads** - files are only downloaded from Modrinth and checked against their published SHA-512 before use
- **Profile editor** - returning from the update screen keeps your unsaved edits

Packs that are not on Modrinth, and unzipped folder packs, are skipped. Nothing is checked until you click a ↻ button.

### Requirements
| Minecraft | Fabric Loader | Fabric API | Fabric Language Kotlin | Mod Menu *(optional)* |
|---|---|---|---|---|
| 26.3 | 0.19.5+ | 0.161.0+26.3 | 1.14.1+kotlin.2.4.20 | 21.0.0-beta.1 |
| 26.2 | 0.19.3+ | 0.152.2+26.2 | 1.13.12+kotlin.2.4.0 | 20.0.0-beta.3 |
| 26.1 - 26.1.2 | 0.19.3+ | 0.145.1+26.1 | 1.13.12+kotlin.2.4.0 | 18.0.0-beta.1 |

## Resource Pack Profiles - v1.0.0+26.3

Minecraft 26.3 compatibility release of **Resource Pack Profiles**.

### Changes
- Updated the Fabric Loader, Fabric API, Fabric Language Kotlin, and Mod Menu development dependencies for Minecraft 26.3
- Migrated resource-pack icon loading and native file dialogs to Minecraft 26.3's client APIs

### Requirements
| | |
|---|---|
| Minecraft | 26.3 |
| Fabric Loader | 0.19.5+ |
| Fabric API | 0.161.0+26.3 |
| Fabric Language Kotlin | 1.14.1+kotlin.2.4.20 |
| Mod Menu | 21.0.0-beta.1 *(optional)* |

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
