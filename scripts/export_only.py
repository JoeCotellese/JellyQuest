"""Quick re-export: rewire materials to emission and export GLB without re-baking."""
import bpy, os, shutil

BLEND_DIR = os.path.dirname(bpy.data.filepath)
GLB_OUTPUT = os.path.join(BLEND_DIR, "cinema_multiplex.glb")
APP_ASSETS = os.path.join(BLEND_DIR, "..", "app", "src", "main", "assets", "cinema_multiplex.glb")

BAKE_LIST = [
    "ArtDeco_Ceiling", "ArtDeco_Floor", "ArtDeco_Gold", "ArtDeco_Seat",
    "ArtDeco_SeatMetal", "ArtDeco_Wall", "ExitDoor_Metal", "ExitDoor_PushBar",
    "ExitSign_Housing", "ExitSign_Red", "ProjectionBooth_Glass",
]

# Rewire to emission
for mat_name in BAKE_LIST:
    mat = bpy.data.materials[mat_name]
    tree = mat.node_tree
    bsdf = tree.nodes["Principled BSDF"]
    bake_node = tree.nodes["BakeTarget"]
    for link in list(tree.links):
        if link.from_node == bake_node and link.to_node == bsdf:
            tree.links.remove(link)
    tree.links.new(bake_node.outputs["Color"], bsdf.inputs["Emission Color"])
    bsdf.inputs["Emission Strength"].default_value = 50.0
    bsdf.inputs["Base Color"].default_value = (0.0, 0.0, 0.0, 1.0)
print("[export] Materials rewired: emission strength=50")

# Hide Screen + lights
screen = bpy.data.objects.get("Screen")
if screen: screen.hide_set(True)
hidden = []
for obj in bpy.data.objects:
    if obj.type == "LIGHT" and not obj.hide_get():
        obj.hide_set(True); hidden.append(obj)

bpy.ops.object.select_all(action="DESELECT")
for obj in bpy.data.objects:
    if obj.type == "MESH" and not obj.hide_get():
        obj.select_set(True)

bpy.ops.export_scene.gltf(
    filepath=GLB_OUTPUT, use_selection=True, export_format="GLB",
    export_apply=True, export_image_format="AUTO",
    export_materials="EXPORT", export_lights=False,
)
shutil.copy2(GLB_OUTPUT, APP_ASSETS)
print(f"[export] GLB exported and copied")

# Restore
if screen: screen.hide_set(False)
for obj in hidden: obj.hide_set(False)
for mat_name in BAKE_LIST:
    mat = bpy.data.materials[mat_name]
    tree = mat.node_tree
    bsdf = tree.nodes["Principled BSDF"]
    bake_node = tree.nodes["BakeTarget"]
    for link in list(tree.links):
        if link.from_node == bake_node and link.to_node == bsdf:
            tree.links.remove(link)
    tree.links.new(bake_node.outputs["Color"], bsdf.inputs["Base Color"])
    bsdf.inputs["Emission Strength"].default_value = 0.0
print("[export] Materials restored")
