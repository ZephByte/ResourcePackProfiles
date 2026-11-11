# Changelog

## Resource Pack Profiles - v2.0.0

**Resource Pack Profiles** becomes a resource pack manager: a redesigned interface, pack updates, and tools to keep your pack folder tidy. For Minecraft 26.1, 26.2, and 26.3. Existing profiles and `.rpprofile` files keep working.

### Changes
- **Replaces the vanilla pack screen** - Options > Resource Packs now opens Resource Pack Profiles directly; turn this off under **Settings** to get the vanilla screen with a Profiles button instead
- **New interface** - one screen with your profiles on the left and the selected profile's packs and actions on the right, replacing the separate list and edit screens
- **Edit in place** - add, remove, and drag to reorder packs; changes save immediately, with an undo for the last one
- **Switch packs off** - untick a pack to skip it in a profile while keeping its place in the load order
- **All Packs view** - see every installed pack and how many profiles use it, and move unused packs to a trash folder (undoable until you empty the trash)
- **Update checker** - packs are checked for newer versions on Modrinth once per game session (you are asked first, and can turn it off in Settings); tick the updates you want, or **Select All Updates**, then click **Update N Selected**. A refresh button checks again on demand
- **Choose a version** - a pack's version list shows every version, so you can pick an older or newer one, or switch back to the one you had
- **Safe updates** - updating switches the profile to the new file and keeps the old one, so other profiles are unaffected; Update All can save a backup copy of the profile first
- **Download missing packs** - importing a profile offers to download the packs you don't have from Modrinth, with a progress bar and a hover list of what is downloading; the same button is on any profile with missing packs. If a pack's recorded version no longer exists, use the arrow on that pack to pick another version, or search Modrinth for it. Profiles remember each pack's Modrinth project, so this works for profiles shared with you too (exported by 2.0 or later)
- **Browse Modrinth** - search and install resource packs from inside the game, with filters for category, resolution and game version; opened from a profile, a pack can be installed and added to it in one click
- **Compatibility warnings** - packs made for an older or newer version of Minecraft are flagged
- **Automatic switching (optional)** - while in a world or on a server, choose a profile to apply whenever you join it; off unless you set it up
- **Search, drag and drop, shortcuts** - filter profiles by name or pack, drop `.rpprofile` or `.zip` files onto the screen, and use Enter, F2, Ctrl+F, Ctrl+D, and Ctrl+Z

Editing the active profile no longer reloads your packs on every change; click **Apply Changes** when you are done. Downloads only come from Modrinth and are checked against their published SHA-512. Packs that are not on Modrinth, and unzipped folder packs, are skipped by the update checker.

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
