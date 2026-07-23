package com.breadmoirai.redstonespecs.project

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.nio.file.Path
import kotlin.io.path.createParentDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Client-side persistent list of managed-spec root paths. Stored as a tiny JSON array under
 * the user's MC config dir. Read at startup; written when the user adds/removes a root in the
 * world-list "Project Specs..." screen.
 */
object ProjectRootsConfig {
    private val GSON = Gson()
    private val LIST_TYPE = object : TypeToken<List<String>>() {}.type

    fun load(path: Path): List<String> {
        if (!path.exists()) return emptyList()
        return GSON.fromJson<List<String>>(path.readText(), LIST_TYPE) ?: emptyList()
    }

    fun save(path: Path, roots: List<String>) {
        path.createParentDirectories()
        path.writeText(GSON.toJson(roots))
    }
}
