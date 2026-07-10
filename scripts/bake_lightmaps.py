"""
Blender script: Bake color × lighting into lightmaps and export unlit GLB.

Run from terminal:
    /Applications/Blender.app/Contents/MacOS/Blender \
        assets/cinema_standard_multiplex.blend \
        --background --python scripts/bake_lightmaps.py

    With a versioned lighting preset (see lighting_presets.json):
    /Applications/Blender.app/Contents/MacOS/Blender \
        assets/cinema_standard_multiplex.blend \
        --background --python scripts/bake_lightmaps.py -- --preset movie_mode

    Fast validation without the ~24-min bake — re-export the existing
    assets/lightmaps/*.png through the fixed export path:
    /Applications/Blender.app/Contents/MacOS/Blender \
        assets/cinema_standard_multiplex.blend \
        --background --python scripts/bake_lightmaps.py -- --no-bake

Args (after "--"):
    --preset NAME   Apply a named lighting preset before baking
    --boost F       Multiply all light energies by F (composes with --preset)
    --no-bake       Skip baking; load saved lightmaps and run only the export

Workflow:
1. Reads light energies from the .blend (or applies --preset), then --boost
2. Wires surface textures into materials that have them (else BSDF default color)
3. Bakes COMBINED (color × lighting) into each lightmap  [skipped with --no-bake]
4. Restores materials: BakeTarget → Base Color (exports as baseColorTexture)
5. Collapses each mesh to the lightmap UV so it becomes TEXCOORD_0
6. Exports GLB (the app forces UNLIT_SHADER, so no KHR_materials_unlit needed)
7. Restores light energies — does NOT save the .blend file
"""

import bpy
import os
import shutil
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import lighting_presets as presets  # noqa: E402

# --- Parse arguments after "--" separator ---
argv = sys.argv
if "--" in argv:
    script_args = argv[argv.index("--") + 1:]
else:
    script_args = []

BAKE_BOOST = 1.0
PRESET = None
NO_BAKE = False
FAST = False
for i, arg in enumerate(script_args):
    if arg == "--boost" and i + 1 < len(script_args):
        BAKE_BOOST = float(script_args[i + 1])
    elif arg == "--preset" and i + 1 < len(script_args):
        PRESET = script_args[i + 1]
    elif arg == "--no-bake":
        NO_BAKE = True
    elif arg == "--fast":
        FAST = True

# --- Paths ---
BLEND_DIR = os.path.dirname(bpy.data.filepath)
LIGHTMAP_DIR = os.path.join(BLEND_DIR, "lightmaps")
GLB_OUTPUT = os.path.join(BLEND_DIR, "cinema_multiplex.glb")
APP_ASSETS = os.path.join(BLEND_DIR, "..", "app", "src", "main", "assets", "cinema_multiplex.glb")
TEXTURE_DIR = BLEND_DIR  # textures live alongside the .blend file

# --fast trades quality for speed: noisier lightmaps for a quick on-device
# look-check. Use the default for the final bake.
BAKE_SAMPLES = 16 if FAST else 128

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

# UV layer holding the lightmap atlas unwrap. The bake writes the atlas here;
# at export it must become TEXCOORD_0 (see collapse_to_lightmap_uv).
LIGHTMAP_UV = "Lightmap"


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
    """Enable all lights — sconces plus house lights (AmbientFill, CoveLight,
    ScreenGlow, exit-sign glows) — so the room reads lit in the bake."""
    log("Configuring lights (all house + sconce lights enabled)...")

    for obj in bpy.data.objects:
        if obj.type != "LIGHT":
            continue
        obj.hide_render = False
        obj.hide_viewport = False
        log(f"  ENABLED: {obj.name} ({obj.data.energy:.0f}W)")


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
    """Wire the baked lightmap into Base Color so it exports as baseColorTexture.

    The app renders the GLB with UNLIT_SHADER (defaultShaderOverride), which
    samples baseColorTexture on UV0 and ignores lighting, baseColorFactor, and
    the per-texture texCoord field. So the baked result (BakeTarget) MUST land in
    the Principled BSDF Base Color. The old path wired BakeTarget → Emission,
    which exports as emissiveTexture with a black base color — the unlit shader
    then renders black. KHR_materials_unlit is unnecessary; the shader override
    forces the unlit path regardless, so a plain matte BSDF is correct here.
    """
    tree = mat.node_tree
    output = tree.nodes["Material Output"]
    bake_node = tree.nodes["BakeTarget"]
    bsdf = tree.nodes["Principled BSDF"]

    # Remove any bake-time surface texture nodes (tiled wall/floor helpers)
    remove_bake_texture_nodes(mat)

    # BakeTarget → Base Color, replacing whatever was wired for baking
    base_in = bsdf.inputs["Base Color"]
    for link in list(base_in.links):
        tree.links.remove(link)
    tree.links.new(bake_node.outputs["Color"], base_in)

    # Matte, non-metallic, no emission (unlit shader ignores these; keep sane)
    bsdf.inputs["Metallic"].default_value = 0.0
    bsdf.inputs["Roughness"].default_value = 1.0
    if "Emission Strength" in bsdf.inputs:
        bsdf.inputs["Emission Strength"].default_value = 0.0
    if "Emission Color" in bsdf.inputs:
        bsdf.inputs["Emission Color"].default_value = (0.0, 0.0, 0.0, 1.0)

    # Ensure the BSDF drives the surface output
    surf = output.inputs["Surface"]
    if not surf.links or surf.links[0].from_node is not bsdf:
        for link in list(surf.links):
            tree.links.remove(link)
        tree.links.new(bsdf.outputs["BSDF"], surf)


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

    # Persist this lightmap NOW, before any later material bakes. A later bake can
    # corrupt an earlier in-memory lightmap image (observed: baking ExitDoor_Metal
    # overwrites Lightmap_ArtDeco_Wall's pixels even though they are separate
    # datablocks). Saving immediately keeps each PNG correct; export reloads from
    # these PNGs (load_existing_lightmaps), so the corruption never reaches the GLB.
    os.makedirs(LIGHTMAP_DIR, exist_ok=True)
    img = bake_node.image
    img.filepath_raw = os.path.join(LIGHTMAP_DIR, f"{img.name}.png")
    img.file_format = "PNG"
    img.save()

    # Do NOT restore here — restoring swaps this surface's albedo to its baked
    # (bright) lightmap, inflating inter-reflection for later bakes. Restore all
    # at the end (via load_existing_lightmaps, which also reloads clean PNGs).


