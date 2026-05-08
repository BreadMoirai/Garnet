package com.breadmoirai.redstonespecs.data.serial

import com.breadmoirai.redstonespecs.data.RedstoneSpec
import io.kotest.core.spec.Spec
import kotlin.reflect.KClass

/**
 * Captures the [RedstoneSpec] literal from a `.spec.kts` class during instantiation.
 *
 * The emitted Spec class calls [record] from inside its FunSpec init body, before any
 * `test(...)` block runs. [captureFrom] then instantiates the class to fire the record
 * call without running test bodies, and returns the captured value.
 */
object SpecLiteralCapture {
    private val capture = ThreadLocal<RedstoneSpec?>()

    fun captureFrom(klass: KClass<out Spec>): RedstoneSpec? {
        capture.set(null)
        return try {
            klass.java.getDeclaredConstructor().newInstance()
            capture.get()
        } finally {
            capture.remove()
        }
    }

    fun record(spec: RedstoneSpec) {
        if (capture.get() == null) capture.set(spec)
    }
}
