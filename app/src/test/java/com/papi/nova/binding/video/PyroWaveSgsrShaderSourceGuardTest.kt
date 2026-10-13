package com.papi.nova.binding.video

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #304 as text: the default shaders untouched, sgsr1.frag held to the CPU model, the vendored SPIR-V held
 * to the source it names, and the native and JNI seams held to the shader's push block.
 *
 * There is no GPU in a JVM test, so this pins what the GPU will be given.
 */
class PyroWaveSgsrShaderSourceGuardTest {
    private val core = "src/main/jni/pyrowave-core"
    private fun text(path: String) = File(path).readText()
    private fun sha256(path: String) =
        MessageDigest.getInstance("SHA-256").digest(File(path).readBytes()).joinToString("") { "%02x".format(it) }

    @Test fun theDefaultShadersAndTheirSpirvAreByteIdenticalToTheBase() {
        val base = mapOf(
            "$core/shaders/present.frag" to "e5d5685e90a0cfdd5802138a92c2c542df1f718c1cdf509efa04c884b396cee0",
            "$core/shaders/present.vert" to "7634f6dc8d6fb71bef4ef9c83e8ebdeddc3385dc9284b5ef663a36f1d93c4df0",
            "$core/shaders/present_frag_spv.h" to "3f67729b7df7337dc4edc95b3dc11d17e0b63a78c512e0f0c3f606799d12a85c",
            "$core/shaders/present_vert_spv.h" to "379b6b69fac807198ccb688a8ee13295a6e01bbc3934a5aafa9569b215257786",
        )
        for ((path, hash) in base) assertEquals(path, hash, sha256(path))
    }

    @Test fun theDecoderAndTheCodecPinAreUntouched() {
        val renderer = text("$core/pyrowave_renderer.cpp")
        assertTrue("the compute decode's correctness-backed refusal of the fragment path moved", renderer.contains("info.fragment_path = false;"))
        assertEquals("cd pyrowave && git checkout 186f0393b77f7755953b5ecde994bb1cec2e4155", text("$core/README.md").lines()[11])
    }

    @Test fun theShaderUsesNovasColourAndCoordinatesNotTheReferences() {
        val shader = text("$core/shaders/sgsr1.frag")
        val code = shader.lines().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")
        assertFalse("128/255 is RubyLight's chroma centre; Nova's is 0.5", code.contains("128.0"))
        assertFalse("full range only: no limited range expansion", Regex("219|224|16\\.0 / 255|fullRange|full_range").containsMatchIn(code))
        assertFalse("no flip: present.vert's uv already has row 0 at the top", Regex("1\\.0 - [a-z_.]*\\.?y|planeCoord").containsMatchIn(code))
        assertFalse("fp32: no mediump without its own control", code.contains("mediump") || code.contains("precision"))
        assertTrue(code.contains("float cb = texture(chroma_b, uv).r - 0.5;"))
        assertTrue(code.contains("float cr = texture(chroma_r, uv).r - 0.5;"))
        // The present shader's arithmetic, on the pushed matrix in its order.
        assertTrue(code.contains("y + sgsr.matrix.x * cr,"))
        assertTrue(code.contains("y + sgsr.matrix.y * cb + sgsr.matrix.z * cr,"))
        assertTrue(code.contains("y + sgsr.matrix.w * cb);"))
        for ((binding, name) in listOf(0 to "luma", 1 to "chroma_b", 2 to "chroma_r")) {
            assertTrue(name, code.contains("layout(set = 0, binding = $binding) uniform sampler2D $name;"))
        }
        assertFalse("the shader carries its own name, never another upscaler's", Regex("\\bFSR\\b").containsMatchIn(shader))
    }

