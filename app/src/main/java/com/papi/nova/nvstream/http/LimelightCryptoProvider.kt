package com.papi.nova.nvstream.http

import java.security.PrivateKey
import java.security.cert.X509Certificate

interface LimelightCryptoProvider {
    val clientCertificate: X509Certificate

    val clientPrivateKey: PrivateKey

    val pemEncodedClientCertificate: ByteArray

    fun encodeBase64String(data: ByteArray): String
}
