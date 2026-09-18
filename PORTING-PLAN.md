# Porting Plan: Flashback + ServerReplay → Minecraft 26.3

Goal: port both mods from Minecraft 26.2 to 26.3 ("Wilderness Bound", released 2026-09-15).

Repos:
- `ServerReplay/` — upstream `github.com/senseiwells/ServerReplay`, branched from `26.2`
- `Flashback/` — upstream `github.com/Moulberry/Flashback`, branched from `master` (which targets 26.2)

No upstream 26.3 work exists in either repo (no branches, PRs, or issues) — these are original ports.

---

## Verified 26.3 toolchain

| Piece | Status |
|---|---|
| Fabric Loader | 0.19.5 stable |
| Loom / Gradle | Loom 1.17 + Gradle 9.6.0 (official recommendation) |
| Fabric API | `0.160.7+26.3` (latest at time of writing) |
| Fabric Language Kotlin | `1.14.1+kotlin.2.4.20` — MC-agnostic, only needs loader >=0.19.5 |
| arcade (ServerReplay core dep) | `0.14.0-beta.3+26.3` already on `maven.supersanta.me/snapshots` |
| Java | 25 required |
| Input | `lwjgl-sdl:3.4.3` ships with the game; `lwjgl-glfw` removed |

Sources: `meta.fabricmc.net`, `maven.fabricmc.net`, `maven.supersanta.me`, fabricmc.net 26.3 blog post, and a `javap` diff of the official 26.2 vs 26.3 client jars.

---

## What changed in MC 26.3 (verified via jar diff)

### Render layer (biggest impact — Flashback)

`com.mojang.blaze3d.opengl.*`, `systems.GpuDevice`, `CommandEncoder`, `RenderPass`,
`pipeline.RenderPipeline`/`BindGroupLayout`/`BlendFunction`/`ColorTargetState`,
`buffers.*`, `textures.*`, and top-level `GpuFormat`/`IndexType`/`PrimitiveTopology`
are **all deleted**, replaced by `com.mojang.renderpearl`:

- `renderpearl.api.*` (GpuFormat, GpuSurface, device, buffers, textures, pipeline, vertex)
- `renderpearl.frontend.*` (FrontendGpuDevice, FrontendCommandEncoder, FrontendRenderPass,
  FrontendRenderPipeline, FrontendGpuSurface)
- `renderpearl.backend.{opengl,vulkan}` — SPIR-V shaders via `lwjgl-shaderc`/`spvc`; Vulkan
  backend exists as an alternative to OpenGL
- `Minecraft.windowSurface` field type moved `blaze3d.systems.GpuSurface` →
  `renderpearl.api.device.GpuSurface`
- New `net.minecraft.client.renderer.oit` package (order-independent transparency);
  `RenderSetup` lost `OutputTarget`, gained `OitPipelineSet`; `RenderType` pipeline types
  moved to `renderpearl.api.pipeline.*`
- `LevelRenderer.render(...)` signature changed; `cloudsTarget`/`entityOutlineTarget`/
  `itemEntityTarget`/`particlesTarget`/`translucentTarget`/`weatherTarget` getters removed;
  `doEntityOutline`→`blitEntityOutline`; `prepareChunkRenders`/`prepareChunkRendersIndirect`
  + multi-draw-indirect (`Minecraft.multiDrawIndirect`)
- `Window`: fullscreen handling reworked (borderless/exclusive split); `width`, `height`,
  `framebufferWidth`, `framebufferHeight` fields intact

### Input: GLFW → SDL3

- `lwjgl-sdl:3.4.3` is bundled → `org.lwjgl.sdl.*` is callable from Java
- `blaze3d.platform.SDLEventHandler`, `SdlDebug`, `TextInputManager` added
- `MouseHandler`: `onButton`/`onDrop`/`onMove`/`onScroll` now **public**;
  `onMove` gained two extra doubles; `onDrop(List<Path>)`→`onDrop(List<String>)`;
  `setup(Window)` removed; `isMouseGrabbed`/`grabMouse`/`releaseMouse`/`turnPlayer` unchanged