    @Test fun theShadersConstantsAndFootprintAreQualcommsAsTheModelHasThem() {
        val code = text("$core/shaders/sgsr1.frag")
        val m = PyroWaveSgsrReference.NOVA
        assertTrue(code.contains("vec2 img_coord = uv * sgsr.viewport_info.zw + vec2(${m.imgCoordOffsetX}, ${m.imgCoordOffsetY});"))
        assertTrue(code.contains("if (edge_vote > 8.0 / 255.0) {"))
        assertEquals(8.0 / 255.0, m.edgeThreshold, 0.0)
        assertTrue(code.contains("float edge_vote = abs(left.z - left.y) + abs(y - left.y) + abs(y - left.z);"))
        assertTrue(code.contains("float std_inv = 2.181818 / sum;"))
        assertEquals(2.181818, m.stdScale, 0.0)
        assertTrue(code.contains("float x = ((dx * dx) + (dy * dy)) * 0.55 + clamp(abs(c) * std_inv, 0.0, 1.0);"))
        assertEquals(0.55, m.distanceScale, 0.0)
        assertTrue(code.contains("delta_y = clamp(delta_y, -23.0 / 255.0, 23.0 / 255.0);"))
        assertEquals(23.0 / 255.0, m.deltaLimit, 0.0)
        assertTrue(code.contains("up_down.xy = textureGather(luma, coord + vec2(0.0, -sgsr.viewport_info.y), 0).wz;"))
        assertTrue(code.contains("up_down.zw = textureGather(luma, coord + vec2(0.0, sgsr.viewport_info.y), 0).yx;"))
        assertTrue(code.contains("vec4 right = textureGather(luma, coord + vec2(sgsr.viewport_info.x, 0.0), 0);"))
        assertTrue(code.contains("if (aWY.x > 0.0) {"))
        assertEquals(PyroWaveSgsrReference.WeightGuard.KEEP_BASE, m.weightGuard)
        assertTrue(code.contains("rgb = clamp(clamp(rgb, 0.0, 1.0) + delta_y, 0.0, 1.0);"))
        assertTrue(m.preClamp)

        // Twelve taps, in order, each the model's.
        val taps = Regex("""weight_y\(pl\.x( [+-] \d\.0)?, pl\.y( [+-] \d\.0)?, ([a-z_]+\.[xyzw]), std_inv\)""")
            .findAll(code).map { match ->
                val offset = { group: String -> if (group.isEmpty()) 0 else group.replace(" ", "").removeSuffix(".0").toInt() }
                PyroWaveSgsrReference.Tap(offset(match.groupValues[1]), offset(match.groupValues[2]), match.groupValues[3])
            }.toList()
        assertEquals(PyroWaveSgsrReference.TAPS, taps)
    }

    @Test fun thePushBlockIsTheNativeStructInOrder() {
        val code = text("$core/shaders/sgsr1.frag")
        val block = code.substringAfter("layout(push_constant) uniform Sgsr {").substringBefore("} sgsr;")
        val members = Regex("""^\s*(vec4|float) ([a-z_]+);""", RegexOption.MULTILINE).findAll(block).map { it.groupValues[1] to it.groupValues[2] }.toList()
        assertEquals(listOf("vec4" to "matrix", "vec4" to "viewport_info", "float" to "edge_sharpness"), members)

        val header = text("$core/pyrowave_renderer.h")
        val struct = header.substringAfter("struct sgsr_push_t {").substringBefore("};")
        assertEquals(
            listOf("colour_matrix_t matrix;", "float viewport_info[4];", "float edge_sharpness;"),
            struct.lines().map { it.trim() }.filter { it.isNotEmpty() },
        )
        assertTrue(header.contains("static constexpr float k_sgsr_edge_sharpness = 2.0f;"))
        val renderer = text("$core/pyrowave_renderer.cpp")
        for (line in listOf(
            "static_assert(sizeof(sgsr_push_t) == 36,",
            "static_assert(offsetof(sgsr_push_t, matrix) == 0,",
            "static_assert(offsetof(sgsr_push_t, viewport_info) == 16,",
            "static_assert(offsetof(sgsr_push_t, edge_sharpness) == 32,",
            "push.edge_sharpness = k_sgsr_edge_sharpness;",
            "push.viewport_info[0] = 1.0f / static_cast<float>(frame_width);",
            "push.viewport_info[3] = static_cast<float>(frame_height);",
            "push_range.size = sizeof(sgsr_push_t);",
        )) assertTrue(line, renderer.contains(line))
    }

