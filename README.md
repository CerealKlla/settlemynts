# Settlemynts

Settlement-management mod for a modular Minecraft project (NeoForge, Minecraft 26.1.2). Part of a suite of intercommunicating mods that expose public APIs so other mods — the user's own and third parties' — can integrate.

See [CLAUDE.md](CLAUDE.md) and [context/design-document.md](context/design-document.md) for the full design spec, architecture, and public API surface before making changes here.

## Development setup

Requires JDK 25 (standalone, not an IDE-bundled runtime) on `JAVA_HOME`.

If you're missing libraries in your IDE, or run into dependency problems, run `gradlew --refresh-dependencies` to refresh the local cache, or `gradlew clean` to reset the build (does not affect source code).

## Mapping names

By default, this project uses Mojang's official mapping names for methods and fields in the Minecraft codebase. These names are covered by a specific license — see https://github.com/NeoForged/NeoForm/blob/main/Mojang.md.

## Resources

- NeoForged docs: https://docs.neoforged.net/
- NeoForged Discord: https://discord.neoforged.net/
