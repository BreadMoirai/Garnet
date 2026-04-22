package com.breadmoirai.redstonespecs.config

enum class DevLevel { STANDARD, ADVANCED }

/** Shared mutable settings readable by both client and server-side code in the same JVM. */
object SharedSettings {
    var devLevel: DevLevel = DevLevel.STANDARD
}
