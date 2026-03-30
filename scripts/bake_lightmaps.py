"""
Blender script: Bake house-lights-on lightmaps for cinema multiplex.

Run from terminal:
    /Applications/Blender.app/Contents/MacOS/Blender \
        assets/cinema_standard_multiplex.blend \
        --background --python scripts/bake_lightmaps.py

This script:
1. Adjusts light intensities for house-lights-on look
2. Disables ScreenGlow, removes ProjectionBooth_Light
3. Adds a subtle ambient fill light
4. Bakes all 11 material lightmaps at 128 samples (Cycles Combined)
5. Saves lightmap PNGs to assets/lightmaps/
6. Exports GLB (without Screen mesh) to assets/cinema_multiplex.glb
7. Copies GLB to app/src/main/assets/cinema_multiplex.glb
"""

import bpy
import os
import shutil
import sys
import time

BLEND_DIR = os.path.dirname(bpy.data.filepath)
LIGHTMAP_DIR = os.path.join(BLEND_DIR, "lightmaps")
GLB_OUTPUT = os.path.join(BLEND_DIR, "cinema_multiplex.glb")
APP_ASSETS = os.path.join(BLEND_DIR, "..", "app", "src", "main", "assets", "cinema_multiplex.glb")

BAKE_SAMPLES = 128

# Materials → objects mapping
BAKE_LIST = [
    ("ArtDeco_Ceiling", ["Ceiling"]),
    ("ArtDeco_Floor", ["Floor", "RakedFloor"]),
    ("ArtDeco_Gold", ["GoldTrim_All"]),
    ("ArtDeco_Seat", ["Seats_All"]),
    ("ArtDeco_SeatMetal", ["Armrests_All"]),
    ("ArtDeco_Wall", ["Walls_All"]),
    ("ExitDoor_Metal", ["ExitDoor_Left", "ExitDoor_Right"]),
    ("ExitDoor_PushBar", ["ExitDoor_PushBar_Left", "ExitDoor_PushBar_Right"]),
    ("ExitSign_Housing", ["ExitSign_Housing_Left", "ExitSign_Housing_Right"]),
    ("ExitSign_Red", ["ExitSign_Face_Left", "ExitSign_Face_Right"]),
    ("ProjectionBooth_Glass", ["ProjectionBooth_Glass"]),
]


def log(msg):
    print(f"[bake] {msg}", flush=True)


def adjust_lights():
    """Set light intensities for house-lights-on bake."""
    log("Adjusting lights...")

    # Sconces → 800 (high energy needed to produce visible lightmap values in 8-bit PNG)
    for obj in bpy.data.objects:
        if obj.name.startswith("Sconce_Light_"):
            obj.data.energy = 800.0
            log(f"  {obj.name}: energy → 800")

    # CoveLight → 1500
    cove = bpy.data.objects["CoveLight"]
    cove.data.energy = 1500.0
    log("  CoveLight: energy → 1500")

    # ScreenGlow → hidden (no screen light during house-lights bake)
    sg = bpy.data.objects["ScreenGlow"]
    sg.hide_render = True
    sg.hide_viewport = True
    log("  ScreenGlow: hidden from render")

    # Remove ProjectionBooth_Light (will be replaced by video panel effect)
    if "ProjectionBooth_Light" in bpy.data.objects:
        booth = bpy.data.objects["ProjectionBooth_Light"]
        light_data = booth.data
        bpy.data.objects.remove(booth, do_unlink=True)
        bpy.data.lights.remove(light_data)
        log("  ProjectionBooth_Light: removed")

    # Add ambient fill light from ceiling
    if "AmbientFill" not in bpy.data.objects:
        fill = bpy.data.lights.new(name="AmbientFill", type="AREA")
        fill.energy = 150.0
        fill.color = (1.0, 0.95, 0.85)
        fill.size = 16.0
        fill.size_y = 30.0

        fill_obj = bpy.data.objects.new("AmbientFill", fill)
        bpy.context.scene.collection.objects.link(fill_obj)
        fill_obj.location = (0.0, 15.0, 9.0)
        fill_obj.rotation_euler = (3.14159, 0, 0)  # pointing down
        log("  AmbientFill: added at ceiling, energy=30")
    else:
        log("  AmbientFill: already exists, skipping")


