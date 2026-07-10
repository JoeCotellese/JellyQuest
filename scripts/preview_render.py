# ABOUTME: Fast headless seat-POV preview render for tuning theater lighting.
# ABOUTME: Renders live Cycles (no bake, no device) with Standard view transform.
"""
Blender script: Fast lighting-preview render from the viewer's seat.

Purpose: tune light energies in seconds instead of waiting ~24 min for a full
bake + deploy. Renders the LIVE scene (surface color x current lights) from the
Multiplex "Middle" seat, using the Standard view transform so the PNG is a
faithful proxy for what the UNLIT_SHADER shows on device (never AgX — that view
transform tonemaps values the device pipeline shows raw).

Run from terminal:
    /Applications/Blender.app/Contents/MacOS/Blender \
        assets/cinema_standard_multiplex.blend \
        --background --python scripts/preview_render.py -- --samples 24

Args (after "--"):
    --samples N   Cycles samples (default 24; low = fast, denoiser cleans it up)
    --res WxH     Resolution (default 1280x720)
    --boost F     Multiply all light energies by F before rendering (default 1.0;
                  matches bake_lightmaps.py --boost so previews and bakes agree)
    --out PATH    Output PNG path (default: scratchpad preview_seat.png)

Albedo note: the raw .blend wires each material's BakeTarget (old lightmap) into
Base Color. Rendering that live would show the previous bake as albedo. So we
reuse bake_lightmaps.py's material setup (surface textures where present, BSDF
default color otherwise) to render true color x light. The .blend is never
saved, so these in-memory edits are discarded when Blender exits.
"""

import bpy
import os
import sys
import time

import mathutils

# Make sibling scripts importable, then reuse the bake pipeline's albedo setup
# so preview albedo matches bake albedo exactly (one source of truth).
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import bake_lightmaps as bake  # noqa: E402
import lighting_presets as presets  # noqa: E402

# Viewer POV — mirrors the app's Multiplex "Middle" seat (TheaterExperiences.kt:
# distanceM = 14.63). +Y is into the room away from the screen (screen at Y~0);
# +Z is up. Eye height is found by raycasting onto the raked floor at the seat.
SEAT_DISTANCE_M = 14.63
EYE_HEIGHT_M = 1.2          # seated eye height above the local floor
CAMERA_HFOV_DEG = 85.0      # wide, approximating the immersive in-headset view
CAMERA_NAME = "PreviewCam_Seat"

# The screen-glow area light stands in for the movie screen emitting into the
# room (the baked lightmap is static, so the runtime video panel can't light the
# room — this light bakes that glow in). Everything else is "house" lighting.
SCREEN_LIGHT_NAMES = {"ScreenGlow"}


def log(msg):
    print(f"[preview] {msg}", flush=True)


def parse_args():
    argv = sys.argv
    a = argv[argv.index("--") + 1:] if "--" in argv else []
    opts = {"samples": 24, "res": (1280, 720), "boost": 1.0,
            "house": 1.0, "screen": 1.0, "preset": None,
            "show_screen": True, "aim": "screen", "out": None}
    i = 0
    while i < len(a):
        if a[i] == "--samples" and i + 1 < len(a):
            opts["samples"] = int(a[i + 1]); i += 2
        elif a[i] == "--preset" and i + 1 < len(a):
            opts["preset"] = a[i + 1]; i += 2
        elif a[i] == "--res" and i + 1 < len(a):
            w, h = a[i + 1].lower().split("x"); opts["res"] = (int(w), int(h)); i += 2
        elif a[i] == "--boost" and i + 1 < len(a):
            opts["boost"] = float(a[i + 1]); i += 2
        elif a[i] == "--house" and i + 1 < len(a):
            opts["house"] = float(a[i + 1]); i += 2
        elif a[i] == "--screen" and i + 1 < len(a):
            opts["screen"] = float(a[i + 1]); i += 2
        elif a[i] == "--no-screen":
            opts["show_screen"] = False; i += 1
        elif a[i] == "--aim" and i + 1 < len(a):
            opts["aim"] = a[i + 1]; i += 2
        elif a[i] == "--out" and i + 1 < len(a):
            opts["out"] = a[i + 1]; i += 2
        else:
            i += 1
    return opts