- `KeyboardHandler`: `keyPress`/`charTyped` now public; `preeditCallback` →
  `textEditing` + `textInput`; `setup(Window)` removed
- New `net.minecraft.client.input` event classes (`KeyEvent`, `MouseButtonEvent`,
  `CharacterEvent`, `PreeditEvent`, `InputWithModifiers`)
- `InputConstants`/`KeyMapping.key` survive, but constants are now SDL-based;
  mouse button constant values changed — do not hardcode
- Custom text widgets must notify `TextInputManager` on focus change
  (`Minecraft.getInstance().textInputManager().onTextInputFocusChange(...)`)

### Network / protocol

New packets: `ClientboundAddTransientBlockPacket`, `ClientboundSwingAnimationPacket`,
`ClientboundPostEffectsPacket` (common), `ServerboundPunchPacket` (replaces
`ServerboundSwingPacket`), `MovementPacket`, `VecDelta`/`VecDelta$Linear`/`VecDelta$Stepped`.

New abstract listener methods:
- `ClientGamePacketListener`: `handleAddTransientBlockPacket`, `handleSwingAnimation`
- `ClientCommonPacketListener`: `handlePostEffects`

Entity movement sync overhauled:
- `ClientboundEntityPositionSyncPacket`: `PositionMoveRotation` → `PositionPath` + xRot/yRot
  floats; now implements `MovementPacket`
- `ServerEntity`: `updateInterval` field type `int` → `world.entity.UpdateInterval`; new
  `InterpolationTracker` field; ctor changed to `(ServerLevel, Entity, UpdateInterval,
  boolean, Synchronizer)`; new `createMovePacket`/`isFullPrecisionEncodingRequired`
- `VecDeltaCodec` still exists; `PositionMoveRotation` still exists (used by teleport packet)

Packets becoming records / StreamCodec-based:
- `ClientboundLevelChunkWithLightPacket` → record: `getX→x()`, `getZ→z()`,
  `getChunkData→chunkData()`, `getLightData→lightData()`
- `ClientboundLevelChunkPacketData` → record + `STREAM_CODEC`;
  `getBlockEntitiesTagsConsumer` → `forEachBlockEntityTag`
- `ClientboundLevelChunkPacketData$BlockEntityInfo`: `packedXZ` int→**byte**, `y` int→**short**,
  `tag` → **Optional\<CompoundTag>**
- `CommonPlayerSpawnInfo`: `previousGameType` `GameType` → `Optional<GameType>`; ctor changed
- `ClientboundLoginPacket`/`ClientboundPlayerInfoUpdatePacket`/`ClientboundSetTimePacket`:
  public API/ctors unchanged (internals moved to StreamCodec)
- `ServerGamePacketListenerImpl`: `handleAnimate(ServerboundSwingPacket)` →
  `handlePunch(ServerboundPunchPacket)`

### Worldgen

`ChunkGenerator` abstract set reworked (matches blog: material rules / configured features /
provider refactors):
- `fillFromNoise` → `buildTerrain(ChunkAccess, Blender, RandomState, StructureManager,
  BiomeManager, WorldGenRegion, Set<Holder<Biome>>)`
- `applyCarvers` / `buildSurface` removed (merged into buildTerrain)
- `addDebugScreenInfo` gained `SamplerContext` param; `getMobsAt` `Holder<Biome>`→`Level`
- `decorateBiomeResolver` added

### Server / other

- `MinecraftServer`: `potionBrewing`/`fuelValues` removed (item-component refactor);
  `getStructureManager` → `getStructureTemplateManager`; `publishServer` signature changed;
  `forceGameMode`/`setForceGameMode` added
- `PlayerList`: `allowCommandsForAllPlayers`/`isAllowCommandsForAllPlayers`/
  `setAllowCommandsForAllPlayers` removed → `playerPermissions` map
- `ServerPlayer`: `drop` takes `Prediction` instead of boolean; `onEquippedItemBroken`
  Item→ItemStack; `openTextEdit` +`SignTextSlot`; `startSleepInBed` new signature;
  `getWardenSpawnTracker` `Optional`→direct; new post-effects API
  (`addPostEffect`/`clearPostEffects`/`removePostEffect`/`sendPostEffects`/`getPostEffects`)
