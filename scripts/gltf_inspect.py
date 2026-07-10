# ABOUTME: Inspects a GLB's material texture slots and mesh UV sets.
# ABOUTME: Validates the unlit export — lightmap in baseColorTexture on TEXCOORD_0.
"""
Parse a GLB and report, per material, which texture slots are populated, and per
mesh primitive, which TEXCOORD sets exist. Exits non-zero if any material used by
a mesh lacks a baseColorTexture (the slot the app's UNLIT_SHADER samples), so it
doubles as a pass/fail gate.

Run: python3 scripts/gltf_inspect.py app/src/main/assets/cinema_multiplex.glb
"""

import json
import struct
import sys


def load_glb_json(path):
    with open(path, "rb") as f:
        magic, _ver, _length = struct.unpack("<III", f.read(12))
        if magic != 0x46546C67:
            raise ValueError(f"{path} is not a GLB (bad magic)")
        clen, _ctype = struct.unpack("<II", f.read(8))
        return json.loads(f.read(clen))


def main():
    path = sys.argv[1] if len(sys.argv) > 1 else "app/src/main/assets/cinema_multiplex.glb"
    g = load_glb_json(path)
    mats = g.get("materials", [])
    meshes = g.get("meshes", [])

    print(f"GLB: {path}")
    print(f"  materials={len(mats)} meshes={len(meshes)} "
          f"textures={len(g.get('textures', []))} images={len(g.get('images', []))}\n")

    failures = []
    print("MATERIALS (texture slots):")
    for i, m in enumerate(mats):
        name = m.get("name", f"mat{i}")
        pbr = m.get("pbrMetallicRoughness", {})
        base = pbr.get("baseColorTexture")
        emis = m.get("emissiveTexture")
        slots = []
        if base is not None:
            slots.append(f"baseColor(texCoord={base.get('texCoord', 0)})")
        if emis is not None:
            slots.append(f"emissive(texCoord={emis.get('texCoord', 0)})")
        factor = pbr.get("baseColorFactor")
        tag = "" if base else "  <-- NO baseColorTexture"
        if not base:
            failures.append(name)
        print(f"  [{i}] {name}: {', '.join(slots) or '(none)'}"
              f"{' factor=' + str(factor) if factor else ''}{tag}")

    print("\nMESH PRIMITIVES (UV sets):")
    for i, mesh in enumerate(meshes):
        for j, prim in enumerate(mesh.get("primitives", [])):
            attrs = prim.get("attributes", {})
            uvs = sorted(k for k in attrs if k.startswith("TEXCOORD_"))
            mat_i = prim.get("material")
            mat_name = mats[mat_i].get("name") if mat_i is not None else "(none)"
            print(f"  {mesh.get('name', 'mesh'+str(i))}[{j}] mat={mat_name} uvs={uvs}")

    print()
    if failures:
        print(f"FAIL: {len(failures)} material(s) missing baseColorTexture: {failures}")
        sys.exit(1)
    print("PASS: every material has a baseColorTexture")


if __name__ == "__main__":
    main()
