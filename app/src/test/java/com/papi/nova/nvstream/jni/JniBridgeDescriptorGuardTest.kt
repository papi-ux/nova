package com.papi.nova.nvstream.jni

import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The native side finds every MoonBridge callback by name and JVM descriptor, as a string in
 * callbacks.c. Nothing compiles that string against the Kotlin signature: change one side alone
 * and the build stays green, GetStaticMethodID returns NULL, and the first frame crashes. This
 * reads the strings and checks each against the method it must resolve to.
 *
 * Reflection only; MoonBridge is never initialised, so its native library is never loaded.
 */
class JniBridgeDescriptorGuardTest {
    private val callbacks: String
        get() = File("src/main/jni/moonlight-core/callbacks.c").readText()

    private fun resolvedOnMoonBridge(): Map<String, String> {
        val init = callbacks
            .substringAfter("Java_com_papi_nova_nvstream_jni_MoonBridge_init(")
            .substringBefore("// Nova: resolve the pre-termination hook")
        val call = Regex("""GetStaticMethodID\(env, clazz, "([A-Za-z0-9_]+)", "([^"]+)"\)""")
        return call.findAll(init).associate { it.groupValues[1] to it.groupValues[2] }
    }

    private fun descriptorOf(type: Class<*>): String = when {
        type == Void.TYPE -> "V"
        type == java.lang.Boolean.TYPE -> "Z"
        type == java.lang.Byte.TYPE -> "B"
        type == java.lang.Character.TYPE -> "C"
        type == java.lang.Short.TYPE -> "S"
        type == java.lang.Integer.TYPE -> "I"
        type == java.lang.Long.TYPE -> "J"
        type == java.lang.Float.TYPE -> "F"
        type == java.lang.Double.TYPE -> "D"
        type.isArray -> "[" + descriptorOf(type.componentType!!)
        else -> "L" + type.name.replace('.', '/') + ";"
    }

    private fun descriptorOf(method: Method): String =
        method.parameterTypes.joinToString(separator = "", prefix = "(", postfix = ")") { descriptorOf(it) } +
            descriptorOf(method.returnType)

    @Test
    fun submitDecodeUnitCarriesTheRawRtpTimestampAsAThirdLong() {
        assertEquals("([BIIIICJJJ)I", resolvedOnMoonBridge()["bridgeDrSubmitDecodeUnit"])
    }

    @Test
    fun everyCallbackTheNativeSideResolvesHasThatExactKotlinSignature() {
        val resolved = resolvedOnMoonBridge()
        assertTrue("callbacks.c resolves no MoonBridge methods; the parser has gone stale", resolved.size >= 20)

        val statics = MoonBridge::class.java.declaredMethods
            .filter { Modifier.isStatic(it.modifiers) }
            .groupBy { it.name }
        for ((name, descriptor) in resolved) {
            val candidates = statics[name]
            assertNotNull("callbacks.c resolves $name, which MoonBridge has no static method for", candidates)
            val actual = candidates!!.map { descriptorOf(it) }
            assertTrue(
                "callbacks.c resolves $name as $descriptor but MoonBridge declares $actual",
                descriptor in actual,
            )
        }
    }
}