- `PrimaryLevelData`: new `versionHistory` field + ctor param
- `Screen`: narration internals changed; `renderables` field intact; `isInputCaptured` added
- Reloadable dynamic registries now include recipes/advancements
  (`DynamicRegistries.registerReloadable`) — runtime-verify the config-sync path
- Fabric API removals (irrelevant to both mods): `FuelRegistry`, `CompostingChanceRegistry`,
  `FabricPotionBrewingBuilder`, `Strippable/Tillable/FlattenableBlockRegistry`

### Verified UNCHANGED (do not re-port)

`Connection.genericsFtw` (packet capture linchpin), `RegistrySynchronization`,
`TagNetworkSerialization`, `ClientboundRegistryDataPacket`, `ConfigurationProtocols`,
`GameProtocols.CLIENTBOUND_TEMPLATE`, `ClientboundAddEntityPacket`,
`ClientboundPlayerInfoUpdatePacket$Entry`, `ClientboundSetTimePacket`,
`IntegratedServer` ctor, `KeyMapping`, `LayeredRegistryAccess`, `HolderSet$Named`,
`RegistryAccess$ImmutableRegistryAccess`, `SynchedEntityData`, `WorldLoader`,
`LevelSettings`, `ServerConfigurationPacketListenerImpl`, `SoundEngine`/`Library` internals,
`MinecraftServer.spin`/`doWorldLoad`/`registries`/`gameRules`/`levels`/`executor`,
`ClientboundEntityPositionSyncPacket` unaffected class `PositionMoveRotation`,
`MouseHandler.turnPlayer`/`grabMouse`/`isMouseGrabbed`.

Note: Flashback's `ca.spottedleaf.starlight.*` imports do NOT come from Minecraft — they
are shaded inside the **ScalableLux** mod jar (compileOnly pin `gYbHVCz8`). The starlight API
is version-stable; the pin can stay for compilation even before a 26.3 build exists, but
runtime behavior requires a 26.3 ScalableLux.

---

## ServerReplay — LOW effort, do first

The repo is a ~25-file Kotlin shell: **zero mixins, zero access wideners, no Java**.
All packet interception, chunk/entity tracking, and `.mcpr`/flashback serialization lives
in `arcade-replay` (already ported upstream → `0.14.0-beta.3+26.3`).

### Steps

1. Branch `26.2` → `26.3`.
2. `libs.versions.toml`:
   - `minecraft = "26.3"`
   - `fabric-loader = "0.19.5"`
   - `fabric-api = "0.160.7+26.3"`
   - `fabric-kotlin = "1.14.1+kotlin.2.4.20"`
   - `arcade = "0.14.0-beta.3+26.3"` (verify all 7 modules exist at this version:
     replay, commands, event-registry, events-server, resource-pack-host, interceptor, utils)
   - keep `fabric-loom = "1.17-SNAPSHOT"`, `mod-publish = "2.1.1"`
3. Kotlin plugin version auto-parses from the FLK string → Kotlin 2.4.20. No manual step,
   but verify the parse still works with the new version format.
4. Fix `jitpack.yml`: pins Java 21.0.2 — stale, bump to 25 if jitpack builds still used.
5. `fabric.mod.json` deps expand from the catalog automatically (`~26.3`,
   `>=0.160.7+26.3`, `>=1.14.1+...`). Verify after `processResources`.
6. Compile; expected fix sites:
   - ~76 `net.casual.arcade.*` call sites across 12 files → arcade 0.13→0.14 API drift
     (check arcade upstream diff/changelog for renamed APIs; the recorder/viewer/format
     entry points used are: `ReplayPlayerRecorders`, `ReplayChunkRecorders`, `ReplayViewers`,
     `ReplayFormat`, `ReplayModIO`, `FlashbackIO`, `RecorderSettings`/`CODEC`s,
     `ArcadeInterceptors`, replay events, `ResourcePackTracker` duck, `commands.*` DSL)
   - Direct MC surface (~34 imports): `net.minecraft.server.permissions` API
     (`PermissionLevel`, `Permission.HasCommandLevel`, `PermissionSet`), `NameAndId`,
     `ClientboundResourcePackPushPacket` 5-arg ctor, `Identifier` 2-arg ctor + CODEC,
     `player.connection`, `source.level`, `playerList.broadcastSystemMessage`,
     `scoreboard.getPlayersTeam`
