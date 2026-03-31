"""
Blender script: Bake color × lighting into lightmaps and export unlit GLB.

Run from terminal:
    /Applications/Blender.app/Contents/MacOS/Blender \
        assets/cinema_standard_multiplex.blend \
        --background --python scripts/bake_lightmaps.py

    With boost (e.g. 1.5x brighter than Blender):
    /Applications/Blender.app/Contents/MacOS/Blender \
        assets/cinema_standard_multiplex.blend \
        --background --python scripts/bake_lightmaps.py -- --boost 1.5

Workflow:
1. Reads light energies from the .blend file (your visual reference)
2. Applies boost multiplier to all lights (default 1.0 = same as Blender)
3. Wires surface textures into materials that have them
4. For materials without textures, uses the BSDF default color
5. Bakes COMBINED (color × lighting) into each lightmap
6. Restores materials: BakeTarget → Base Color, marked as unlit
7. Exports GLB with KHR_materials_unlit
8. Restores light energies — does NOT save the .blend file
"""

import bpy
import os
import shutil
import sys
import time

# --- Parse arguments after "--" separator ---
argv = sys.argv
if "--" in argv:
    script_args = argv[argv.index("--") + 1:]
else:
    script_args = []

BAKE_BOOST = 1.0
for i, arg in enumerate(script_args):
    if arg == "--boost" and i + 1 < len(script_args):
        BAKE_BOOST = float(script_args[i + 1])

# --- Paths ---
BLEND_DIR = os.path.dirname(bpy.data.filepath)
LIGHTMAP_DIR = os.path.join(BLEND_DIR, "lightmaps")
GLB_OUTPUT = os.path.join(BLEND_DIR, "cinema_multiplex.glb")
APP_ASSETS = os.path.join(BLEND_DIR, "..", "app", "src", "main", "assets", "cinema_multiplex.glb")
TEXTURE_DIR = BLEND_DIR  # textures live alongside the .blend file

BAKE_SAMPLES = 128

# Materials → objects → optional texture file
# Textures are tiled via a Mapping node during bake, then removed
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

# Tiling scale for surface textures (set in Blender, matched here)
TEXTURE_TILE_SCALE = 16.0


def log(msg):
    print(f"[bake] {msg}", flush=True)


# ── Step 1: Read and boost light energies ──────────────────────────

def boost_lights():
    """Read current light energies, apply boost, return originals for restore."""
    original_energies = {}
    log(f"Boosting lights by {BAKE_BOOST}x...")

    for obj in bpy.data.objects:
        if obj.type == "LIGHT":
            original_energies[obj.name] = obj.data.energy
            boosted = obj.data.energy * BAKE_BOOST
            obj.data.energy = boosted
            log(f"  {obj.name}: {original_energies[obj.name]:.0f} → {boosted:.0f}")

    return original_energies


def restore_lights(original_energies):
    """Restore light energies to pre-boost values."""
    for name, energy in original_energies.items():
        obj = bpy.data.objects.get(name)
        if obj and obj.type == "LIGHT":
            obj.data.energy = energy


# ── Step 2: Disable non-house lights ───────────────────────────────

def configure_house_lights():
    """Disable screen/projection lights — keep house lights as-is."""
    log("Configuring house lights...")

    if "ScreenGlow" in bpy.data.objects:
        sg = bpy.data.objects["ScreenGlow"]
        sg.hide_render = True
        sg.hide_viewport = True
        log("  ScreenGlow: hidden")

    if "ProjectionBooth_Light" in bpy.data.objects:
        booth = bpy.data.objects["ProjectionBooth_Light"]
        light_data = booth.data
        bpy.data.objects.remove(booth, do_unlink=True)
        bpy.data.lights.remove(light_data)
        log("  ProjectionBooth_Light: removed")

    # Log active lights
    for obj in bpy.data.objects:
        if obj.type == "LIGHT" and not obj.hide_render:
            log(f"  Active: {obj.name} ({obj.data.energy:.0f}W)")


# ── Step 3: Wire surface textures ──────────────────────────────────

def add_surface_texture(mat, texture_filename):
    """Wire a tiled surface texture into Base Color for baking."""
    tree = mat.node_tree
    bsdf = tree.nodes["Principled BSDF"]

    tex_path = os.path.join(TEXTURE_DIR, texture_filename)
    img = bpy.data.images.load(tex_path, check_existing=True)

    # UV Map node → Mapping (tile) → Image Texture → Base Color
    uv_node = tree.nodes.new("ShaderNodeUVMap")
    uv_node.name = "BakeSurfaceUV"
    uv_node.uv_map = "UVMap"
    uv_node.location = (-700, 300)

    mapping = tree.nodes.new("ShaderNodeMapping")
    mapping.name = "BakeTileMapping"
    mapping.location = (-550, 300)
    mapping.inputs["Scale"].default_value = (TEXTURE_TILE_SCALE, TEXTURE_TILE_SCALE, 1.0)

    tex_node = tree.nodes.new("ShaderNodeTexImage")
    tex_node.name = "BakeSurfaceTex"
    tex_node.image = img
    tex_node.location = (-350, 300)

    tree.links.new(uv_node.outputs["UV"], mapping.inputs["Vector"])
    tree.links.new(mapping.outputs["Vector"], tex_node.inputs["Vector"])
    tree.links.new(tex_node.outputs["Color"], bsdf.inputs["Base Color"])

    log(f"    Wired {texture_filename} (tile={TEXTURE_TILE_SCALE}x)")


