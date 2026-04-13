package com.papi.nova.binding;

import android.content.Context;

import com.papi.nova.binding.audio.AndroidAudioRenderer;
import com.papi.nova.binding.crypto.AndroidCryptoProvider;
import com.papi.nova.nvstream.av.audio.AudioRenderer;
import com.papi.nova.nvstream.http.LimelightCryptoProvider;

public class PlatformBinding {
    public static LimelightCryptoProvider getCryptoProvider(Context c) {
        return new AndroidCryptoProvider(c);
    }
}
