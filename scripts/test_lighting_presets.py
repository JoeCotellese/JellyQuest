# ABOUTME: Unit tests for the bpy-free parts of lighting_presets.py.
# ABOUTME: Verifies group classification and preset->energy resolution.
"""Run: python3 scripts/test_lighting_presets.py"""

import lighting_presets as lp

# Actual light names in cinema_standard_multiplex.blend
BLEND_LIGHTS = (
    [f"Sconce_Light_L_{i}" for i in range(6)]
    + [f"Sconce_Light_R_{i}" for i in range(6)]
    + ["CoveLight", "AmbientFill", "ScreenGlow",
       "ExitSign_Glow_Left", "ExitSign_Glow_Right"]
)


def test_group_classification():
    assert lp.group_for_light("Sconce_Light_L_0") == "sconces"
    assert lp.group_for_light("Sconce_Light_R_5") == "sconces"
    assert lp.group_for_light("CoveLight") == "cove"
    assert lp.group_for_light("AmbientFill") == "ambient_fill"
    assert lp.group_for_light("ScreenGlow") == "screen_glow"
    assert lp.group_for_light("ExitSign_Glow_Left") == "exit_glow"
    assert lp.group_for_light("SomeUnknownLight") is None


def test_movie_mode_resolves_all_blend_lights():
    energies, unmatched = lp.resolve_energies("movie_mode", BLEND_LIGHTS)
    assert unmatched == [], f"unexpected unmatched lights: {unmatched}"
    assert len(energies) == len(BLEND_LIGHTS)
    # All 12 sconces at the group energy
    for i in range(6):
        assert energies[f"Sconce_Light_L_{i}"] == 13.5
        assert energies[f"Sconce_Light_R_{i}"] == 13.5
    assert energies["ScreenGlow"] == 82.5
    assert energies["CoveLight"] == 1.35
    assert energies["AmbientFill"] == 0.75
    assert energies["ExitSign_Glow_Left"] == 0.6


def test_unknown_preset_raises():
    try:
        lp.resolve_energies("does_not_exist", BLEND_LIGHTS)
    except KeyError:
        return
    raise AssertionError("expected KeyError for unknown preset")


def test_screen_glow_ratio_matches_movie_mode():
    # 82.5 / 55 baseline == 1.5x, the tuned screen multiplier
    assert abs(lp.screen_glow_ratio("movie_mode") - 1.5) < 1e-9


if __name__ == "__main__":
    n = 0
    for name, fn in sorted(globals().items()):
        if name.startswith("test_") and callable(fn):
            fn()
            print(f"  ok  {name}")
            n += 1
    print(f"\n{n} tests passed")