    @Test fun theDefaultDrawIsTheOldDrawAndSgsrDrawsOnlyWhenActive() {
        val renderer = text("$core/pyrowave_renderer.cpp")
        val draw = renderer.substringAfter("void renderer_t::bind_and_draw(VkCommandBuffer cmd) {").substringBefore("\n  }\n")
        val default = draw.substringAfter("} else {").substringBefore("vk.vkCmdDraw(cmd, 3, 1, 0, 0);")
        for (line in listOf(
            "vk.vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline);",
            "const auto matrix = colour_matrix();",
            "vk.vkCmdPushConstants(cmd, pipeline_layout, VK_SHADER_STAGE_FRAGMENT_BIT, 0,",
            "sizeof(matrix), &matrix);",
            "vk.vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline_layout,",
        )) assertTrue(line, default.contains(line))
        assertTrue(draw.contains("if (scaler_state == PYROWAVE_SCALER_SGSR1_ACTIVE) {"))
        // The default pipeline is still made from the vendored present shaders, before anything SGSR.
        assertTrue(renderer.contains("VkShaderModule fragment = make_module(present_frag_spv, sizeof(present_frag_spv));"))
        assertTrue(renderer.contains("VkShaderModule fragment = make_module(sgsr1_frag_spv, sizeof(sgsr1_frag_spv));"))
        val create = renderer.substringAfter("bool renderer_t::create(").substringBefore("pyrowave_scaler_info renderer_t::scaler_info()")
        assertTrue("SGSR is chosen after everything a renderer needs exists", create.indexOf("choose_scaler(sgsr_request);") > create.indexOf("!create_decoder()"))
        // Destroyed before the device.
        val destroy = renderer.substringAfter("void renderer_t::destroy() {")
        assertTrue(destroy.indexOf("vk.vkDestroyPipeline(device.device, sgsr_pipeline, nullptr)") < destroy.indexOf("device.destroy();"))
        assertTrue(destroy.indexOf("vk.vkDestroyPipelineLayout(device.device, sgsr_layout, nullptr)") < destroy.indexOf("device.destroy();"))
    }