def ensure_default_color_on_base(mat):
    """For materials without a texture, disconnect BakeTarget so the
    BSDF default Base Color is used during baking."""
    tree = mat.node_tree
    bsdf = tree.nodes["Principled BSDF"]
    base_input = bsdf.inputs["Base Color"]

    if base_input.links:
        from_node = base_input.links[0].from_node
        if from_node.name == "BakeTarget":
            tree.links.remove(base_input.links[0])
            col = list(base_input.default_value)[:3]
            log(f"    Using default color ({col[0]:.2f}, {col[1]:.2f}, {col[2]:.2f})")


def remove_bake_texture_nodes(mat):
    """Remove temporary texture nodes added for baking."""
    tree = mat.node_tree
    for name in ("BakeSurfaceTex", "BakeTileMapping", "BakeSurfaceUV"):
        if name in tree.nodes:
            tree.nodes.remove(tree.nodes[name])


def restore_material_for_export(mat):
    """Swap Principled BSDF for Emission shader so glTF exports as unlit.

    Blender's glTF exporter emits KHR_materials_unlit when it sees an
    Emission shader connected directly to Material Output.
    """
    tree = mat.node_tree
    output = tree.nodes["Material Output"]
    bake_node = tree.nodes["BakeTarget"]

    # Remove any bake-time texture nodes
    remove_bake_texture_nodes(mat)

    # Remove Principled BSDF link to output
    for link in list(tree.links):
        if link.to_node == output and link.to_socket.name == "Surface":
            tree.links.remove(link)

    # Create Emission shader: BakeTarget → Emission → Material Output
    emission = tree.nodes.new("ShaderNodeEmission")
    emission.name = "UnlitExport"
    emission.location = (0, 300)
    emission.inputs["Strength"].default_value = 1.0

    tree.links.new(bake_node.outputs["Color"], emission.inputs["Color"])
    tree.links.new(emission.outputs["Emission"], output.inputs["Surface"])


# ── Step 4: Bake ──────────────────────────────────────────────────

def configure_bake_settings():
    """Set Cycles bake configuration."""
    log(f"Configuring bake: Cycles Combined, {BAKE_SAMPLES} samples")
    bpy.context.scene.render.engine = "CYCLES"
    bpy.context.scene.cycles.bake_type = "COMBINED"
    bpy.context.scene.cycles.samples = BAKE_SAMPLES
    bpy.context.scene.cycles.device = "GPU"


def bake_material(mat_name, obj_names, texture_filename=None):
    """Bake a single material's lightmap (color × lighting)."""
    mat = bpy.data.materials[mat_name]

    # Set up material for baking
    if texture_filename:
        add_surface_texture(mat, texture_filename)
    else:
        ensure_default_color_on_base(mat)

    # Make BakeTarget the active image node (bake writes here)
    for node in mat.node_tree.nodes:
        node.select = False
    bake_node = mat.node_tree.nodes["BakeTarget"]
    bake_node.select = True
    mat.node_tree.nodes.active = bake_node

    # Select objects
    bpy.ops.object.select_all(action="DESELECT")
    for obj_name in obj_names:
        bpy.data.objects[obj_name].select_set(True)
    bpy.context.view_layer.objects.active = bpy.data.objects[obj_names[0]]

    # Bake
    bpy.ops.object.bake(type="COMBINED")

    # Restore material for export
    restore_material_for_export(mat)


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


# ── Step 5: Export GLB with unlit materials ────────────────────────

def export_glb():
    """Export GLB with KHR_materials_unlit, without the Screen mesh."""
    log("Exporting GLB (unlit)...")

    # Hide Screen mesh
    screen = bpy.data.objects.get("Screen")
    screen_was_hidden = None
    if screen:
        screen_was_hidden = screen.hide_get()
        screen.hide_set(True)
        screen.select_set(False)

    # Hide lights
    hidden_objects = []
    for obj in bpy.data.objects:
        if obj.type == "LIGHT":
            if not obj.hide_get():
                obj.hide_set(True)
                hidden_objects.append(obj)

    # Select all visible mesh objects
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


# ── Main ───────────────────────────────────────────────────────────

def main():
    log("=" * 60)
    log(f"Lightmap Bake: Color × Light (boost={BAKE_BOOST}x)")
    log("=" * 60)
    start_total = time.time()

    original_energies = boost_lights()
    configure_house_lights()
    configure_bake_settings()

    log("Starting bake...")
    bake_all_lightmaps()

    restore_lights(original_energies)
    export_glb()
    # Do NOT save .blend — it stays as the visual reference

    elapsed_total = time.time() - start_total
    log("=" * 60)
    log(f"All done! Total time: {elapsed_total:.1f}s")
    log("=" * 60)


if __name__ == "__main__":
    main()
