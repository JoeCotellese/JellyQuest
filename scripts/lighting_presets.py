# ABOUTME: Loads versioned theater lighting presets and applies them in Blender.
# ABOUTME: Shared by preview_render.py and bake_lightmaps.py so the look is defined once.
"""
Versioned lighting presets for the JellyQuest theater.

A preset maps light GROUPS to absolute energies (Watts). Group membership is
derived from light-object name prefixes (GROUP_PREFIXES) so the 12 sconces stay
one tunable unit. The pure functions here (load_presets, group_for_light,
resolve_energies) have no bpy dependency and are unit-tested; apply_preset needs
Blender and sets each light's energy on the current scene.

The .blend stays the visual reference and is never saved by these scripts —
apply_preset mutates in-memory energies only.
"""

import json
import os

DEFAULT_PRESET_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                   "lighting_presets.json")

# Light-object name prefix → group. Longest match wins so more specific prefixes
# (none currently overlap, but keep the rule explicit) take precedence.
GROUP_PREFIXES = {
    "ScreenGlow": "screen_glow",
    "Sconce_Light": "sconces",
    "CoveLight": "cove",
    "AmbientFill": "ambient_fill",
    "ExitSign_Glow": "exit_glow",
}


def load_presets(path=None):
    """Load and return the presets file as a dict."""
    with open(path or DEFAULT_PRESET_PATH) as f:
        return json.load(f)


def group_for_light(light_name):
    """Return the group a light belongs to, or None if it matches no prefix."""
    best = None
    for prefix, group in GROUP_PREFIXES.items():
        if light_name.startswith(prefix) and (best is None or len(prefix) > len(best[0])):
            best = (prefix, group)
    return best[1] if best else None


def resolve_energies(preset_name, light_names, path=None):
    """Map each light name to its preset energy.

    Returns (energies, unmatched) where energies is {light_name: watts} for
    lights whose group is in the preset, and unmatched is the list of light
    names that matched no group (caller should surface these, not swallow them).
    """
    data = load_presets(path)
    if preset_name not in data["presets"]:
        raise KeyError(f"Unknown preset '{preset_name}'. "
                       f"Available: {sorted(data['presets'])}")
    preset = data["presets"][preset_name]
    energies, unmatched = {}, []
    for name in light_names:
        group = group_for_light(name)
        if group is not None and group in preset:
            energies[name] = preset[group]
        else:
            unmatched.append(name)
    return energies, unmatched


def screen_glow_ratio(preset_name, path=None):
    """Preset ScreenGlow energy relative to the authored baseline. Lets the
    preview scale the visible movie-screen brightness so it tracks the light."""
    data = load_presets(path)
    base = data["baseline_reference"]["screen_glow"]
    return data["presets"][preset_name]["screen_glow"] / base


def apply_preset(preset_name, path=None, log=print):
    """Set every scene light's energy from the named preset (Blender only)."""
    import bpy  # local import so the pure functions stay bpy-free / testable

    light_names = [o.name for o in bpy.data.objects if o.type == "LIGHT"]
    energies, unmatched = resolve_energies(preset_name, light_names, path)
    for obj in bpy.data.objects:
        if obj.name in energies:
            obj.data.energy = energies[obj.name]
    log(f"[preset] Applied '{preset_name}' to {len(energies)} lights")
    if unmatched:
        log(f"[preset] WARNING: {len(unmatched)} lights matched no group: {unmatched}")
    return energies, unmatched
