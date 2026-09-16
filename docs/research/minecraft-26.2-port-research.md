# Research report: porting a Fabric mod from Minecraft 1.20.4 → 26.2

Research date: **2026-09-16** (system clock, UTC). Everything below was checked against live sources on that date.
Current latest release at time of writing: **26.3** (2026-09-15), i.e. **26.2 is one game drop behind latest**.

Method / evidence levels used throughout:

- **[PRIMARY]** — Mojang artifact or official Mojang/Fabric/NeoForge publication.
- **[JAR]** — read directly out of the actual shipped `client.jar` for 26.2 (sha1 `2dc72797acbc1b63fc16a11c4ac393605f453754`) with `javap`/zip inspection.
- **[COMMUNITY]** — mod author statements, issue threads, Modrinth metadata.
- **[UNCERTAIN]** — explicitly flagged as unverified.

---

## 1. Minecraft Java Edition 26.2 basics

| Fact | Value | Source |
|---|---|---|
| Release date | **2026-06-16** | [Minecraft Wiki: Java Edition 26.2](https://minecraft.wiki/w/Java_Edition_26.2), [official article](https://www.minecraft.net/en-us/article/minecraft-java-edition-26-2) |
| Drop name | **"Chaos Cubed"** (sulfur caves, sulfur cube mob, cinnabar/sulfur blocks, friends list) | same |
| Renderer headline | "Added experimental support for rendering the game through Vulkan" + new "Graphics API" video setting | [official 26.2 article](https://www.minecraft.net/en-us/article/minecraft-java-edition-26-2) |
| Java requirement | **Java 25** (`javaVersion: {"component":"java-runtime-epsilon","majorVersion":25}`) | [26.2 version JSON](https://piston-meta.mojang.com/v1/packages/987b91a95ae93b3bb78cc14d6e0bbd31bad08d59/26.2.json) **[PRIMARY]** |
| Java 21 was the previous level | 1.21.11 used `java-runtime-delta` / major 21 | [1.21.11 version JSON](https://piston-meta.mojang.com/v1/packages/f8d822c54003334930a71b7b7b5e16b3d98ea9fd/1.21.11.json) **[PRIMARY]** |
| LWJGL | **3.4.1** (includes `lwjgl-shaderc`, `lwjgl-spvc`, `lwjgl-vma`, `lwjgl-vulkan` macOS natives, `lwjgl-opengl`) | [26.2 version JSON](https://piston-meta.mojang.com/v1/packages/987b91a95ae93b3bb78cc14d6e0bbd31bad08d59/26.2.json) **[PRIMARY]** |
| Client jar size / sha1 | 39,193,383 bytes / `2dc72797acbc…` | same |
| `minimumLauncherVersion` | **21** (unchanged from 1.21.x) | same **[PRIMARY]** |
| Launch args | identical shape to older versions; **no `--vulkan`/renderer CLI or JVM flag** exists in the version JSON | same **[PRIMARY]** |
| JVM args | `-XstartOnFirstThread` (macOS), `-Xss1M`, `--sun-misc-unsafe-memory-access=allow`, `--enable-native-access=ALL-UNNAMED` | same **[PRIMARY]** |

### 1.1 Version numbering scheme change (1.21.x → 26.x)

Official announcement: **"Minecraft's new version numbering system"**, published **2025-12-02** ([minecraft.net](https://www.minecraft.net/en-us/article/minecraft-new-version-numbering-system)).

- From 2026 onward both Bedrock and Java versions are numbered by **year prefix**: 2026 releases begin with **`26`**, then release number, then patch/hotfix number.
- Java and Bedrock share the prefix but differ in the trailing numbers because of different release cadences.
- Snapshot names changed too: e.g. a snapshot that would have been `25w41a` becomes **`25.4-snapshot-1`** (intended version included in the name).
- The stated motivation was to help "creator partners and modders" tell drops from patches — **explicitly a modder-facing change**.
- Java path: `1.21.11` (Mounts of Mayhem, 2025-12-09) → **`26.1`** → `26.1.1`/`26.1.2` → **`26.2`** → `26.3`.

### 1.2 Obfuscation removal (the single biggest structural change)

- Mojang published a blog post announcing they would **remove obfuscation starting with the first snapshot after the Mounts of Mayhem (1.21.11) launch**, with experimental releases beginning one week earlier to give tooling time to migrate — described in Fabric's post **[Removing Obfuscation from Fabric](https://fabricmc.net/2025/10/31/obfuscation.html)** (2025-10-31) **[PRIMARY]**.
- Consequences Fabric announced there: **Intermediary will no longer exist**; **Yarn is not justifiable to maintain** for new versions; Fabric API names migrate to Mojang names; Loom gains a no-remap mode; production logs get readable names.
- Verified independently **[PRIMARY]**:
  - The **26.2 version JSON has only `client` and `server` under `downloads`** — there is **no `client_mappings`/`server_mappings`** entry (1.21.11 still had both). Mappings are no longer a separate downloadable artifact.
  - `FabricMC/fabric-docs` states: *"Minecraft 26.1 is unobfuscated and includes parameter names, so there is no need for any obfuscation mappings"* ([Fabric docs: Migrating Mappings](https://docs.fabricmc.net/develop/porting/mappings)).
  - `meta.fabricmc.net` reports `net.fabricmc:intermediary:0.0.0` for 26.2 — i.e. the identity mapping ([meta API](https://meta.fabricmc.net/v2/versions/loader/26.2)).

### 1.3 Launcher situation

- No launcher-format break: `minimumLauncherVersion` is still **21**, `mainClass` is still `net.minecraft.client.main.Main` **[PRIMARY, 26.2 version JSON]**.
- Because releases are unobfuscated and mappings are gone, the launcher/profile does not need mapping downloads and mod loaders no longer remap at runtime; Fabric explicitly said *"We aim to keep supporting all existing launchers"* ([obfuscation post](https://fabricmc.net/2025/10/31/obfuscation.html)).
- **[UNCERTAIN]** I found no evidence of any separate/new launcher requirement for Vulkan, and no documented per-launcher renderer switch. The renderer switch is in-game only.

---

## 2. The rendering backend change

### 2.1 Is OpenGL still supported? Is Vulkan default? Is there a toggle?

Policy announced in **[Another step towards Vibrant Visuals](https://www.minecraft.net/en-us/article/another-step-towards-vibrant-visuals-for-java-edition)** (2026-02-18) **[PRIMARY]**:

- Java Edition is switching **from OpenGL to Vulkan**; OpenGL is deprecated on macOS and will eventually stop working there.
- During the testing period players **can switch between OpenGL and Vulkan**.
- *"Once we're happy with the performance and stability of Vulkan across devices we will remove the OpenGL implementation."* — removal has **not happened yet**.

State in **26.2** ([official 26.2 article](https://www.minecraft.net/en-us/article/minecraft-java-edition-26-2), [wiki](https://minecraft.wiki/w/Java_Edition_26.2)) **[PRIMARY]**:

- Video Settings → **"Graphics API"** with three values: **Default**, **Prefer Vulkan (Experimental)**, **Prefer OpenGL**.
- **"Default" currently equals "Prefer OpenGL"** in 26.2. In the first 26.2 snapshot the default was Vulkan-if-supported; the release changed the default back ([26.2 Snapshot 1 article](https://www.minecraft.net/en-us/article/minecraft-26-2-snapshot-1)).
- Fallback behaviour: `Prefer Vulkan` falls back to OpenGL; `Prefer OpenGL` falls back to Vulkan; the setting is auto-downgraded after a startup crash (`Prefer Vulkan` → `Default` → `Prefer OpenGL`); with `Prefer OpenGL` the game never touches Vulkan.
- Changing it requires a **restart** (`options.graphicsApi.restart` = "You must restart your game to change the graphics API.") **[JAR, lang file]**.
- Vulkan requirement: **Vulkan 1.2 + `VK_KHR_dynamic_rendering` + push descriptors** ("may increase or decrease over time"); macOS goes through **MoltenVK**; under Vulkan the discrete GPU is preferred.
- Backend is visible in the **F3 overlay** (`system_specs` section), and telemetry gained `backend_name`, `backend_failure_reason`, `backend_failure_message`, `backend_failure_missing_capabilities`.
- **No JVM flag or CLI flag exists** for the renderer. Class-level confirmation **[JAR]**: `net.minecraft.client.PreferredGraphicsApi` = `{DEFAULT, OPENGL, VULKAN}` with `GpuBackend[] getBackendsToTry()`, and `Options.preferredGraphicsBackend` (`OptionInstance<PreferredGraphicsApi>`). Backend failure reasons are enumerated in `BackendCreationException$Reason` (`VULKAN_LOADER_MISSING`, `VULKAN_DEVICE_VERSION_TOO_LOW`, `VULKAN_MISSING_FEATURE`, `OPENGL_MISSING`, …).
- Still true in **26.3**: `OPENGL` and `VULKAN` both exist, label is still "Prefer Vulkan (Experimental)" **[JAR, 26.3 client.jar]**.

Important scope correction: **"Vibrant Visuals" itself is NOT in Java 26.2.** The wiki states Vibrant Visuals "is currently planned to be added to Java Edition at some point in the future", and that the Vulkan switch is preparation for it ([Minecraft Wiki: Vibrant Visuals](https://minecraft.wiki/w/Vibrant_Visuals)). 26.2 shipped the **renderer groundwork**, not the visual feature set.

### 2.2 The modder-facing rule: no raw OpenGL

Fabric's official documentation says it plainly **[PRIMARY]**:

> "Use of raw OpenGL is not supported on Minecraft 26.2 as it has released with an optional Vulkan backend. Instead, you must use the Blaze3D abstraction layer, which sits between your code and the rendering backend (either OpenGL or Vulkan)."
> — [Fabric Docs: Basic Rendering Concepts (26.2)](https://docs.fabricmc.net/develop/rendering/basic-concepts)

The Fabric 26.2 blog adds **[PRIMARY]**:

> "Developers making use of raw OpenGL calls rather than going through the Blaze3D API will need to migrate for their mods to work."
> — [Fabric for Minecraft 26.2](https://fabricmc.net/2026/06/15/262.html)

Mojang's own guidance to modders (2026-02-18 article) **[PRIMARY]**:

> "we recommend our modding community look at moving away from OpenGL usage. We encourage authors to try to reuse as much of the internal rendering APIs as possible… If that is not sufficient for your needs, then come and talk to us!"
> — plus an official **Vibrant Visuals Discord channel for modders** (announced originally in [The road to Vibrant Visuals on Java](https://www.minecraft.net/en-us/article/the-road-to-vibrant-visuals-on-java), 2025-10-22).

My own inspection of the shipped jar **[JAR]**:

- **All** raw LWJGL GL entry-point use in the 26.2 client is confined to `com.mojang.blaze3d.opengl.*` (GlStateManager, GlBuffer, GlCommandEncoder, GlDevice, GlProgram, GlSampler, GlQueryPool, GlConst, GlDebug, VertexArrayCache, BufferStorage, GlTransientMemory, DirectStateAccess…).
- The only LWJGL GL classes referenced anywhere in the jar are **`GL33C`** (OpenGL 3.3 core) and **`GLCapabilities`** (+ debug callbacks). `GL11`, `GL20`, `GL30`, `GL45` are referenced **nowhere** in vanilla code.
- `com.mojang.blaze3d.opengl.GlBackend` creates the window with an **OpenGL context** ("Failed to create window with OpenGL context"), while `com.mojang.blaze3d.vulkan.VulkanBackend` creates a **GLFW Vulkan surface** (`GLFWVulkan`). So each backend brings its own window/context; when the Vulkan backend is active there is no GL context at all.
- **Conclusion (high confidence, partly inference):** mod code may still *link* against `org.lwjgl.opengl.*` (the library is on the classpath for the GL backend), but it is unsupported, will observe/alter state that Blaze3D owns, and cannot work when the Vulkan backend is active. There is no compatibility layer.

Community evidence of exactly this failure mode **[COMMUNITY]**: a Voxy maintainer-filed thread shows a 26.2 port crashing in `GL11.glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING)` even though OpenGL was the active backend, with the reporter noting *"now that Minecraft officially supports multiple graphical APIs in the Blaze3D abstraction layer, OpenGL-specific code fails"* — [voxy#548](https://github.com/MCRcortex/voxy/issues/548).

### 2.3 Is there an OpenGL-on-Vulkan translation (Zink / legacy GL interop)?

- **In vanilla: no.** There is no Zink, ANGLE, or GL-over-Vulkan layer in the 26.2 jar. The only translation layer shipped is **MoltenVK (Vulkan → Metal) on macOS**, which is the opposite direction. No `lwjgl-opengles`, no Zink/ANGLE libs in the 26.2 library list **[PRIMARY, version JSON]**.
- **In the community: yes, as a mod.** The "Vulkanite" fork explicitly advertises **OpenGL–Vulkan interop** for hardware ray tracing on 26.2 (`VK_KHR_ray_tracing` style passes exposed to shader developers), built on Sodium 0.9.1 + Iris 1.11.2 ([Vulkanite 26.2 README](https://raw.githubusercontent.com/sjrsjz/vulkanite-modified/refs/heads/26.2/README.md)) **[COMMUNITY]**. That is a third-party technique, not a supported vanilla path, and it requires the OpenGL backend.

### 2.4 What happened to the old API surface (1.20.4 → 26.2)

Verified by class inventory of the 26.2 jar **[JAR]** and documented in the **[NeoForge 26.1.x → 26.2 migration primer](https://docs.neoforged.net/primer/docs/26.2/)** (the most detailed public modder reference for this step) **[PRIMARY]**:

| 1.20.4-era API | 26.2 status |
|---|---|
| `RenderSystem` | **Still exists**, but is now a facade: `RenderSystem.getDevice()` → `GpuDevice`, `getModelViewMatrixCopy()`, `getModelViewStack()`, `getProjectionMatrixBuffer()`, `getSamplerCache()`, `getSequentialBuffer(PrimitiveTopology)`, `bindDefaultUniforms(RenderPass)`, `shutdownRenderer()`. Removed: `flipFrame`, `getApiDescription`. |
| `ShaderProgram` / `ShaderInstance` | **Gone.** Replaced by `com.mojang.blaze3d.pipeline.RenderPipeline` / `CompiledRenderPipeline`, managed by `net.minecraft.client.renderer.ShaderManager`; device-level `GpuDevice#precompilePipeline(...)`. |
| `Framebuffer` | **Class removed.** Replaced by `com.mojang.blaze3d.pipeline.RenderTarget` / `TextureTarget` / `MainTarget` (with `GpuTexture` + `GpuTextureView` colour/depth attachments) and, for the GL backend only, `FrameBufferCache`/`FrameBufferAttachment` in `com.mojang.blaze3d.opengl`. |
| `VertexBuffer` / `VertexArray` | **Class removed.** Replaced by `GpuBuffer`/`GpuBufferSlice` + `RenderPass#setVertexBuffer(int, GpuBufferSlice)` / `setIndexBuffer(GpuBuffer, IndexType)`. |
| `BufferBuilder` | **Still exists** (`com.mojang.blaze3d.vertex.BufferBuilder`) but writes into the new staging/upload system. |
| `Tesselator` / `Immediate` / `MultiBufferSource` | **Removed.** Vanilla now uses `MeshData`, `StagedVertexBuffer`, and the extraction/submit ("RenderState") system. |
| `RenderLayer` / `RenderType` | `RenderType` still exists at **`net.minecraft.client.renderer.rendertype.RenderType`**; terrain layers were replaced by **`ChunkSectionLayer`** in 26.1, and chunk layers are now chosen automatically from sprite properties (translucency/cutout/solid). |
| `PoseStack` | **Still exists** (`com.mojang.blaze3d.vertex.PoseStack`), still the world-rendering matrix stack. For GUI/HUD the matrix stack is `Matrix3x2fStack`. |
| `GlStateManager` | **Still exists but only as part of the OpenGL backend**: `com.mojang.blaze3d.opengl.GlStateManager` (with `$BlendState`, `$DepthState`, `$CullState`, `$ColorLogicState`, `$ScissorState`, …). Not a cross-backend API. |
| Blend state | `SourceFactor`/`DestFactor` merged into **`BlendFactor`**, combined with **`BlendOp`**; used via `BlendFunction` and `ColorTargetState`. |
| Texture/buffer formats | `TextureFormat` + `VertexFormatElement$Type` replaced by the **`GpuFormat`** enum (e.g. `R8_UNORM`, `RGBA8_UNORM`, `R32_SINT`, `D32_FLOAT_S8_UINT`) **[PRIMARY]** |

### 2.5 The new API surface (packages and key types) — all **[JAR]** verified present in the 26.2 client

Generalised (backend-neutral) Blaze3D:

- `com.mojang.blaze3d.pipeline`: `RenderPipeline`, `RenderPipeline$Builder`, `RenderPipeline$Snippet`, `CompiledRenderPipeline`, `BindGroupLayout` (+`$Builder`, `$UniformDescription`), `ColorTargetState`, `DepthStencilState`, `BlendFunction`, `BlendEquation`, `RenderTarget`, `TextureTarget`, `MainTarget`
- `com.mojang.blaze3d.systems`: `RenderSystem`, `GpuDevice`, `GpuDeviceBackend`, `GpuBackend`, `CommandEncoder`, `CommandEncoderBackend`, `RenderPass`, `RenderPassDescriptor`, `RenderPass$RenderArea`, `GpuSurface`, `DeviceInfo`, `DeviceLimits`, `DeviceFeatures`, `DeviceType`, `GpuQueryPool`, `TimerQuery`, `ScissorState`, `SamplerCache`, `TransientMemory`, `TracyGpuProfiler`, `BackendCreationException$Reason`
- `com.mojang.blaze3d.buffers`: `GpuBuffer` (+`$Usage` annotation flags), `GpuBufferSlice`, `GpuBufferSlice$MappedView`, `GpuFence`, `Std140Builder`, `Std140SizeCalculator`
- `com.mojang.blaze3d.textures`: `GpuTexture` (+`$Usage`), `GpuTextureView`, `GpuSampler`, `AddressMode`, `FilterMode`
- `com.mojang.blaze3d.vertex`: `VertexFormat` (+`$Builder`), `VertexFormatElement` (record: name/offset/GpuFormat), `DefaultVertexFormat`, `BufferBuilder`, `ByteBufferBuilder`, `MeshData`, `VertexConsumer`, `PoseStack`, `StagingBuffer` (+`$Cpu`, `$PersistentlyMapped`, `$Uploader`, `$BufferHandle`), `UberGpuBuffer`, `TlsfAllocator`
- `com.mojang.blaze3d.shaders`: `ShaderSource`, `ShaderType`, `UniformType` (`UNIFORM_BUFFER`, `TEXEL_BUFFER`), `GpuDebugOptions`
- `com.mojang.blaze3d.preprocessor`: `GlslPreprocessor` (`#moj_import` handling)
- `com.mojang.blaze3d.framegraph`: `FrameGraphBuilder`, `FramePass`, `FrameGraphBuilder$Handle` (used for post-processing chains)
- `com.mojang.blaze3d.resource`: `GraphicsResourceAllocator`, `ResourceHandle`, `RenderTargetDescriptor`, `CrossFrameResourcePool`
- `com.mojang.blaze3d`: `GpuFormat`, `PrimitiveTopology` (`LINES`, `DEBUG_LINES`, `DEBUG_LINE_STRIP`, `POINTS`, `TRIANGLES`, `TRIANGLE_STRIP`, `TRIANGLE_FAN`, `QUADS`), `IndexType` (`SHORT`, `INT`), `ProjectionType`, `GpuDeviceLossException`, `GpuOutOfMemoryException`
- Minecraft side: `net.minecraft.client.renderer.RenderPipelines` (all vanilla pipeline snippets), `net.minecraft.client.renderer.StagedVertexBuffer`, `net.minecraft.client.renderer.ShaderManager`, `net.minecraft.client.renderer.rendertype.RenderType`, `net.minecraft.client.renderer.PostChain`, `net.minecraft.client.renderer.PostPass`, `net.minecraft.client.PreferredGraphicsApi`

Backend implementations (never use these directly in cross-backend code):

- `com.mojang.blaze3d.opengl.*` — 65 classes (`GlBackend`, `GlDevice`, `GlCommandEncoder`, `GlBuffer`, `GlTexture`, `GlTextureView`, `GlSampler`, `GlProgram`, `GlStateManager`, `FrameBufferCache`, `VertexArrayCache`, `DirectStateAccess`, `GlConst`, `GlDebug`, …)
- `com.mojang.blaze3d.vulkan.*` — 73 classes (`VulkanBackend`, `VulkanDevice`, `VulkanCommandEncoder`, `VulkanCommandPool`, `VulkanRenderPipeline`, `VulkanRenderPass`, `VulkanGpuBuffer/Texture/TextureView/Sampler/Surface`, `VulkanBindGroupLayout`, `VulkanQueue`, `VulkanConst`, `VulkanDebug`, `DestructionQueue`, plus `vulkan.checkpoints.*` for NVIDIA/AMD device-lost debugging)

Selected signatures **[JAR]**:

```java
// com.mojang.blaze3d.pipeline.RenderPipeline$Builder
withLocation(Identifier) / withVertexShader(Identifier) / withFragmentShader(Identifier)
withShaderDefine(String) / withShaderDefine(String,int) / withShaderDefine(String,float)
withBindGroupLayout(BindGroupLayout) / withVertexBinding(int, VertexFormat)
withPrimitiveTopology(PrimitiveTopology) / withCull(boolean) / withPolygonMode(PolygonMode)
withDepthStencilState(DepthStencilState) / withColorTargetState(ColorTargetState) / build()

// com.mojang.blaze3d.systems.RenderPass
setPipeline(RenderPipeline) / bindTexture(String, GpuTextureView, GpuSampler)
setUniform(String, GpuBuffer|GpuBufferSlice) / enableScissor(int,int,int,int)
setVertexBuffer(int, GpuBufferSlice) / setIndexBuffer(GpuBuffer, IndexType)
draw(int,int,int,int) / drawIndexed(int,int,int,int,int)
multiDraw(...) / multiDrawIndexed(...) / drawIndirect(GpuBufferSlice,int) / drawIndexedIndirect(GpuBufferSlice,int)
drawMultipleIndexed(Collection<RenderPass$Draw<T>>, GpuBuffer, IndexType, Collection<String>, T)

// com.mojang.blaze3d.vertex.VertexFormat$Builder
addAttribute(String, GpuFormat) / addAttribute(String, int, GpuFormat)
addAttribute(String, GpuFormat, int) / addAttribute(String, int, int, GpuFormat, int)

// com.mojang.blaze3d.systems.CommandEncoder
createRenderPass(...) / clearColorTexture / clearColorAndDepthTextures / clearDepthTexture
writeToBuffer(GpuBufferSlice, ByteBuffer) / copyToBuffer(...)
writeToTexture(...) / copyBufferToTexture(...) / copyTextureToBuffer(...) / copyTextureToTexture(...)
createFence() / writeTimestamp(GpuQueryPool,int) / submit()
```

### 2.6 Shaders for modders: format, uniforms, post-processing

**Shader authoring format is unchanged in spirit — still GLSL, not hand-written SPIR-V.**

- Core shaders in the 26.2 jar are still **GLSL**: `assets/minecraft/shaders/core/*.vsh` / `*.fsh` / `*.glsl`, e.g. `text.vsh` starts with `#version 330`, uses `in vec3 Position; in vec4 Color; in vec2 UV0;`, `uniform sampler2D Sampler2;`, `#moj_import <minecraft:fog.glsl>`, `gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);` **[JAR]**.
- There are **no shader-definition JSON files** left in `assets/minecraft/shaders/` — pipelines are defined **in code** (`RenderPipelines` / `RenderPipeline.builder(...)`) and reference shader `Identifier`s **[JAR]**.
- On Vulkan the GLSL is **cross-compiled to SPIR-V at runtime**: `com.mojang.blaze3d.vulkan.glsl.GlslCompiler` (*"Compiles the GLSL shaders into SPIR-V bytecode"*), `IntermediaryShaderModule`, `SpvcUtil`, `SpvSampler`, `SpvUniformBuffer`, `ShaderCompileException`; the jar ships `org.lwjgl:lwjgl-shaderc` and `org.lwjgl:lwjgl-spvc` **[JAR + version JSON]**. On OpenGL the same GLSL is compiled to GL programs (`GlProgram`) **[JAR]**.
- **Shader variants are now controlled by defines**, e.g. 26.2 replaced the six text shaders with two: `core/rendertype_text*` → **`core/text`** and `core/text_background`, with defines `IS_GUI`, `IS_SEE_THROUGH`, `IS_GRAYSCALE` (**[NeoForge primer](https://docs.neoforged.net/primer/docs/26.2/)** and confirmed by the jar contents) **[PRIMARY]**.
- **Uniforms and samplers** are declared through `BindGroupLayout` and set on `RenderPass` by name:
  ```java
  BindGroupLayout EXAMPLE_LAYOUT = BindGroupLayout.builder()
      .withSampler("Sampler0")
      .withUniform("Globals", UniformType.UNIFORM_BUFFER)
      .build();
  ```
  and attached with `RenderPipeline$Builder#withBindGroupLayout`; duplicate names across layouts throw during shader compilation. This replaced `RenderPipeline.UniformDescription` + per-pipeline sampler lists. `Std140Builder`/`Std140SizeCalculator` exist for building UBO data **[JAR]**.
- **Post-processing is largely unchanged in shape**: `assets/minecraft/post_effect/*.json` still exists (`blur.json`, `creeper.json`, `entity_outline.json`, `invert.json`, `spider.json`, `transparency.json`) and still references `vertex_shader`/`fragment_shader` identifiers, `inputs` (`sampler_name` + `target` + `bilinear`), `output` targets, and `uniforms` with named fields **[JAR]**. Loading/execution is via `PostChain`/`PostPass` and `FrameGraphBuilder` (the frame-graph API is the modern integration point).
- **Blending state**: `ColorTargetState` per render target (Sodium uses `new ColorTargetState(Optional.of(BlendFunction.TRANSLUCENT), GpuFormat.RGBA8_UNORM, 0xFFFFFFFF)`), with `BlendFactor` + `BlendOp` **[JAR + Sodium source]**.
- **Custom vertex formats**: `VertexFormat.builder(int stepRate).addAttribute(name, GpuFormat[, stepRate])`; a pipeline may bind **up to 16 vertex buffers** with different formats, but they must share one `PrimitiveTopology`; `VertexFormat$IndexType`/`$Mode` moved to `IndexType`/`PrimitiveTopology` **[PRIMARY, primer]**.
- **Custom render targets/framebuffers**: create `GpuTexture`(+`GpuTextureView`) via `GpuDevice#createTexture/createTextureView`, or use `RenderTarget`/`TextureTarget`; draw with `CommandEncoder#createRenderPass(...)`; render areas are now supported (`RenderPass$RenderArea`) **[JAR]**.

### 2.7 Compute shaders, storage buffers, persistent mapped buffers

Verified by exhaustive class/method search of the 26.2 jar **[JAR]**:

- **Compute shaders: not exposed.** There is no `ComputePipeline`/compute class anywhere in the jar, `CommandEncoder` and `RenderPass` have **no `dispatch(...)` method**, and `RenderPipeline` has no compute stage. (`GpuBuffer.USAGE_INDIRECT_PARAMETERS`' javadoc mentions "indirect draw and compute dispatch calls", but no compute entry point exists.)
- **Storage buffers: not exposed.** `GpuBuffer.USAGE_*` flags are exactly: `USAGE_MAP_READ`, `USAGE_MAP_WRITE`, `USAGE_HINT_CLIENT_STORAGE`, `USAGE_COPY_DST`, `USAGE_COPY_SRC`, `USAGE_VERTEX`, `USAGE_INDEX`, `USAGE_UNIFORM`, `USAGE_UNIFORM_TEXEL_BUFFER`, `USAGE_INDIRECT_PARAMETERS` — **no `USAGE_STORAGE_BUFFER`/SSBO**. `UniformType` is only `{UNIFORM_BUFFER, TEXEL_BUFFER}`. (Same in 26.3.)
- **Persistent mapped buffers: yes.** `GpuBuffer#map(long,long,boolean,boolean)` returns `GpuBufferSlice$MappedView`; `com.mojang.blaze3d.vertex.StagingBuffer$PersistentlyMapped` exists; `DeviceFeatures#persistentMapping` is the documented capability check; the GL backend has `GlTransientMemory$PersistentMapping`.

---

## 3. Modding ecosystem for 26.2

All version numbers below verified on 2026-09-16.

### 3.1 Fabric (26.2)

Fabric's official post **[Fabric for Minecraft 26.2](https://fabricmc.net/2026/06/15/262.html)** (2026-06-15) says **[PRIMARY]**:

- develop with **Fabric Loom 1.17** and **Gradle 9.5.1** (at the time of writing);
- players should install the latest stable **Fabric Loader — 0.19.3** at 26.2 release.

Verified against registries **[PRIMARY]**:

| Component | Value | Source |
|---|---|---|
| Fabric Loader (latest / release) | **0.19.5** (0.19.3 was current at 26.2's release) | [fabric-loader maven-metadata.xml](https://maven.fabricmc.net/net/fabricmc/fabric-loader/maven-metadata.xml), [meta API for 26.2](https://meta.fabricmc.net/v2/versions/loader/26.2) |
| Fabric API for 26.2 | latest **0.160.0+26.2**; many builds exist (0.153.0 … 0.160.0) | [fabric-api maven-metadata.xml](https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/maven-metadata.xml) |
| Fabric Loom | **1.17.x** for 26.2 (last 1.17 = 1.17.21); **1.18.2** is current for 26.3 | [fabric-loom maven-metadata.xml](https://maven.fabricmc.net/net/fabricmc/fabric-loom/maven-metadata.xml) |
| Gradle | 9.5.1 per Fabric's 26.2 post; current Gradle release is **9.7.1** | [services.gradle.org/versions/current](https://services.gradle.org/versions/current) |
| Java toolchain | **Java 25 minimum** (Fabric: *"Minecraft 26.1 requires Java 25 minimum for the Gradle JVM"*) — the game itself requests `java-runtime-epsilon`/25 | [Fabric 26.1 post](https://fabricmc.net/2026/03/14/261.html), [26.2 version JSON](https://piston-meta.mojang.com/v1/packages/987b91a95ae93b3bb78cc14d6e0bbd31bad08d59/26.2.json) |
| IntelliJ IDEA | 2025.3+ required for mixins to work correctly (stated for 26.1, still the relevant floor) | [Fabric 26.1 post](https://fabricmc.net/2026/03/14/261.html) |

### 3.2 Yarn vs Mojang mappings

- **Yarn is effectively discontinued for new versions.** Verified: [yarn maven-metadata.xml](https://maven.fabricmc.net/net/fabricmc/yarn/maven-metadata.xml) ends at **`1.21.11+build.6`** — no 26.x builds. [intermediary metadata](https://maven.fabricmc.net/net/fabricmc/intermediary/maven-metadata.xml) also ends at **`1.21.11`**.
- Fabric's obfuscation post: *"we can't see a way to justify maintaining Yarn in its current state… If you are a mod developer using Yarn, you will likely need to migrate to Mojang's names"* ([Removing Obfuscation from Fabric](https://fabricmc.net/2025/10/31/obfuscation.html)).
- **26.1 was the first unobfuscated release**; Fabric: *"no mods from 1.21.11 or before will work without, at a minimum, recompilation"* ([Fabric 26.1 post](https://fabricmc.net/2026/03/14/261.html)).
- Build-script consequence: switch from `net.fabricmc.fabric-loom-remap`/`fabric-loom` to the **`net.fabricmc.fabric-loom`** plugin, drop the `mappings` dependency, and use plain `implementation`/`compileOnly` + `jar` instead of `modImplementation`/`remapJar` ([Fabric 26.1 post](https://fabricmc.net/2026/03/14/261.html), [Fabric docs: Porting to 26.2](https://docs.fabricmc.net/develop/porting/), [Migrating Mappings](https://docs.fabricmc.net/develop/porting/mappings)).
- This workspace already reflects exactly this (`fabric.loom.disableObfuscation=true`, no `mappings` line, `implementation` for Fabric API, Java 25, Loom 1.17-SNAPSHOT, MC 26.2) — see `gradle.properties` / `build.gradle`.

### 3.3 NeoForge / Forge for 26.2

| Loader | 26.2 status | Source |
|---|---|---|
| **NeoForge** | active: latest 26.2 build **26.2.0.88** (neoforge maven also has 26.3 betas) | [neoforge maven-metadata.xml](https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml) |
| **Forge** | active: **26.2-65.1.3** (latest), **26.2-65.1.0** (recommended); 26.1.2-64.1.3 also present | [forge maven-metadata.xml](https://maven.minecraftforge.net/net/minecraftforge/forge/maven-metadata.xml), [promotions_slim.json](https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json) |
| NeoForge migration primer | *"Minecraft 26.1.x → 26.2 Mod Migration Primer"*, a **loader-agnostic vanilla-class migration guide** — the single most useful public document for this port | [docs.neoforged.net/primer/docs/26.2](https://docs.neoforged.net/primer/docs/26.2/) |

### 3.4 Documentation & tooling for mod authors

- **No Mojang-published API reference / javadoc for Blaze3D.** What exists officially: the two minecraft.net posts ([Feb 2026](https://www.minecraft.net/en-us/article/another-step-towards-vibrant-visuals-for-java-edition), [Oct 2025](https://www.minecraft.net/en-us/article/the-road-to-vibrant-visuals-on-java)) plus an **official modder-focused Discord channel** (Vibrant Visuals channel on Mojang's feedback Discord). Treat the Discord as the authoritative channel for questions Mojang explicitly invited.
- **Fabric docs (versioned to 26.2)**: [Porting to 26.2](https://docs.fabricmc.net/develop/porting/), [Basic Rendering Concepts](https://docs.fabricmc.net/develop/rendering/basic-concepts), [Rendering in the World](https://docs.fabricmc.net/develop/rendering/world) (custom `RenderPipeline`, extraction/drawing phases, `StagedVertexBuffer`, custom render states), [Migrating Mappings](https://docs.fabricmc.net/develop/porting/mappings).
- **`mcsrc.dev`** — Fabric-run online decompiled-source viewer for Minecraft (downloads the jar, decompiles with WASM Vineflower, plus mixin/AW tooling) ([Fabric 26.1 post](https://fabricmc.net/2026/03/14/261.html)). This is the closest thing to browsable source docs for 26.2.
- **NeoForge primer** (above) for the vanilla-side rename/behaviour list.

---

## 4. How the big rendering mods adapted

### Sodium — **has native Vulkan support; rewrote onto Blaze3D** **[COMMUNITY]**

- **Sodium 0.9.0 for MC 26.2** (2026-06-16) release notes: *"This is the first version to **experimentally** support Vulkan. To access it, use the Graphics API option in video settings."* and item *"**Moved all rendering to Mojang's Blaze3D API**"* — [release tag mc26.2-0.9.0](https://github.com/CaffeineMC/sodium/releases/tag/mc26.2-0.9.0).
- The port PR is **CaffeineMC/sodium#3735 "Port to Minecraft 26.2"** (merged 2026-06-16; 37 commits, +2155/−3897, 159 files). Its description is the clearest public account of a Vulkan migration:
  > "This includes a major rewrite to the renderer to run entirely within Blaze3D, Mojang's rendering API. This provides support for both OpenGL and Vulkan by default. A new `DrawContext` system has been introduced for the part of the renderer that requires API specific knowledge. There are three backends: `GLDrawContext` (replicates pre-PR behaviour), `VKMultiDrawContext` (preferred path on Vulkan; uses **push constants** as a replacement for uniforms and `EXT_multi_draw`), `VKIndirectContext` (since `EXT_multi_draw` isn't guaranteed; turns the draw into an indirect one with an extra GPU upload, always supported)."
  — [PR #3735](https://github.com/CaffeineMC/sodium/pull/3735) **[COMMUNITY]**
- Sodium still authors **GLSL** (.vsh/.fsh) and builds `RenderPipeline`s via `RenderPipeline.builder()…withBindGroupLayout(BIND_GROUP)`; it adds a Vulkan-only mixin to attach **push constant ranges** to its pipeline layouts (`VulkanPipelineMixin` wrapping `VulkanRenderPipeline#compile`) — [ShaderChunkRenderer.java](https://github.com/CaffeineMC/sodium/blob/26.2/stable/common/src/main/java/net/caffeinemc/mods/sodium/client/render/chunk/ShaderChunkRenderer.java), [VulkanPipelineMixin.java](https://github.com/CaffeineMC/sodium/blob/26.2/stable/common/src/main/java/net/caffeinemc/mods/sodium/mixin/core/VulkanPipelineMixin.java) **[COMMUNITY]**.
- Latest: **Sodium 0.9.2 for 26.2** (2026-09-11, Fabric + NeoForge); 0.9.2 for 26.3 (2026-09-15); 0.9.2 also for 26.1.2 — [Modrinth API](https://api.modrinth.com/v2/project/sodium/version).
- Known Vulkan-specific Sodium bugs (shows rough edges remain): [#3909](https://github.com/CaffeineMC/sodium/issues/3909) *"GLSL compilation error on Intel iGPU with Vulkan backend: non-opaque uniforms outside a block"* (open, 2026-09-12); [#3887](https://github.com/CaffeineMC/sodium/issues/3887) *"Black screen on start with Vulkan Graphics API"* (closed, 2026-08-20).

### Iris — **supports 26.2 on OpenGL only; Vulkan not supported** **[COMMUNITY]**

- Iris 1.11.x ships for 26.2 (Fabric + NeoForge): 1.11.0 (2026-06-16), 1.11.2 (2026-07-08), 1.11.4 (2026-09-13) — [Modrinth API](https://api.modrinth.com/v2/project/iris/version).
- **Changelog of 1.11.0+26.2: "Adds support for Minecraft 26.2. Note that Vulkan is not supported."** — Modrinth version metadata (same link) **[COMMUNITY]**. Later 26.2 changelogs do not repeat the caveat, so **[UNCERTAIN]** whether 1.11.2/1.11.4 still hard-refuse Vulkan.
- Iris maintainers on the February 2026 Vulkan announcement: *"IMS has been looking into this"* ([Iris#3024](https://github.com/IrisShaders/Iris/issues/3024)).
- On the "will Vulkan be supported?" question ([Iris#3171](https://github.com/IrisShaders/Iris/issues/3171)), community answers quote an Iris Discord message: *"Vulkan support is being developed as a separate mod"*, with the name **"aperture"** circulating ([Iris#3279](https://github.com/IrisShaders/Iris/issues/3279) asks for "the new shaders loader aperture"). **This is second-hand Discord content; I could not verify it from an official Iris source — [UNCERTAIN].**

### Distant Horizons — **26.2 supported; now has an OpenGL/Blaze3D engine choice, still OpenGL-centric** **[COMMUNITY]**

- DH `3.2.0-b-26.2` (2026-07-07), loaders fabric + neoforge — [Modrinth API](https://api.modrinth.com/v2/project/distanthorizons/version).
- Changelog highlights relevant to 26.2: *"Increase the minimum required OpenGL version 3.2 → 3.3"*, *"**Add a warning if OpenGL is used on MC 26.2**"*, *"When using the 'Auto' rendering engine, Iris will no longer cause the game to crash"*, and *"**If DH is set to explicitly use Blaze3D the game will still crash due to Iris not supporting Blaze3D**"* — so DH exposes an **Auto / OpenGL / Blaze3D** rendering-engine setting, and the Blaze3D path is the one that collides with Iris.
- The DH↔Iris interaction is also documented from Iris's side and was closed as "a DH compatibility issue, not an Iris issue" ([Iris#3200](https://github.com/IrisShaders/Iris/issues/3200), 2026-07-03), where the reporter says DH's `AUTO` engine picks `BLAZE_3D` on MC 26.1.2+ and that switching DH to `OPEN_GL` is the workaround.
- The DH maintainers' own answer to "how will the Vulkan update impact you?" is in [DH GitLab issue #1261](https://gitlab.com/distant-horizons-team/distant-horizons/-/issues/1261) (2026-06-02) — **I could not read the comments** (GitLab API returned 401 for notes, page is JS-rendered). **[UNCERTAIN — maintainer's stated plan unverified.]**

### Embeddium / Indium — **dead, no 26.x support** **[COMMUNITY]**

- **Embeddium**: last Modrinth update **2025-01-24**, game versions stop at **1.21.4** ([Modrinth API](https://api.modrinth.com/v2/project/embeddium)). Its role (NeoForge Sodium port) is now covered by Sodium itself, which publishes **NeoForge** builds (Sodium 0.9.2 for 26.2/26.3 on `neoforge`).
- **Indium**: last update **2025-02-25**, versions stop at **1.21.1** ([Modrinth API](https://api.modrinth.com/v2/project/indium)).
- Practical takeaway for a 1.20.4 port: don't plan around Embeddium/Indium APIs; target Sodium's own NeoForge/Fabric artifacts and FRAPI/Indigo where needed (Sodium 0.9.2 lists "Support FRAPI on NeoForge"; Fabric's Indigo/FModel modules were themselves in flux at 26.1, per the [Fabric 26.1 post](https://fabricmc.net/2026/03/14/261.html)).

---

## 5. Practical porting guidance (1.20.4 → 26.2)

### 5.1 Ordered plan

1. **Toolchain first** (this workspace is already here): Java 25 toolchain, Loom 1.17.x, Gradle 9.5.1+, Fabric Loader 0.19.5, Fabric API for 26.2, `net.fabricmc.fabric-loom` plugin, **no mappings dependency**, `implementation` instead of `modImplementation`, `jar` instead of `remapJar` ([Fabric docs porting](https://docs.fabricmc.net/develop/porting/)).
2. **Mappings migration** 1.20.4 (Yarn or Mojmap) → Mojang official names (now the only names). Loom ships rename tooling; manual review — especially mixins — is required ([Migrating Mappings](https://docs.fabricmc.net/develop/porting/mappings)).
3. **Then the vanilla diff.** Use the NeoForge primers as the contract: `1.20.4 → 1.20.5`, …, `1.21.11 → 26.1`, `26.1.x → 26.2` ([primer index](https://docs.neoforged.net/primer/docs/26.2/)). The 1.20.5/1.20.6 and 1.21.5/1.21.6 steps are where the old render stack (`MultiBufferSource`-centric drawing, `Tesselator`, `ShaderInstance`) is dismantled; 26.1/26.2 is where the backend abstraction lands.
4. **Inventory your render code** for raw GL and legacy classes, and rewrite each against Blaze3D. This workspace's own scan is representative and shows what to expect:
   - `org.lwjgl.opengl.*` imported in **21 files** (`GL11` ×21, `GL30` ×6, `GL13` ×5, `GL21`/`GL14`/`GL12` ×2, `GL20`/`GL15` ×1)
   - call sites: `GL11.glGetInteger` ×27, `GL11.glPixelStorei` ×16, `GL11.glIsEnabled` ×6, `GL11.glReadPixels` ×5, `GL11.glGetTexLevelParameteri` ×2, `GL11.glGetTexImage` ×2, `glDrawBuffer`/`glReadBuffer`/`glClear`, plus `GL11.GL_*` constant use ×125
   - legacy classes still referenced: `Tessellator` (26 files), `Immediate` (23), `GlStateManager` (10), `ShaderProgram` (10), `VertexBuffer` (9), `FrameBuffer` (1)
   Mapping sketch: GL state/capability queries → Blaze3D device/texture queries (`GpuTexture#getWidth/getHeight/getFormat/usage`, `DeviceFeatures`, `DeviceLimits`); framebuffer binding readback → `CommandEncoder#createRenderPass(...)` + `copyTextureToBuffer(...)` (`GpuBuffer` + `GpuFence`); `glPixelStorei/glTexImage` upload path → `GpuDevice#createTexture` + `CommandEncoder#writeToTexture(...)`; `glReadPixels` → `copyTextureToBuffer` + mapped `GpuBufferSlice$MappedView`; `ShaderProgram` → `RenderPipeline` + `BindGroupLayout` + `RenderPass#setUniform/bindTexture`; `VertexBuffer` → `GpuBuffer`/`StagedVertexBuffer`; depth/buffer clears → `CommandEncoder#clearColorAndDepthTextures`.
5. **Rendering entry points**: move to the extraction/drawing split. Do per-frame data collection in an **extraction** callback (e.g. Fabric's `LevelExtractionEvents.END_EXTRACTION`, `LevelExtractionContext`) and issue GPU work in a **render** callback (`LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN`, `LevelRenderContext`) using `StagedVertexBuffer`; see the complete, compilable example in [Fabric: Rendering in the World](https://docs.fabricmc.net/develop/rendering/world) and its source [CustomRenderPipeline.java](https://github.com/FabricMC/fabric-docs/blob/main/reference/latest/src/client/java/com/example/docs/rendering/CustomRenderPipeline.java).
6. **Custom pipelines**: keep GLSL `.vsh/.fsh` under `assets/<namespace>/shaders/…` (the path in `withVertexShader(Identifier)` is namespace-relative), declare samplers/uniforms via `BindGroupLayout`, pick `GpuFormat`s and `ColorTargetState`, and register the pipeline. **Note:** `RenderPipelines.register(...)` is `private static` in the 26.2 jar; Fabric API's `fabric-transitive-access-wideners-v1` module widens it (and all `RenderPipelines.*_SNIPPET` fields) transitively for mods — verified in `fabric-api-0.160.0+26.2.jar`. If you don't want that dependency path, build pipelines and hand them to `GpuDevice#precompilePipeline(RenderPipeline[, ShaderSource])` / keep them in your own map (this is what Sodium does).
7. **Custom post-processing**: keep `assets/<namespace>/post_effect/*.json` + `shaders/post/*.fsh`, load via `PostChain#load(PostChainConfig, TextureManager, Set<Identifier>, Identifier, Projection, ProjectionMatrixBuffer)`, and integrate with `FrameGraphBuilder` (`PostChain#addToFrame`, `PostPass#addToFrame`).
8. **Test on both backends.** Run with `Prefer OpenGL` and `Prefer Vulkan`; check the F3 `system_specs` line for the active backend. Remember the in-game setting needs a restart, and the game will silently fall back.
9. **Interactive help**: Mojang's Vibrant Visuals Discord channel is the sanctioned place to ask about gaps in the new render API (announced in the [Feb 2026 post](https://www.minecraft.net/en-us/article/another-step-towards-vibrant-visuals-for-java-edition)).

### 5.2 Look-ahead: 26.3 renames the API again

If you plan to follow 26.2 → 26.3 soon, know that **26.3 moved the whole GPU abstraction into a new namespace `com.mojang.renderpearl`** **[JAR, 26.3 client.jar]**:

- `com.mojang.renderpearl.api.{buffers,commands,device,pipeline,textures,vertex}` (≈70 API classes): `GpuBuffer` *(now an interface)*, `GpuDevice`, `RenderPipeline`, `BindGroupLayout`, `CommandEncoder`, `RenderPass`, `VertexFormat`, `GpuTexture`, `UniformType`, `ShaderSource`, …, plus `com.mojang.renderpearl.api.BackendCreationException`, `BlendFactor`/`BlendOp`, `CompareOp`, `PolygonMode`, `IndexType`, `PrimitiveTopology`.
- Backends: `com.mojang.renderpearl.backend.opengl` (63 classes) and `com.mojang.renderpearl.backend.vulkan` (59), plus `backend.common`, `backend.util`, `frontend`, `frontend.shaders`, `util`.
- `com.mojang.blaze3d.*` still exists for the Minecraft-facing pieces (e.g. `blaze3d.vertex`, `blaze3d.framegraph`, `blaze3d.resource`, `blaze3d.pipeline.RenderTarget`, `blaze3d.platform`), but the device/buffer/pipeline/texture API types moved.
- In 26.3 the `GpuBuffer.USAGE_*` set is unchanged (**still no storage-buffer flag**), `PreferredGraphicsApi` still has `OPENGL` + `VULKAN`, and the option label is still "Prefer Vulkan (Experimental)".

Announcements/posts written for 26.3 (e.g. [Fabric for Minecraft 26.3](https://fabricmc.net/2026/09/15/263.html)) will therefore reference the `renderpearl` names; 26.2-era docs reference `blaze3d` names. Don't mix them.

---

## 6. Confidence / gaps

**High confidence (verified against primary artifacts or official publications)**

- 26.2 release date, drop name, Java 25 requirement, LWJGL 3.4.1, unchanged launcher version and map-less version JSON.
- Numbering-scheme change and its rationale; 26.1 = first unobfuscated release; Yarn/Intermediary frozen at 1.21.11.
- Vulkan is experimental and **not** the default in the 26.2 release; toggle is the "Graphics API" video setting; no JVM/CLI switch; Vulkan 1.2 + dynamic rendering + push descriptors; MoltenVK on macOS.
- Raw OpenGL is unsupported per Fabric's docs; vanilla GL use is confined to `com.mojang.blaze3d.opengl.*` and only uses `GL33C`.
- The new API surface, the removal/renaming of `ShaderProgram`/`FrameBuffer`/`VertexBuffer`/`Immediate`/`Tesselator`/`MultiBufferSource`, `GlStateManager` becoming GL-backend-only, `GpuFormat`, `RenderPipeline`/`BindGroupLayout`/`RenderPass`, GLSL→SPIR-V compilation via shaderc, post_effect JSON still present.
- **No compute shaders and no storage buffers** in Blaze3D 26.2 (and none in 26.3).
- Fabric/NeoForge/Forge version availability for 26.2; Loom 1.17 / Gradle 9.5.1 / Java 25 guidance.
- Sodium 0.9.0+ natively supports Vulkan after a full Blaze3D rewrite; Iris 1.11.0 explicitly says Vulkan is not supported; Embeddium and Indium are abandoned with no 26.x builds.

**Explicitly NOT verified / uncertain**

1. **Runtime behaviour of raw GL calls under Vulkan.** I inferred "no GL context exists under the Vulkan backend" from backend-specific window/context creation (`GlBackend` vs `VulkanBackend`) plus the Fabric statement; I did not run the game. Exact failure mode (crash vs silent no-op) is unverified.
2. **Presence of any hidden JVM/system property to force the backend.** I found none in the version JSON args or in class string constants, but I cannot prove exhaustive absence.
3. **Iris's current Vulkan stance in 1.11.2/1.11.4.** Only 1.11.0's changelog states "Vulkan is not supported"; I could not find a later official statement.
4. **"aperture"** as Iris's separate Vulkan shader-loader mod. Source is a Discord screenshot quoted in GitHub issue comments; no official Iris/Fabric/Mojang announcement found. Treat as rumour-level.
5. **Distant Horizons' own roadmap for Vulkan.** DH's changelogs show a Blaze3D/OpenGL engine toggle, but the maintainer's reply in DH GitLab issue #1261 was unreadable (notes API 401; GitLab page JS-rendered). What DH's "Blaze3D" engine means for the vanilla Vulkan backend specifically is unverified.
6. **Whether Mojang has published any in-depth API documentation** beyond the two minecraft.net posts and the modder Discord. I found none (no official javadoc for Blaze3D/`renderpearl`); absence is based on searching, not on an authoritative index.
7. **Timeline for OpenGL removal.** Mojang promises advance notice; no date, and OpenGL is still present and still selectable in 26.3.
8. **`RenderPipelines.register` accessibility ergonomics.** I verified it is `private static` in both 26.2 and 26.3 and that Fabric API's transitive access wideners make it accessible; I did not compile a mod to confirm the widener is applied transitively to a plain Fabric API dependency in Loom 1.17.
9. **Fabric API's "recommended" version for 26.2** is not published in the blog post; I report the newest available build from maven metadata (0.160.0+26.2). Fabric's develop page is a JS app and does not expose a static recommended-version feed I could read.
10. **26.2's wiki claim "first version without any further revisions ever since Java Edition 1.1"** is a wiki statement, not something I corroborated elsewhere.

---

### Appendix: raw sources used

- Official articles: [26.2](https://www.minecraft.net/en-us/article/minecraft-java-edition-26-2) · [26.2 Snapshot 1](https://www.minecraft.net/en-us/article/minecraft-26-2-snapshot-1) · [new version numbering](https://www.minecraft.net/en-us/article/minecraft-new-version-numbering-system) · [Another step towards Vibrant Visuals](https://www.minecraft.net/en-us/article/another-step-towards-vibrant-visuals-for-java-edition) · [The road to Vibrant Visuals on Java](https://www.minecraft.net/en-us/article/the-road-to-vibrant-visuals-on-java)
- Mojang artifacts: [version manifest](https://launchermeta.mojang.com/mc/game/version_manifest_v2.json) · [26.2.json](https://piston-meta.mojang.com/v1/packages/987b91a95ae93b3bb78cc14d6e0bbd31bad08d59/26.2.json) · [26.2 client.jar](https://piston-data.mojang.com/v1/objects/2dc72797acbc1b63fc16a11c4ac393605f453754/client.jar) · [1.21.11.json](https://piston-meta.mojang.com/v1/packages/f8d822c54003334930a71b7b7b5e16b3d98ea9fd/1.21.11.json)
- Wiki: [Java Edition 26.2](https://minecraft.wiki/w/Java_Edition_26.2) · [Vibrant Visuals](https://minecraft.wiki/w/Vibrant_Visuals)
- Fabric: [Removing Obfuscation](https://fabricmc.net/2025/10/31/obfuscation.html) · [26.1](https://fabricmc.net/2026/03/14/261.html) · [26.2](https://fabricmc.net/2026/06/15/262.html) · [26.3](https://fabricmc.net/2026/09/15/263.html) · docs [porting](https://docs.fabricmc.net/develop/porting/) / [mappings](https://docs.fabricmc.net/develop/porting/mappings) / [rendering concepts](https://docs.fabricmc.net/develop/rendering/basic-concepts) / [rendering in the world](https://docs.fabricmc.net/develop/rendering/world) · maven [loader](https://maven.fabricmc.net/net/fabricmc/fabric-loader/maven-metadata.xml) / [api](https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/maven-metadata.xml) / [loom](https://maven.fabricmc.net/net/fabricmc/fabric-loom/maven-metadata.xml) / [yarn](https://maven.fabricmc.net/net/fabricmc/yarn/maven-metadata.xml) / [intermediary](https://maven.fabricmc.net/net/fabricmc/intermediary/maven-metadata.xml) · [meta API](https://meta.fabricmc.net/v2/versions/loader/26.2)
- NeoForge/Forge: [26.2 primer](https://docs.neoforged.net/primer/docs/26.2/) · [neoforge maven](https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml) · [forge maven](https://maven.minecraftforge.net/net/minecraftforge/forge/maven-metadata.xml) · [forge promotions](https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json)
- Mods: [Sodium 0.9.0 release](https://github.com/CaffeineMC/sodium/releases/tag/mc26.2-0.9.0) · [Sodium PR #3735](https://github.com/CaffeineMC/sodium/pull/3735) · [Sodium issue #3909](https://github.com/CaffeineMC/sodium/issues/3909) · [Iris #3024](https://github.com/IrisShaders/Iris/issues/3024) / [#3171](https://github.com/IrisShaders/Iris/issues/3171) / [#3200](https://github.com/IrisShaders/Iris/issues/3200) / [#3279](https://github.com/IrisShaders/Iris/issues/3279) · [Voxy #548](https://github.com/MCRcortex/voxy/issues/548) · [DH #1261](https://gitlab.com/distant-horizons-team/distant-horizons/-/issues/1261) · Modrinth APIs for [sodium](https://api.modrinth.com/v2/project/sodium/version), [iris](https://api.modrinth.com/v2/project/iris/version), [distanthorizons](https://api.modrinth.com/v2/project/distanthorizons/version), [embeddium](https://api.modrinth.com/v2/project/embeddium), [indium](https://api.modrinth.com/v2/project/indium) · [Vulkanite fork](https://github.com/sjrsjz/vulkanite-modified)
- Tooling: [Gradle current](https://services.gradle.org/versions/current)