7. Test on a dedicated server: start → player + chunk-area recording → markers →
   `.mcpr` and Flashback-format exports → HTTP downloader interceptor → `/pack` resource-pack
   flow → crash/stop recovery (`RecorderRecoverer` + replaystudio path).

---

## Flashback — HIGH effort, do second

~353 Java files, 110 mixin entries (60 client + 50 common), ~110 access-widener entries,
~43 files touching the GPU abstraction, 14 files using GLFW, vendored ImGui fork + FFmpeg.

### Phase 0 — build plumbing

- `gradle.properties`: `minecraft_version=26.3`, `loader_version=0.19.5`,
  `fabric_version=0.160.7+26.3`, bump `mod_version`
- `build.gradle`: loom `1.15-SNAPSHOT` → `1.17-SNAPSHOT`; verify `com.gradleup.shadow:9.3.0`
  works on Gradle 9.6
- `gradle/wrapper/gradle-wrapper.properties`: Gradle 9.4.1 → 9.6.0
- `fabric.mod.json`: `"minecraft": ">26.1 <26.3"` → widen (e.g. `>26.1 <26.4`)
- Audit the 7 `compileOnly` Modrinth pins — sodium/iris/DH/bobby/voicechat/modmenu/
  scalablelux. Sodium/Iris had no 26.3 builds at release; compat mixins are
  `@IfModLoaded`-guarded so the mod can build/ship without them initially.
- Keep ScalableLux pin for `ca.spottedleaf.starlight.*` compile surface (see note above).

### Phase 1 — compile fixes, bottom-up

1. **Packet listeners** — `playback/ReplayGamePacketHandler.java` implements
   `ClientGamePacketListener` (~135 methods): add `handleAddTransientBlockPacket`,
   `handleSwingAnimation`. `ClientCommonPacketListener` impl gets `handlePostEffects`.
   Decide record-vs-ignore for each in `IgnoredPacketSet` (transient block + swing animation
   are world state → probably record; post effects → decide). Sweep
   `ServerboundSwingPacket`→`PunchPacket` references.
2. **Movement overhaul** — `PacketHelper` (`ClientboundEntityPositionSyncPacket` ctor now
   `(int, PositionPath, float, float, boolean)`), `mixin/playback/MixinServerEntity`
   (`updateInterval` is now `UpdateInterval`, `InterpolationTracker`, `createMovePacket`),
   `ReplayGamePacketHandler` teleport handling (`clientboundTeleportEntityPacket.change()`
   still returns `PositionMoveRotation` — verify), `ReplayServer:1213` sync-packet ctor.
   Re-validate every `UnsafeWrapper` field poke against the new `ServerEntity` layout.
3. **Record-ified packets** — `CachedChunkPacket`, `AsyncReplaySaver`, `ReplayChunkCache`:
   `x()/z()/chunkData()/lightData()` renames; `BlockEntityInfo` byte/short/Optional types;
   `forEachBlockEntityTag`. ~67 `getX/getZ/getChunkData/getLightData` call sites to sweep
   (not all are this packet — audit individually).
4. **Snapshot** — `Recorder.writeSnapshot`: `CommonPlayerSpawnInfo` now takes
   `Optional<GameType>` for previousGameType; re-verify all other packet ctor arities
   (login/playerinfo/time/spawn verified unchanged).
5. **Worldgen** — `playback/EmptyLevelSource.java`: reimplement against new
   `ChunkGenerator` abstract set (`buildTerrain`, new `addDebugScreenInfo`/`getMobsAt`
   signatures, `decorateBiomeResolver`).
