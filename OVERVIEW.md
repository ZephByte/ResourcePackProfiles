# Resource Pack Profiles — Feature Outline

A Fabric client-side mod for Minecraft that allows users to save, load, and manage named resource pack load order profiles.

---

## Core Functionality

- Save the current resource pack load order as a named profile
- Load a saved profile, replacing the current load order
- Rename, edit, and delete existing profiles
- Unlimited profiles per user

---

## UI / Integration

- **Primary goal:** Inject a profiles UI directly into the vanilla resource pack menu (a button like "Manage Profiles" or a dropdown)
- **Fallback:** A dedicated screen accessible via Mod Menu if vanilla menu injection proves too complex or unstable
- Profile list should display the profile name and ideally a summary of what packs are included

---

## Profile Management

- Each profile stores an ordered list of active resource packs
- Profiles are saved locally to a JSON config file
- When loading a profile, the mod applies the saved load order and refreshes the resource packs automatically (no manual restart required if possible)

### Editing a Profile
- User can overwrite an existing profile with the current load order, or open the profile to manually reorder/remove packs from it directly
- Prompts confirmation before overwriting to avoid accidental loss

### Deleting a Profile
- Prompts a confirmation dialog ("Are you sure you want to delete [profile name]? This cannot be undone.") before permanently removing it from the JSON

---

## Edge Cases

### Missing Pack on Load
If a pack listed in a profile is not found, show a warning dialog listing each missing pack:

> "The following packs could not be found: [pack name]"

The user is given two options:
1. **Load anyway** — loads the profile without the missing packs
2. **Cancel** — aborts the profile load, leaving the current load order unchanged

If the user chooses to load anyway, a follow-up option is offered to **update the profile**, permanently removing the missing packs from the JSON.

### Duplicate Profile Names
Prevent saving or prompt to overwrite if a profile name already exists.

### Empty Profile
Warn the user if they attempt to save a profile with no active packs.

---

## Technical Details

- **Modloader:** Fabric
- **Publishing Platform:** Modrinth
- **Mod Menu:** Compatibility for profile access as a fallback UI
- **Resource Pack Reload:** Hook into Minecraft's resource pack reload system for seamless switching
- **Config Storage:** JSON file stored in `.minecraft/config/` or `.minecraft/resourcepackprofiles/`