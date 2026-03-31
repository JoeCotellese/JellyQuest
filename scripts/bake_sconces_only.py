"""
Blender script: Bake lightmaps with ONLY sconce lights + surface textures.

Run from terminal:
    /Applications/Blender.app/Contents/MacOS/Blender \
        assets/cinema_standard_multiplex.blend \
        --background --python scripts/bake_sconces_only.py

This bakes surface color × sconce lighting into each lightmap, so the
exported GLB has both material color and baked light in the base color texture.
All lights except sconces are disabled.
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
TEXTURE_DIR = os.path.join(BLEND_DIR, "..", "app", "src", "main", "assets")

# Materials → objects mapping
# Tuple: (material_name, [object_names], optional_texture_filename)
BAKE_LIST = [
    ("ArtDeco_Ceiling", ["Ceiling"], None),
    ("ArtDeco_Floor", ["RakedFloor"], "texture_floor_carpet.png"),
    ("ArtDeco_Gold", ["GoldTrim_All"], None),
    ("ArtDeco_Seat", ["Seats_All"], None),
    ("ArtDeco_SeatMetal", ["Armrests_All"], None),
    ("ArtDeco_Wall", ["Walls_All"], "texture_wall_fabric.png"),
    ("ExitDoor_Metal", ["ExitDoor_Left", "ExitDoor_Right"], None),
    ("ExitDoor_PushBar", ["ExitDoor_PushBar_Left", "ExitDoor_PushBar_Right"], None),
    ("ExitSign_Housing", ["ExitSign_Housing_Left", "ExitSign_Housing_Right"], None),
    ("ExitSign_Red", ["ExitSign_Face_Left", "ExitSign_Face_Right"], None),
    ("ProjectionBooth_Glass", ["ProjectionBooth_Glass"], None),
]


def log(msg):
    print(f"[bake-sconces] {msg}", flush=True)


def adjust_lights():
    """Enable only sconce lights — disable everything else."""
    log("Adjusting lights (sconces only)...")

    # Sconces → 800W
    sconce_count = 0
    for obj in bpy.data.objects:
        if obj.name.startswith("Sconce_Light_"):
            obj.data.energy = 800.0
            obj.hide_render = False
            obj.hide_viewport = False
            sconce_count += 1
            log(f"  {obj.name}: energy → 800")
    log(f"  Enabled {sconce_count} sconce lights")

    # CoveLight → disabled
    if "CoveLight" in bpy.data.objects:
        cove = bpy.data.objects["CoveLight"]
        cove.hide_render = True
        cove.hide_viewport = True
        log("  CoveLight: DISABLED")

    # ScreenGlow → disabled
    if "ScreenGlow" in bpy.data.objects:
        sg = bpy.data.objects["ScreenGlow"]
        sg.hide_render = True
        sg.hide_viewport = True
        log("  ScreenGlow: DISABLED")

    # Remove ProjectionBooth_Light
    if "ProjectionBooth_Light" in bpy.data.objects:
        booth = bpy.data.objects["ProjectionBooth_Light"]
        light_data = booth.data
        bpy.data.objects.remove(booth, do_unlink=True)
        bpy.data.lights.remove(light_data)
        log("  ProjectionBooth_Light: removed")

    # Do NOT add AmbientFill
    if "AmbientFill" in bpy.data.objects:
        af = bpy.data.objects["AmbientFill"]
        af.hide_render = True
        af.hide_viewport = True
        log("  AmbientFill: DISABLED")


def configure_bake_settings():
    """Set Cycles bake configuration."""
    log(f"Configuring bake: Cycles Combined, {BAKE_SAMPLES} samples")
    bpy.context.scene.render.engine = "CYCLES"
    bpy.context.scene.cycles.bake_type = "COMBINED"
    bpy.context.scene.cycles.samples = BAKE_SAMPLES
    bpy.context.scene.cycles.device = "GPU"


def add_surface_texture(mat, texture_filename):
    """Wire a surface texture into Base Color for baking.

    Before: BakeTarget → Base Color
    After:  SurfaceTexture (on UVMap) → Base Color, BakeTarget still active

    The COMBINED bake evaluates the shader (surface color × lighting)
    and writes the result into the active image node (BakeTarget).
    """
    tree = mat.node_tree
    bsdf = tree.nodes["Principled BSDF"]

    # Load texture image
    tex_path = os.path.join(TEXTURE_DIR, texture_filename)
    img = bpy.data.images.load(tex_path, check_existing=True)

    # Create texture node
    tex_node = tree.nodes.new("ShaderNodeTexImage")
    tex_node.name = "SurfaceTexture"
    tex_node.image = img
    tex_node.location = (-500, 300)

    # Create UV Map node pointing to tiling UVs
    uv_node = tree.nodes.new("ShaderNodeUVMap")
    uv_node.name = "SurfaceUV"
    uv_node.uv_map = "UVMap"
    uv_node.location = (-700, 300)

    # Wire: UVMap → Texture → Base Color
    tree.links.new(uv_node.outputs["UV"], tex_node.inputs["Vector"])
    tree.links.new(tex_node.outputs["Color"], bsdf.inputs["Base Color"])

    log(f"    Wired {texture_filename} into Base Color via UVMap")


def remove_surface_texture(mat):
    """Remove the surface texture nodes and restore BakeTarget → Base Color."""
    tree = mat.node_tree
    bsdf = tree.nodes["Principled BSDF"]
    bake_node = tree.nodes["BakeTarget"]

    # Remove added nodes
    for name in ("SurfaceTexture", "SurfaceUV"):
        if name in tree.nodes:
            tree.nodes.remove(tree.nodes[name])

    # Restore BakeTarget → Base Color
    tree.links.new(bake_node.outputs["Color"], bsdf.inputs["Base Color"])


def bake_material(mat_name, obj_names, texture_filename=None):
    """Bake a single material's lightmap."""
    mat = bpy.data.materials[mat_name]

    # If a surface texture is provided, wire it in before baking
    if texture_filename:
        add_surface_texture(mat, texture_filename)

    # Make BakeTarget the active node (this is where bake writes to)
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

    # Restore material: remove surface texture, reconnect BakeTarget
    if texture_filename:
        remove_surface_texture(mat)


def bake_all_lightmaps():
    """Bake all material lightmaps and save to disk."""
    os.makedirs(LIGHTMAP_DIR, exist_ok=True)
    total = len(BAKE_LIST)

    for i, (mat_name, obj_names, tex_file) in enumerate(BAKE_LIST, 1):
        label = f"{mat_name}" + (f" + {tex_file}" if tex_file else "")
        log(f"  [{i}/{total}] Baking {label}...")
        start = time.time()
        try:
            bake_material(mat_name, obj_names, tex_file)
            elapsed = time.time() - start
            log(f"  [{i}/{total}] {label} done ({elapsed:.1f}s)")
        except Exception as e:
            log(f"  [{i}/{total}] {label} FAILED: {e}")
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

    # Also hide lights — they don't belong in the GLB
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


def main():
    log("=" * 60)
    log("Lightmap Bake: SCONCES ONLY (diagnostic)")
    log("=" * 60)
    start_total = time.time()

    adjust_lights()
    configure_bake_settings()

    log("Starting bake...")
    bake_all_lightmaps()

    export_glb()
    # Do NOT save .blend — this is a diagnostic run

    elapsed_total = time.time() - start_total
    log("=" * 60)
    log(f"All done! Total time: {elapsed_total:.1f}s")
    log("=" * 60)


if __name__ == "__main__":
    main()
