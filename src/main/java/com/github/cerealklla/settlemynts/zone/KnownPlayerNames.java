package com.github.cerealklla.settlemynts.zone;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonParseException;

import net.neoforged.fml.loading.FMLPaths;

/**
 * Every player name this server's own vanilla name-cache has ever seen -- read directly from
 * {@code usercache.json} (the exact file vanilla's {@code CachedUserNameToIdResolver}, reachable via
 * {@code MinecraftServer#services()#nameToIdCache()}, persists to -- confirmed against the real
 * decompiled source before relying on this, since that interface itself exposes no public
 * enumeration method, only {@code get(name)}/{@code get(uuid)}). Added 2026-11-17 purely for the
 * Plot Config Sign's "Change Owner" screen type-ahead -- real owner *resolution* still goes through
 * the official {@code nameToIdCache().get(name)} API directly (which can resolve a real Mojang
 * account even if it's never logged into this server before), this list is only ever used to drive
 * suggestions as a Town Planner types.
 *
 * <p>Same {@code FMLPaths.GAMEDIR}-relative convention {@code blueprint.BlueprintStorage} already
 * uses for a game-dir file outside any world save. Never throws -- a missing/corrupt file just means
 * no suggestions, not a broken screen.
 */
public final class KnownPlayerNames {

    private KnownPlayerNames() {
    }

    public static List<String> all() {
        Path file = FMLPaths.GAMEDIR.get().resolve("usercache.json");
        if (!Files.exists(file)) {
            return List.of();
        }
        try {
            JsonArray entries = new Gson().fromJson(Files.readString(file, StandardCharsets.UTF_8), JsonArray.class);
            if (entries == null) {
                return List.of();
            }
            List<String> names = new ArrayList<>();
            for (var element : entries) {
                if (element.isJsonObject() && element.getAsJsonObject().has("name")) {
                    names.add(element.getAsJsonObject().get("name").getAsString());
                }
            }
            names.sort(String.CASE_INSENSITIVE_ORDER);
            return names;
        } catch (IOException | JsonParseException e) {
            return List.of();
        }
    }
}
