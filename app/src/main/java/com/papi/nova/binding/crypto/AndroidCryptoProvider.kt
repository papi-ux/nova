package com.papi.nova.binding.crypto

import android.annotation.SuppressLint
import android.content.Context
import android.util.Base64
import com.papi.nova.LimeLog
import com.papi.nova.nvstream.http.LimelightCryptoProvider
import org.bouncycastle.asn1.x500.X500NameBuilder
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.io.StringWriter
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.NoSuchAlgorithmException
import java.security.PrivateKey
import java.security.Provider
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.InvalidKeySpecException
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Calendar
import java.util.Date
import java.util.Locale

class AndroidCryptoProvider(c: Context) : LimelightCryptoProvider {
    private val certFile: File
    private val keyFile: File

    private var cert: X509Certificate? = null
    private var key: PrivateKey? = null
    private var pemCertBytes: ByteArray? = null

    init {
        val dataPath = c.filesDir.absolutePath
        certFile = File(dataPath + File.separator + "client.crt")
        keyFile = File(dataPath + File.separator + "client.key")
    }

    private fun loadFileToBytes(file: File): ByteArray? {
        if (!file.exists()) {
            return null
        }

        return try {
            FileInputStream(file).use { input ->
                val fileData = ByteArray(file.length().toInt())
                if (input.read(fileData) != file.length().toInt()) {
                    null
                } else {
                    fileData
                }
            }
        } catch (_: IOException) {
            null
        }
    }

    private fun loadCertKeyPair(): Boolean {
        val certBytes = loadFileToBytes(certFile)
        val keyBytes = loadFileToBytes(keyFile)
        if (certBytes == null || keyBytes == null) {
            LimeLog.info("Missing cert or key; need to generate a new one")
            return false
        }

        try {
            val certFactory = CertificateFactory.getInstance("X.509", bcProvider)
            cert = certFactory.generateCertificate(ByteArrayInputStream(certBytes)) as X509Certificate
            pemCertBytes = certBytes
            val keyFactory = KeyFactory.getInstance("RSA", bcProvider)
            key = keyFactory.generatePrivate(PKCS8EncodedKeySpec(keyBytes))
        } catch (_: CertificateException) {
            LimeLog.warning("Corrupted certificate")
            return false
        } catch (e: NoSuchAlgorithmException) {
            throw RuntimeException(e)
        } catch (_: InvalidKeySpecException) {
            LimeLog.warning("Corrupted key")
            return false
        }

        return true
    }

    @SuppressLint("TrulyRandom")
    private fun generateCertKeyPair(): Boolean {
        val serialBytes = ByteArray(8)
        SecureRandom().nextBytes(serialBytes)

        val keyPair = try {
            val keyPairGenerator = KeyPairGenerator.getInstance("RSA", bcProvider)
            keyPairGenerator.initialize(2048)
            keyPairGenerator.generateKeyPair()
        } catch (e: NoSuchAlgorithmException) {
            throw RuntimeException(e)
        }

        val now = Date()
        val calendar = Calendar.getInstance()
        calendar.time = now
        calendar.add(Calendar.YEAR, 20)
        val expirationDate = calendar.time
        val serial = BigInteger(serialBytes).abs()

        val name = X500NameBuilder(BCStyle.INSTANCE)
            .addRDN(BCStyle.CN, "NVIDIA GameStream Client")
            .build()

        val certBuilder = X509v3CertificateBuilder(
            name,
            serial,
            now,
            expirationDate,
            Locale.ENGLISH,
            name,
            SubjectPublicKeyInfo.getInstance(keyPair.public.encoded)
        )

        try {
            val signer = JcaContentSignerBuilder("SHA256withRSA")
                .setProvider(bcProvider)
                .build(keyPair.private)
            cert = JcaX509CertificateConverter()
                .setProvider(bcProvider)
                .getCertificate(certBuilder.build(signer))
            key = keyPair.private
        } catch (e: Exception) {
            throw RuntimeException(e)
        }

        LimeLog.info("Generated a new key pair")
        saveCertKeyPair()
        return true
    }

    private fun saveCertKeyPair() {
        try {
            FileOutputStream(certFile).use { certOut ->
                FileOutputStream(keyFile).use { keyOut ->
                    val stringWriter = StringWriter()
                    JcaPEMWriter(stringWriter).use { pemWriter ->
                        pemWriter.writeObject(cert)
                    }

                    OutputStreamWriter(certOut).use { certWriter ->
                        val pemString = stringWriter.buffer.toString()
                        for (char in pemString) {
                            if (char != '\r') {
                                certWriter.append(char)
                            }
                        }
                    }

                    keyOut.write(key!!.encoded)
                    LimeLog.info("Saved generated key pair to disk")
                }
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    override val clientCertificate: X509Certificate
        get() = synchronized(globalCryptoLock) {
            cert?.let { return@synchronized it }
            if (loadCertKeyPair()) {
                return@synchronized cert ?: error("Certificate failed to load")
            }
            if (!generateCertKeyPair()) {
                error("Certificate generation failed")
            }
            loadCertKeyPair()
            cert ?: error("Certificate failed to load")
        }

    override val clientPrivateKey: PrivateKey
        get() = synchronized(globalCryptoLock) {
            key?.let { return@synchronized it }
            if (loadCertKeyPair()) {
                return@synchronized key ?: error("Private key failed to load")
            }
            if (!generateCertKeyPair()) {
                error("Private key generation failed")
            }
            loadCertKeyPair()
            key ?: error("Private key failed to load")
        }

    override val pemEncodedClientCertificate: ByteArray
        get() = synchronized(globalCryptoLock) {
            clientCertificate
            pemCertBytes ?: error("PEM certificate failed to load")
        }

    override fun encodeBase64String(data: ByteArray): String = Base64.encodeToString(data, Base64.NO_WRAP)

    companion object {
        private val globalCryptoLock = Any()
        private val bcProvider: Provider = BouncyCastleProvider()
    }
}