def apply_light_groups(house_mult, screen_mult):
    """Scale house lights and the screen glow independently. Discarded on exit
    (the .blend is never saved), so this only affects this render."""
    if house_mult == 1.0 and screen_mult == 1.0:
        return
    log(f"Light groups: house x{house_mult}, screen x{screen_mult}")
    for obj in bpy.data.objects:
        if obj.type != "LIGHT":
            continue
        mult = screen_mult if obj.name in SCREEN_LIGHT_NAMES else house_mult
        obj.data.energy *= mult


def setup_albedo():
    """Wire true surface albedo (textures / BSDF defaults) like the bake does,
    so the render shows color x light rather than the stale baked lightmap."""
    log("Setting up albedo (reusing bake material setup)...")
    for mat_name, _obj_names, tex_file in bake.BAKE_LIST:
        mat = bpy.data.materials.get(mat_name)
        if not mat:
            continue
        if tex_file:
            bake.add_surface_texture(mat, tex_file)
        else:
            bake.ensure_default_color_on_base(mat)


def show_movie_screen(show, screen_mult):
    """Render the Screen as a glowing movie surface, or hide it.

    On device the screen is a runtime video panel and the room's screen light is
    baked via the ScreenGlow area light. So here the glowing Screen is made
    CAMERA-ONLY (no diffuse/glossy/transmission/scatter contribution): it shows
    the movie-screen glow to the viewer without adding bounce light the device
    never bakes. Room lighting stays driven by ScreenGlow. Brightness tracks the
    --screen multiplier so screen and its baked spill move together.
    """
    screen = bpy.data.objects.get("Screen")
    if not screen:
        return
    if not show:
        screen.hide_render = True
        return
    screen.hide_render = False

    mat = bpy.data.materials.get("PreviewMovieScreen")
    if mat is None:
        mat = bpy.data.materials.new("PreviewMovieScreen")
        mat.use_nodes = True
        nt = mat.node_tree
        nt.nodes.clear()
        emit = nt.nodes.new("ShaderNodeEmission")
        out = nt.nodes.new("ShaderNodeOutputMaterial")
        nt.links.new(emit.outputs["Emission"], out.inputs["Surface"])
    emit = mat.node_tree.nodes["Emission"]
    emit.inputs["Color"].default_value = (0.9, 0.92, 1.0, 1.0)  # ScreenGlow tint
    emit.inputs["Strength"].default_value = 2.5 * screen_mult

    screen.data.materials.clear()
    screen.data.materials.append(mat)

    # Camera-only: seen, but casts no light (ScreenGlow does the lighting).
    screen.visible_diffuse = False
    screen.visible_glossy = False
    screen.visible_transmission = False
    screen.visible_volume_scatter = False
    screen.visible_camera = True
    log(f"Movie screen: glowing (strength {2.5 * screen_mult:.1f}), camera-only")


def floor_z_at(x, y):
    """Find the raked-floor height at (x, y) by casting straight down, stepping
    past the ceiling and seats (whatever is hit first) until RakedFloor is hit."""
    deps = bpy.context.evaluated_depsgraph_get()
    down = mathutils.Vector((0, 0, -1))
    z = 20.0
    for _ in range(8):  # ceiling, then maybe a seat, then the floor
        hit, loc, _n, _i, obj, _m = bpy.context.scene.ray_cast(
            deps, mathutils.Vector((x, y, z)), down)
        if not hit:
            return 0.0
        if obj and obj.name == "RakedFloor":
            return loc.z
        z = loc.z - 0.001  # drop just below this hit and continue
    return 0.0


