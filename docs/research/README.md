# 26.2 research artifacts

Reference material gathered while porting to Minecraft 26.2. The actionable summary and the plan
live in [`../26.2-vulkan-port.md`](../26.2-vulkan-port.md); these files are the evidence behind it,
kept separate so that document stays readable.

| File | What it is |
|---|---|
| `minecraft-26.2-port-research.md` | 26.2 as a release: version numbering, Java 25, unobfuscated jars and the end of Yarn, the Graphics API option and Vulkan's status, the Blaze3D API surface, what was removed, ecosystem versions (Loom/loader/API/NeoForge/Forge), and how Sodium, Iris, Distant Horizons, Embeddium and Indium each stand. Every fact carries its source. |
| `26.2-gl-capability-audit.md` | Capability-by-capability audit of what a rendering-heavy mod can still reach in 26.2 versus 1.21.11, with the command and observed output behind each entry. This is where the "no stencil state", "no compute", "no storage buffers", "no synchronous read-back" conclusions come from. |
| `26.2-shaders-and-post-processing.md` | Reverse-engineering of 26.2's shader and post-processing path: the GLSL assets, the runtime GLSL→SPIR-V compilation, `BindGroupLayout`/`RenderPipeline` requirements, the core-shader inventory and the `post_effect` JSON format. |
| `26.2-gui-text-reference.md`, `26.2-gui-text-reference-exhaustive.md` | The interface text-rendering path in 26.2 (extraction, glyph atlases, the `GuiRenderer` submit path), which is what BBS's `Batcher2D` and its text drawing have to move onto. |

These are working notes, not reviewed documentation: treat a claim as verified only where it cites
a command or a source, and prefer `../26.2-vulkan-port.md` where the two disagree.
