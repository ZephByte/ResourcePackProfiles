# Resource Pack Profiles

A lightweight **client-side Fabric mod** that lets you save, manage, and share named **resource pack load order profiles** — switch between completely different pack setups in seconds.

Just drop it into your `.minecraft/mods` folder and access it from the resource pack screen.

---

## 🔧 Features

- 💾 **Profile Saving:** Snapshot your current resource pack load order as a named profile with a single click.
- 📂 **Instant Loading:** Apply any saved profile and have your packs reload automatically — no restarts needed.
- ✏️ **Profile Editing:** Add, remove, and reorder packs within a profile without touching your main load order. Edit the *active* profile and it re-applies automatically, so your changes stay live.
- ⭐ **Favorites:** Star profiles to pin them to the top of the list.
- 🖼️ **Custom Icons:** Assign a custom image to any profile; auto-generates a composite icon from your pack art if none is set.
- 📤 **Import / Export:** Share profiles as `.rpprofile` files — a single JSON file that includes your pack list and custom icon.
- ⚠️ **Missing Pack Detection:** Profiles with unavailable packs are flagged automatically and can still be applied without the missing packs.
- ⌨️ **Keyboard & Narrator Friendly:** The profile and pack lists are built on Minecraft's native widgets, so they support keyboard navigation and screen-reader narration.

---

## 🗂️ Profile Files

Profiles are saved to:

```
config/resourcepackprofiles.json
```

Custom icons are stored in:

```
config/resourcepackprofiles/icons/
```

Exported profiles use the `.rpprofile` format:

```json
{
  "name": "My Profile",
  "packIds": ["file/Pack1", "file/Pack2"],
  "favorite": false,
  "customIcon": "<base64 encoded PNG or null>"
}
```

---

## 💻 Usage

Open the **Resource Pack Profiles** screen via the **Profiles** button on the vanilla resource pack screen (left of *Open Pack Folder*), or via [Mod Menu](https://modrinth.com/mod/modmenu).

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

In the editor, click a pack in the **Available** column to add it to the top of **Selected**, and use the arrows on a selected pack's icon to remove it or move it up/down the load order.

---

## 🔌 Compatibility

- Minecraft: `26.1.2`
- Fabric Loader: `0.19.3+`
- Fabric API: `0.152.1+26.1.2`
- Fabric Language Kotlin: `1.13.12+kotlin.2.4.0` (required)
- [Mod Menu](https://modrinth.com/mod/modmenu): `18.0.0-beta.1` (optional — only if you want to open the screen from Mod Menu)
- **Client-side only** — no server-side installation needed

---

## 📦 Installation

1. Install [Fabric Loader](https://fabricmc.net/) for your Minecraft version.
2. Install [Fabric API](https://modrinth.com/mod/fabric-api).
3. Install [Fabric Language Kotlin](https://modrinth.com/mod/fabric-language-kotlin).
4. Drop the `ResourcePackProfiles-x.x.x.jar` into your `.minecraft/mods` folder.
5. (Optional) Install [Mod Menu](https://modrinth.com/mod/modmenu) to open the profiles screen from the mods list.
6. Launch the game and open the resource pack screen to get started.

---

## 📜 License

MIT License — free to use, modify, and distribute.

---

## 👤 Author

Developed by [ZephByte](https://github.com/zephbyte)