def screen_center():
    """World-space center of the Screen mesh (the look-at target)."""
    screen = bpy.data.objects.get("Screen")
    if not screen:
        return mathutils.Vector((0.0, 0.0, 3.7))
    corners = [screen.matrix_world @ mathutils.Vector(c) for c in screen.bound_box]
    return sum(corners, mathutils.Vector()) / len(corners)


def aim_target(aim, eye_z):
    """Look-at target for the chosen view. 'screen' faces the screen; 'left'/
    'right' face the side walls where the sconces are, so wall/sconce blowout
    (which fills the wide headset FOV) is actually visible — the seat-to-screen
    view under-shows it."""
    if aim == "left":
        return mathutils.Vector((-8.7, 8.0, 2.6))
    if aim == "right":
        return mathutils.Vector((8.7, 8.0, 2.6))
    return screen_center()


def place_camera(aim="screen"):
    """Create/position the seat camera aimed per `aim`."""
    cam = bpy.data.objects.get(CAMERA_NAME)
    if cam is None:
        cam_data = bpy.data.cameras.new(CAMERA_NAME)
        cam = bpy.data.objects.new(CAMERA_NAME, cam_data)
        bpy.context.scene.collection.objects.link(cam)

    eye_z = floor_z_at(0.0, SEAT_DISTANCE_M) + EYE_HEIGHT_M
    cam.location = mathutils.Vector((0.0, SEAT_DISTANCE_M, eye_z))

    target = aim_target(aim, eye_z)
    direction = target - cam.location
    cam.rotation_euler = direction.to_track_quat("-Z", "Y").to_euler()

    cam.data.sensor_fit = "HORIZONTAL"
    cam.data.lens_unit = "FOV"
    cam.data.angle = CAMERA_HFOV_DEG * 3.14159265 / 180.0

    bpy.context.scene.camera = cam
    log(f"Camera at seat: loc={tuple(round(v,2) for v in cam.location)} "
        f"aim={tuple(round(v,2) for v in target)}")
    return cam


def configure_render(samples, res):
    scene = bpy.context.scene
    scene.render.engine = "CYCLES"
    scene.cycles.samples = samples
    scene.cycles.device = "GPU"
    scene.cycles.use_denoising = True
    # Standard view transform: the faithful proxy for the device's unlit display.
    # AgX would tonemap and mislead brightness tuning (see issue #26 gotchas).
    scene.view_settings.view_transform = "Standard"
    scene.render.resolution_x, scene.render.resolution_y = res
    scene.render.resolution_percentage = 100
    scene.render.image_settings.file_format = "PNG"
    log(f"Render: Cycles {samples} samples, {res[0]}x{res[1]}, Standard transform")


def main():
    opts = parse_args()
    # Default to /tmp per repo convention — preview output is a diagnostic, not
    # a committed asset. Override with --out to keep a render.
    out = os.path.abspath(opts["out"] or "/tmp/jellyquest_preview_seat.png")

    log("=" * 60)
    log(f"Seat preview render (boost={opts['boost']}x)")
    log("=" * 60)
    start = time.time()

    # A preset SETS absolute energies; --boost/--house/--screen then MULTIPLY on
    # top for live nudging. The visible movie screen tracks the preset's screen
    # level (relative to baseline) times any --screen nudge.
    screen_factor = opts["screen"]
    if opts["preset"]:
        presets.apply_preset(opts["preset"], log=log)
        screen_factor *= presets.screen_glow_ratio(opts["preset"])

    if opts["boost"] != 1.0:
        bake.BAKE_BOOST = opts["boost"]
        bake.boost_lights()  # discarded on exit; .blend never saved

    apply_light_groups(opts["house"], opts["screen"])
    bake.configure_house_lights()
    setup_albedo()
    show_movie_screen(opts["show_screen"], screen_factor)
    place_camera(opts["aim"])
    configure_render(opts["samples"], opts["res"])

    bpy.context.scene.render.filepath = out
    bpy.ops.render.render(write_still=True)

    log("=" * 60)
    log(f"Done in {time.time() - start:.1f}s → {out}")
    log("=" * 60)


if __name__ == "__main__":
    main()