def restore_all_materials_for_export():
    """Wire every baked material's lightmap into Base Color for export. Run once
    after ALL bakes complete, so inter-reflection stays physical during baking."""
    for mat_name, _obj_names, _tex_file in BAKE_LIST:
        restore_material_for_export(bpy.data.materials[mat_name])


def load_existing_lightmaps():
    """--no-bake: load the saved lightmap PNGs into the BakeTarget images and
    restore materials for export, skipping the ~24-min bake. Bake and export are
    independent stages, so this validates the export path in ~1 min against the
    lightmaps already on disk (assets/lightmaps/*.png)."""
    for mat_name, _obj_names, _tex_file in BAKE_LIST:
        mat = bpy.data.materials[mat_name]
        bake_node = mat.node_tree.nodes["BakeTarget"]
        img = bake_node.image
        png = os.path.join(LIGHTMAP_DIR, f"{img.name}.png")
        if not os.path.exists(png):
            log(f"  MISSING {png} — skipping {mat_name}")
            continue
        img.filepath = png
        img.source = "FILE"
        img.reload()
        log(f"  Loaded {img.name}.png")
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
    # Note: each lightmap is saved inside bake_material() right after its bake,
    # NOT here — an end-of-loop save would persist images already corrupted by
    # later bakes. See the note in bake_material().


# ── Step 5: Export GLB with unlit materials ────────────────────────

def collapse_to_lightmap_uv():
    """Make the lightmap unwrap the sole UV set (TEXCOORD_0) on every baked mesh.

    Meta's UNLIT_SHADER always samples the material texture on UV0 and ignores
    the glTF texCoord field. The atlas is unwrapped into the 'Lightmap' UV layer,
    but in the .blend that sits at index 1 while the tiling 'UVMap' is index 0
    and flagged active_render. Left alone, the exporter binds the lightmap to
    TEXCOORD_0 = tiling UV, so the runtime samples the atlas with tiling coords
    and renders the black atlas background. The surface textures are already
    baked into the atlas, so the tiling UV is dead weight at runtime — drop it,
    leaving the lightmap unwrap as the only UV set (TEXCOORD_0).
    """
    log("Collapsing baked meshes to lightmap UV (TEXCOORD_0)...")
    processed = set()
    for obj in bpy.data.objects:
        if obj.type != "MESH" or obj.data.name in processed:
            continue
        uvs = obj.data.uv_layers
        if LIGHTMAP_UV not in uvs:
            continue
        processed.add(obj.data.name)
        for uv in list(uvs):
            if uv.name != LIGHTMAP_UV:
                uvs.remove(uv)
        uvs[LIGHTMAP_UV].active = True
        uvs[LIGHTMAP_UV].active_render = True
        log(f"  {obj.data.name}: single UV '{LIGHTMAP_UV}' → TEXCOORD_0")


def export_glb():
    """Export GLB with KHR_materials_unlit, without the Screen mesh."""
    log("Exporting GLB (unlit)...")

    collapse_to_lightmap_uv()

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
    mode = "export-only (--no-bake)" if NO_BAKE else "bake + export"
    log(f"Lightmap {mode}: preset={PRESET or '(blend values)'} boost={BAKE_BOOST}x")
    log("=" * 60)
    start_total = time.time()

    # Snapshot true light energies so we can restore (the .blend is never saved).
    original_energies = {o.name: o.data.energy
                         for o in bpy.data.objects if o.type == "LIGHT"}

    # A preset SETS absolute energies; --boost then MULTIPLIES on top.
    if PRESET:
        presets.apply_preset(PRESET, log=log)
    if BAKE_BOOST != 1.0:
        boost_lights()
    configure_house_lights()

    if NO_BAKE:
        log("Skipping bake — loading existing lightmaps for export...")
        load_existing_lightmaps()
    else:
        configure_bake_settings()
        log("Starting bake...")
        bake_all_lightmaps()
        # Reload the clean per-material PNGs into the BakeTarget images, undoing
        # any in-memory corruption from cross-material bake writes, then restore
        # materials for export. Export uses these reloaded images.
        log("Reloading clean lightmaps for export...")
        load_existing_lightmaps()

    restore_lights(original_energies)
    export_glb()
    # Do NOT save .blend — it stays as the visual reference

    elapsed_total = time.time() - start_total
    log("=" * 60)
    log(f"All done! Total time: {elapsed_total:.1f}s")
    log("=" * 60)


if __name__ == "__main__":
    main()
