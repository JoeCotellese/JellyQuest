# Learnings — VirtualMonitorPOC

Durable project knowledge. One line per entry. Curated by humans and AI via the `/learnings` skill.

Legend: ⚖️ decision · 🪤 gotcha · 🧩 pattern · 🔧 tooling
Format: `YYYY-MM-DD SYMBOL one-line learning (≤100 chars)`

## Active

2026-07-10 🪤 Env GLB uses UNLIT_SHADER: shows baseColorTexture on UV0 only; ignores baseColorFactor & texCoord
2026-07-10 🪤 Lightmap must export as baseColorTexture/UV0 (BakeTarget→Base Color); Emission slot renders black
2026-07-10 🪤 Bake loop corrupts earlier lightmaps in memory; save each PNG post-bake & reload before export
2026-07-10 🧩 Bake all materials before restoring any; per-material restore mid-loop inflates inter-reflection
2026-07-10 🪤 Judge bake brightness from lightmap PNG / Standard preview, never AgX (unlit shows raw values)
2026-07-10 🔧 Tune lighting via preview_render.py (~10s) & bake --no-bake (0.5s), not full 24min bakes
2026-07-10 ⚖️ Lighting is versioned presets (scripts/lighting_presets.json), applied by both preview and bake
2026-07-10 🪤 Quest screencap returns 0 bytes when headset asleep/unworn or app backgrounded; wake+foreground first
2026-07-10 🔧 Deploy with `gradlew :app:installDebug --rerun-tasks` so Gradle ships fresh assets (else cached)

## Archived

