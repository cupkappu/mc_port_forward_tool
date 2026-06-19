# Build Version Notes

Project: Minecraft Transport Dialer
Date: 2026-06-19
Baseline: Fabric compatibility matrix

## Selected Versions

| Target | Minecraft | Fabric Loader | Fabric API | Yarn mappings | Java |
|--------|-----------|---------------|------------|---------------|------|
| `1.20.1` | `1.20.1` | `0.19.3` | `0.92.9+1.20.1` | `1.20.1+build.10` | 17 |
| `1.21.1` | `1.21.1` | `0.19.3` | `0.116.12+1.21.1` | `1.21.1+build.3` | 21 |

Version-specific coordinates live in `versions/*.properties`. Build with:

```
./gradlew build -PtargetMinecraft=1.20.1
./gradlew build -PtargetMinecraft=1.21.1
```

## Source URLs

- Fabric Loader metadata:
  - https://meta.fabricmc.net/v2/versions/loader/1.20.1
  - https://meta.fabricmc.net/v2/versions/loader/1.21.1
- Fabric API Maven metadata:
  - https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/maven-metadata.xml
- Yarn Maven metadata:
  - https://maven.fabricmc.net/net/fabricmc/yarn/maven-metadata.xml
- Fabric Loom docs:
  - https://docs.fabricmc.net/develop/loom/

## Compatibility Notes

- This branch targets common Fabric modpack baselines, not the later
  unobfuscated `26.x` line.
- Shared transport code remains under `src/main/java` and has no direct
  `net.minecraft.*` or `net.fabricmc.*` imports.
- Minecraft/Fabric adapters are selected by `adapter_source_set`:
  - `src/fabric1201` for Minecraft `1.20.1`
  - `src/fabric1211` for Minecraft `1.21.1`
- `1.20.1` uses Fabric's raw channel networking API with `Identifier` and
  `PacketByteBuf`.
- `1.21.1` uses the modern `CustomPayload`, `PacketCodec`, and
  `PayloadTypeRegistry.playC2S/playS2C` API.