    @Test fun theVendoredSpirvIsTheCompiledSourceItNames() {
        val header = text("$core/shaders/sgsr1_frag_spv.h")
        val source = sha256("$core/shaders/sgsr1.frag")
        assertTrue("sgsr1_frag_spv.h was compiled from another sgsr1.frag: regenerate it", header.contains("// Source sha256: $source"))
        assertTrue(header.contains("// Compiler: glslc-2026.1-1.fc44 (/usr/bin/glslc sha256 85365a1f6e439c17cb9d71045d8306c018782359a466ae65c1806be4f7f0cd87;"))
        val words = Regex("""0x([0-9a-f]{8})u,""").findAll(header.substringAfter("sgsr1_frag_spv[] = {")).map { it.groupValues[1].toLong(16).toInt() }.toList()
        val bytes = ByteBuffer.allocate(words.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply { words.forEach { putInt(it) } }.array()
        val payload = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertTrue("the recorded SPIR-V sha256 is not this payload's", header.contains("// SPIR-V sha256: $payload (${words.size} words)"))
        assertEquals("SPIR-V magic", 0x07230203, words[0])
        assertEquals("SPIR-V 1.0", 0x00010000, words[1])
    }

    @Test fun jniAndKotlinAgreeOnEverySignature() {
        val jni = text("$core/pyrowave_jni.c")
        assertTrue(jni.contains("jboolean hdr, jint sgsrRequest) {"))
        assertTrue(jni.contains("Java_com_papi_nova_binding_video_PyroWave_nativeScalerState(JNIEnv *env, jclass clazz, jlong handle)"))
        assertTrue(jni.contains("Java_com_papi_nova_binding_video_PyroWaveSgsrSelfTest_nativeReadback("))
        assertEquals(2, Regex("""pyrowave_renderer_create\(window, Width, Height, false, false, PYROWAVE_SGSR_REQUEST_NONE\)""").findAll(jni).count())
        val kotlin = text("src/main/java/com/papi/nova/binding/video/PyroWave.kt")
        assertTrue(kotlin.contains("        hdr: Boolean,\n        sgsrRequest: Int,\n    ): Long"))
        assertTrue(kotlin.contains("private external fun nativeScalerState(handle: Long): IntArray?"))
        val debug = text("src/debug/java/com/papi/nova/binding/video/PyroWaveSgsrSelfTest.kt")
        assertTrue(debug.contains("private external fun nativeReadback("))
        assertFalse("the readback self test is debug only", File("src/main/java/com/papi/nova/binding/video/PyroWaveSgsrSelfTest.kt").exists())
    }

    @Test fun attributionTravelsWithTheSourceAndTheApk() {
        val licence = text("$core/shaders/LICENSE-SGSR1")
        assertEquals(licence, text("src/main/assets/licenses/NOTICE-SGSR1.txt"))
        for (line in listOf(
            "SnapdragonGameStudios/snapdragon-gsr/blob/d926f074bcb9d714e179f1ce0fcb9ee2eeb5074e/sgsr/v1/include/glsl/sgsr1_shader_mobile.frag",
            "Shader sha256: 93e8db987dc79f62e68805f968d63989e5d50daebe4d040e4c7104047ad49139",
            "Copyright (c) 2025, Qualcomm Innovation Center, Inc. All rights reserved.",
            "Copyright (c) 2023, Qualcomm Innovation Center, Inc. All rights reserved.",
            "sha256 bc496633d3db8c6ca1362216ec382d361b69c7d5518acd6bbfd91503febfb3ef",
            "RamazanKara/rubylight-android/blob/2293aa2705b42040780b97e4052739be1822043d",
            "Redistributions in binary form must reproduce the above copyright notice,",
            "SPDX-License-Identifier: BSD-3-Clause",
        )) assertTrue(line, licence.contains(line))
        val shader = text("$core/shaders/sgsr1.frag")
        assertTrue(shader.contains("// Copyright (c) 2025, Qualcomm Innovation Center, Inc. All rights reserved."))
        assertTrue(shader.contains("// SPDX-License-Identifier: BSD-3-Clause"))
        assertTrue(shader.contains("RamazanKara/rubylight-android@2293aa2705b42040780b97e4052739be1822043d"))
    }

    @Test fun theSettingIsNamedSgsr1AndHiddenWithoutExperimentalCodecs() {
        val strings = text("src/main/res/values/strings.xml")
        val title = Regex("""<string name="title_pyrowave_sgsr1">([^<]*)</string>""").find(strings)!!.groupValues[1]
        val summary = Regex("""<string name="summary_pyrowave_sgsr1">([^<]*)</string>""").find(strings)!!.groupValues[1]
        assertEquals("SGSR 1 upscaling (experimental, PyroWave SDR)", title)
        assertFalse(Regex("\\bFSR\\b|temporal").containsMatchIn(title + summary))
        assertTrue(summary.contains("smaller than the screen"))
        val availability = text("src/main/java/com/papi/nova/preferences/NovaSettingsAvailability.kt")
        assertTrue(availability.contains("if (!BuildConfig.EXPERIMENTAL_CODECS) {\n            keys += PreferenceConfiguration.PYROWAVE_SGSR1_PREF_STRING"))
        val preferences = text("src/main/java/com/papi/nova/preferences/PreferenceConfiguration.kt")
        assertTrue(preferences.contains("config.pyrowaveSgsr1 = BuildConfig.EXPERIMENTAL_CODECS &&"))
        assertTrue(preferences.contains("const val DEFAULT_PYROWAVE_SGSR1 = false"))
    }
}