def configure_bake_settings():
    """Set Cycles bake configuration."""
    log(f"Configuring bake: Cycles Combined, {BAKE_SAMPLES} samples")
    bpy.context.scene.render.engine = "CYCLES"
    bpy.context.scene.cycles.bake_type = "COMBINED"
    bpy.context.scene.cycles.samples = BAKE_SAMPLES
    bpy.context.scene.cycles.device = "GPU"


def bake_material(mat_name, obj_names):
    """Bake a single material's lightmap."""
    mat = bpy.data.materials[mat_name]

    # Make BakeTarget the active node
    for node in mat.node_tree.nodes:
        node.select = False
    bake_node = mat.node_tree.nodes["BakeTarget"]
    bake_node.select = True
    mat.node_tree.nodes.active = bake_node

    # Select objects that use this material
    bpy.ops.object.select_all(action="DESELECT")
    for obj_name in obj_names:
        bpy.data.objects[obj_name].select_set(True)
    bpy.context.view_layer.objects.active = bpy.data.objects[obj_names[0]]

    # Bake
    bpy.ops.object.bake(type="COMBINED")


def bake_all_lightmaps():
    """Bake all material lightmaps and save to disk."""
    os.makedirs(LIGHTMAP_DIR, exist_ok=True)
    total = len(BAKE_LIST)

    for i, (mat_name, obj_names) in enumerate(BAKE_LIST, 1):
        log(f"  [{i}/{total}] Baking {mat_name}...")
        start = time.time()
        try:
            bake_material(mat_name, obj_names)
            elapsed = time.time() - start
            log(f"  [{i}/{total}] {mat_name} done ({elapsed:.1f}s)")
        except Exception as e:
            log(f"  [{i}/{total}] {mat_name} FAILED: {e}")
            sys.exit(1)

    # Save all lightmap images
    log("Saving lightmap PNGs...")
    for img in bpy.data.images:
        if img.name.startswith("Lightmap_"):
            filepath = os.path.join(LIGHTMAP_DIR, f"{img.name}.png")
            img.filepath_raw = filepath
            img.file_format = "PNG"
            img.save()
            log(f"  Saved {img.name}.png")



def export_glb():
    """Export GLB without the Screen mesh."""
    log("Exporting GLB...")

    # Hide Screen mesh from export
    screen = bpy.data.objects.get("Screen")
    screen_was_hidden = None
    if screen:
        screen_was_hidden = screen.hide_get()
        screen.hide_set(True)
        screen.select_set(False)

    # Also hide lights and the AmbientFill — they don't belong in the GLB
    hidden_objects = []
    for obj in bpy.data.objects:
        if obj.type == "LIGHT":
            if not obj.hide_get():
                obj.hide_set(True)
                hidden_objects.append(obj)

    # Select all visible mesh objects for export
    bpy.ops.object.select_all(action="DESELECT")
    for obj in bpy.data.objects:
        if obj.type == "MESH" and not obj.hide_get():
            obj.select_set(True)

    bpy.ops.export_scene.gltf(
        filepath=GLB_OUTPUT,
        use_selection=True,
        export_format="GLB",
        export_apply=True,
        export_image_format="AUTO",
        export_materials="EXPORT",
        export_lights=False,
    )
    log(f"  Exported to {GLB_OUTPUT}")

    # Restore hidden state
    if screen and screen_was_hidden is not None:
        screen.hide_set(screen_was_hidden)
    for obj in hidden_objects:
        obj.hide_set(False)

    # Copy to app assets
    os.makedirs(os.path.dirname(APP_ASSETS), exist_ok=True)
    shutil.copy2(GLB_OUTPUT, APP_ASSETS)
    log(f"  Copied to {APP_ASSETS}")


def save_blend():
    """Save the .blend file with updated light settings."""
    bpy.ops.wm.save_mainfile()
    log("Saved .blend file")


def main():
    log("=" * 60)
    log("Lightmap Bake: House-Lights-On")
    log("=" * 60)
    start_total = time.time()

    adjust_lights()
    configure_bake_settings()

    log("Starting bake...")
    bake_all_lightmaps()

    export_glb()
    save_blend()

    elapsed_total = time.time() - start_total
    log("=" * 60)
    log(f"All done! Total time: {elapsed_total:.1f}s")
    log("=" * 60)


if __name__ == "__main__":
    main()
