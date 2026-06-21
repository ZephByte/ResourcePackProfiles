# Resource Pack Profiles

[![GitHub release](https://img.shields.io/github/v/release/ZephByte/ResourcePackProfiles?label=release&color=blue)](https://github.com/ZephByte/ResourcePackProfiles/releases/latest)
[![Modrinth](https://img.shields.io/modrinth/v/resource-pack-profiles?label=modrinth&logo=modrinth&color=00AF5C)](https://modrinth.com/mod/resource-pack-profiles)
[![License: MIT](https://img.shields.io/github/license/ZephByte/ResourcePackProfiles)](LICENSE)
[![Fabric](https://img.shields.io/badge/mod%20loader-fabric-blue)](https://fabricmc.net/)

A lightweight **client-side Fabric mod** that lets you save, manage, and share named **resource pack load order profiles** — switch between completely different pack setups in seconds, right from the resource pack screen.

---

![Profile screen showing a list of saved profiles with pack counts and custom icons](https://raw.githubusercontent.com/ZephByte/ResourcePackProfiles/main/docs/screenshot.png)

---

## Features

- **Profile Saving** — Snapshot your current resource pack load order as a named profile with a single click.
- **Instant Loading** — Apply any saved profile and have your packs reload automatically; no restarts needed.
- **Profile Editing** — Add, remove, and reorder packs within a profile without touching your main load order. Edit the *active* profile and it re-applies automatically, so your changes stay live.
- **Favorites** — Star profiles to pin them to the top of the list.
- **Custom Icons** — Assign a custom image to any profile; auto-generates a composite icon from your pack art if none is set.
- **Import / Export** — Share profiles as `.rpprofile` files — a single JSON that includes your pack list and custom icon.
- **Missing Pack Detection** — Profiles with unavailable packs are flagged automatically and can still be applied without the missing packs.
- **Keyboard & Narrator Friendly** — Built on Minecraft's native widgets, so the profile and pack lists support full keyboard navigation and screen-reader narration.

---

## Download

- **[Modrinth](https://modrinth.com/mod/resource-pack-profiles)** (recommended)
- **[GitHub Releases](https://github.com/ZephByte/ResourcePackProfiles/releases)**

---

## Installation

1. Install [Fabric Loader](https://fabricmc.net/) for your Minecraft version.
2. Install [Fabric API](https://modrinth.com/mod/fabric-api).
3. Install [Fabric Language Kotlin](https://modrinth.com/mod/fabric-language-kotlin).
4. Drop the mod `.jar` into your `.minecraft/mods` folder.
5. (Optional) Install [Mod Menu](https://modrinth.com/mod/modmenu) to open the profiles screen from the mods list.

**Client-side only** — no server installation needed.

---

## Compatibility

| Dependency | Version |
|---|---|
| Minecraft | `26.1.2` |
| Fabric Loader | `0.19.3+` |
| Fabric API | `0.152.1+26.1.2` |
| Fabric Language Kotlin | `1.13.12+kotlin.2.4.0` |
| Mod Menu | `18.0.0-beta.1` *(optional)* |

---

## Usage

Open the **Resource Pack Profiles** screen via the **Profiles** button on the vanilla resource pack screen *(left of Open Pack Folder)*, or via [Mod Menu](https://modrinth.com/mod/modmenu).

| Action | How |
|---|---|
| Save current load order | Type a name → click **Save Current** |
| Load a profile | Click anywhere on the profile's row |
| Edit packs in a profile | Click ✎ |
| Delete a profile | Click ✕ |
| Favorite a profile | Click ★ / ☆ |
| Set / change a profile icon | Open Edit (✎) → click **Icon…** |
| Remove a custom icon | Open Edit (✎) → click ✕ next to the icon |
| Export a profile | Open Edit (✎) → click the export button (bottom right) |
| Import a profile | Click the import button (bottom right of the profile list) |

In the editor, click a pack in the **Available** column to add it to the top of **Selected**. Use the arrow overlay on a selected pack's icon to remove it or reorder it. With keyboard focus, use **Enter** to add/remove and **Shift+Up/Down** to reorder.

---

## Profile File Format

Profiles are saved to:

```
config/resourcepackprofiles.json
```

Custom icons are stored in:

```
config/resourcepackprofiles/icons/
```

Exported `.rpprofile` files are standard JSON:

```json
{
  "name": "My Profile",
  "packIds": ["file/Pack1", "file/Pack2"],
  "favorite": false,
  "customIcon": "<base64 encoded PNG, or null>"
}
```

---

## Building from Source

```bash
git clone https://github.com/ZephByte/ResourcePackProfiles.git
cd ResourcePackProfiles
./gradlew build
```

The built jar will be in `build/libs/`.

---

## License

MIT - free to use, modify, and redistribute. See [LICENSE](LICENSE).

---

## Author

Made by [ZephByte](https://github.com/ZephByte).