6. **Server classes** — `ReplayPlayer`/`FlashbackFakePlayer` override signatures:
   `getWardenSpawnTracker` Optional→direct, `drop`→`Prediction`,
   `onEquippedItemBroken` Item→ItemStack, `startSleepInBed`; `ReplayServer`'s anonymous
   `PlayerList` — `allowCommands` APIs removed → `playerPermissions` map.
   (No usages of `getStructureManager`/`potionBrewing`/`fuelValues` exist — confirmed.)
7. **World bootstrap** — `Flashback.openReplayWorld` + `CombineReplayScreen`:
   `PrimaryLevelData` ctor gained `versionHistory`; `WorldLoader`/`LevelSettings`/
   `WorldDimensions` verified unchanged → likely minimal edits.
8. **Config phase: likely untouched** — `SynchronizeRegistriesTask`,
   `RegistrySynchronization`, `TagNetworkSerialization`,
   `ServerConfigurationPacketListenerImpl` all identical →
   `MixinServerConfigurationPacketListenerImpl`, `ReplayConfigurationPacketHandler`,
   `MixinServerGamePacketListenerImpl` (`flashback$switchToConfigWithTasks`) probably compile
   as-is. Runtime-verify against the reloadable-registry refactor (recipes/advancements now
   live in reloadable registries; `MixinMinecraftServer` wraps
   `TagLoader.loadTagsForExistingRegistries` — confirm behavior still correct).
9. **Lenient-registry mixins** — verify descriptors still match:
   `HolderSetCodec`/`RegistryFileCodec`/`RegistryFixedCodec` encode `(...)DataResult`,
   `Registry.lambda$referenceHolderWithLifecycle$0` (lambda name fragile),
   `MappedRegistry.freeze`, `Holder(Set).canSerializeIn`.
10. **Access widener** — re-validate all ~110 entries. Known affected:
    `ServerEntity.updateInterval` (type), `ClientboundLevelChunkPacketData`/`BlockEntityInfo`
    fields→record components. Verified still valid: `Window.*`, `KeyMapping.key`,
    `SoundEngine`/`Library`/`Channel`, `LayeredRegistryAccess.*`,
    `HolderSet$Named.bind`, `ImmutableRegistryAccess.registries`, `SynchedEntityData.itemsById`,
    `Screen.renderables`, `ClientChunkCache$Storage.chunks`, `PrimaryLevelData.{settings,
    specialWorldProperty}`, `MinecraftServer.{registries,gameRules,worldData,executor,levels}`,
    `ClientboundPlayerInfoUpdatePacket.entries`.

### Phase 2 — render + input rewrite (the real work)

1. `editor/ui/CustomImGuiImplB3D.java` (386 lines) — rewrite against
   `renderpearl.frontend.*` / `renderpearl.api.*` (GpuDevice→FrontendGpuDevice,
   RenderPipeline→FrontendRenderPipeline, GpuBuffer/GpuTexture/GpuSampler/GpuTextureView→
   renderpearl.api.{buffers,textures}, GpuFormat/PrimitiveTopology/VertexFormat→renderpearl.api).
   ImGui's GLSL shader needs an SPIR-V path (shaderc is bundled with MC). If written against
   the frontend abstraction it should work on both GL and Vulkan backends.
2. `mixin/export/MixinGlCommandEncoder.java` — target `blaze3d.opengl.GlCommandEncoder` is
   deleted (as is `DirectStateAccess`). Re-find the framebuffer-texture-binding hook inside
   the renderpearl opengl backend or framegraph path.
3. `editor/ui/CustomImGuiImplGlfw.java` (1,559 lines) — rewrite as an SDL backend
   (`CustomImGuiImplSdl`) using `org.lwjgl.sdl.*`, hooked into vanilla's
   `SDLEventHandler`/input event flow (or vanilla's now-public `MouseHandler`/
   `KeyboardHandler` callbacks — a viable simplification). Must notify `TextInputManager`
   on text-field focus. Clipboard/cursor APIs → SDL. **The vendored imgui natives are
   reusable** (verified: zero GLFW/SDL/GL symbols in the DLL — platform+render glue is
   all Java-side).
