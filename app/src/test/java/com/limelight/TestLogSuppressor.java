package com.limelight;

import java.io.PrintStream;

public final class TestLogSuppressor {
    private TestLogSuppressor() {}

    private static boolean installed;

    public static synchronized void install() {
        if (installed) {
            return;
        }
        installed = true;

        final PrintStream originalErr = System.err;
        System.setErr(new PrintStream(originalErr, true) {
            private boolean shouldSuppress(String msg) {
                return msg != null && msg.contains("Invalid ID 0x00000000");
            }

            @Override
            public void println(String x) {
                if (!shouldSuppress(x)) {
                    super.println(x);
                }
            }

            @Override
            public void print(String s) {
                if (!shouldSuppress(s)) {
                    super.print(s);
                }
            }
        });
    }
}