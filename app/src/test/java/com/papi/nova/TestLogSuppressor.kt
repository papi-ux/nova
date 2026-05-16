package com.papi.nova

import java.io.PrintStream

object TestLogSuppressor {
    private var installed = false

    @JvmStatic
    @Synchronized
    fun install() {
        if (installed) {
            return
        }
        installed = true

        val originalErr = System.err
        System.setErr(
            object : PrintStream(originalErr, true) {
                private fun shouldSuppress(message: String?): Boolean {
                    return message?.contains("Invalid ID 0x00000000") == true
                }

                override fun println(x: String?) {
                    if (!shouldSuppress(x)) {
                        super.println(x)
                    }
                }

                override fun print(s: String?) {
                    if (!shouldSuppress(s)) {
                        super.print(s)
                    }
                }
            }
        )
    }
}