4. Keybind layer — `Keybind`, `KeybindHelper`, `Keybinds`, `windows/KeybindsWindow`,
   `EditorMovementControls`: `GLFW_*` constants → `InputConstants`/SDL keycodes (mouse
   button constant values changed — map via `InputConstants`, never hardcode).
5. Export path — `ExportJob` (`GpuSurface` moved packages, `blitFromTexture` signature),
   `SaveableFramebuffer(+Queue)`, `FramebufferUtils`, `TransformDepthUniform`,
   `PerfectFrames` (FREX): framebuffer readback via the renderpearl command encoder /
   copy-texture-to-buffer equivalent. `NativeImage` fields verified intact.
6. `mixin/MixinMinecraft` — `renderFrame` wraps `GpuSurface.blitFromTexture`; `GpuSurface`
   moved `blaze3d.systems`→`renderpearl.api.device` → update the target descriptor.
   (`doWorldLoad`/`MinecraftServer.spin` wrap verified unchanged.)
7. ~40 `visuals/`/`playback/`/`export/` mixins — all target classes still exist, but
   signatures shifted: `LevelRenderer.render` (new signature), `lambda$addMainPass$0`,
   `GameRenderer.render` wrap of `CommandEncoder.clearDepthTexture` (class moved packages —
   the `remap=false` descriptor must be updated), `Hud.extract*`, `FogRenderer.setupFog`,
   `Projection`, `Camera`, `submit*`/`extract*` render-state APIs. Re-verify every `@At`.
8. `MixinWindow` — targets verified unchanged; `Window` internals reworked but
   width/height/framebuffer fields intact.

### Phase 3 — audio, compat, polish

- `mixin/audio/MixinAudioLibrary` + `MixinSoundEngine` — `Library` unchanged,
  `SoundEngine` additive changes only; verify `alcCreateContext` wrap still applies.
- Compat mixins per-mod once 26.3 builds exist (sodium/iris/bobby/voicechat/DH/axiom/
  modmenu). `remap=false` string targets re-verified per mod. May ship initial port with
  compat disabled via `@IfModLoaded`.
- Re-check `breaks` ranges (iris `<1.10.9`, voicechat `<2.6.23`) against 26.3 builds.
- voicechat entrypoint API (`de.maxhenkel.voicechat.api.*`) version check.
- Fabric-internal usages to re-verify against new Fabric API: `PacketContext`/
  `PacketContextProvider`, `UntrackedPacketListener`, `ServerPlayNetworking.canSend/send`
  mixin, `BiomeModificationImpl`, `RecipeSyncImpl` (recipe sync → reloadable registries may
  have changed this impl).

### Replay-format compatibility

Metadata auto-stamps the new protocol/data version (`SharedConstants` at runtime — no
hardcoded values). Old 26.2 replays will show `ReplaySummary`'s mismatch warning; several
packets' wire formats changed (`EntityPositionSync`, `LevelChunkWithLight`,
`PlayerInfoUpdate` entry codec), so chunk caches and position packets from old files may
decode incorrectly. Treat 26.2 replay compat as best-effort; do not gate the port on it.
`ReplayCombiner` already refuses cross-dataVersion merges.

### Testing checklist

- Recording in singleplayer + on a dedicated server (via ServerReplay flashback export)
- Playback: entities, chunks, weather, boss bars, scoreboard, maps, tab list, resource packs
- Registry re-sync path (`updateRegistry` → config→play transition)
- Chunk cache dedup (`level_chunk_caches`)
- Editor: ImGui UI, keybinds, keyframes, freecam, markers
- Export: FFmpeg video, PNG sequence, audio capture (`SOFTLoopback`), FREX flawless frames
- Compat: Distant Horizons, Sodium, Iris, Bobby, Simple Voice Chat, Axiom, ModMenu

---

## Suggested order

1. **ServerReplay** — quick win; validates toolchain end-to-end (arcade already ported).
2. **Flashback** — Phase 0 plumbing → Phase 1 compile fixes → Phase 2 render/input →
   Phase 3 compat/polish. The mixin surface mostly survived (verified); the concentrated
   risk is the renderpearl rewrite + SDL input.
3. Consider upstream PRs once stable.
