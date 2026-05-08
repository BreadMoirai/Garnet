package com.breadmoirai.redstonespecs.managed

object DimIdSanitizer {
    private val ALLOWED = Regex("[a-z0-9_/.\\-]")

    /**
     * Sanitizes a folder subpath into the path component of a Minecraft `ResourceLocation`.
     * The base prefix is `managed`; full id is `redstonespecs:<this>`.
     * Rules: lowercase; chars outside `[a-z0-9_/.-]` become `_`. Empty → `managed`.
     */
    fun toPath(subpath: String): String {
        if (subpath.isEmpty()) return "managed"
        val sanitized = subpath.lowercase().map { c ->
            if (ALLOWED.matches(c.toString())) c else '_'
        }.joinToString("")
        return "managed/$sanitized"
    }
}
