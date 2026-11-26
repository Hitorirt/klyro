package gg.klyro.mapper.adb

import android.content.Context
import android.os.Build
import android.sun.misc.BASE64Encoder
import android.sun.security.provider.X509Factory
import android.sun.security.x509.AlgorithmId
import android.sun.security.x509.CertificateAlgorithmId
import android.sun.security.x509.CertificateExtensions
import android.sun.security.x509.CertificateIssuerName
import android.sun.security.x509.CertificateSerialNumber
import android.sun.security.x509.CertificateSubjectName
import android.sun.security.x509.CertificateValidity
import android.sun.security.x509.CertificateVersion
import android.sun.security.x509.CertificateX509Key
import android.sun.security.x509.KeyIdentifier
import android.sun.security.x509.PrivateKeyUsageExtension
import android.sun.security.x509.SubjectKeyIdentifierExtension
import android.sun.security.x509.X500Name
import android.sun.security.x509.X509CertImpl
import android.sun.security.x509.X509CertInfo
import gg.klyro.mapper.R
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import java.io.File
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import kotlin.random.Random

/**
 * Identidade ADB do app (chave RSA + certificado X.509), guardada nos arquivos internos.
 * Na primeira execução gera o par de chaves; depois reaproveita, para não precisar parear de novo.
 */
class AdbConnectionManager private constructor(private val context: Context) : AbsAdbConnectionManager() {

    private var privKey: PrivateKey
    private var cert: Certificate

    init {
        api = Build.VERSION.SDK_INT
        val savedKey = readPrivateKey()
        val savedCert = readCertificate()
        if (savedKey != null && savedCert != null) {
            privKey = savedKey
            cert = savedCert
        } else {
            val generator = KeyPairGenerator.getInstance("RSA")
            generator.initialize(2048, SecureRandom())
            val pair = generator.generateKeyPair()
            privKey = pair.private
            cert = createCertificate(pair.public, pair.private)
            writePrivateKey(privKey)
            writeCertificate(cert)
        }
    }

    override fun getDeviceName(): String = context.getString(R.string.app_name)

    override fun getCertificate(): Certificate = cert

    override fun getPrivateKey(): PrivateKey = privKey

    private val certFile get() = File(context.filesDir, "cert.pem")
    private val keyFile get() = File(context.filesDir, "priv.key")

    private fun readCertificate(): Certificate? {
        if (!certFile.exists()) return null
        return certFile.inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
    }

    private fun readPrivateKey(): PrivateKey? {
        if (!keyFile.exists()) return null
        val bytes = keyFile.readBytes()
        return KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(bytes))
    }

    private fun writePrivateKey(key: PrivateKey) = keyFile.writeBytes(key.encoded)

    private fun createCertificate(pubKey: PublicKey, privKey: PrivateKey): Certificate {
        val subject = "CN=" + context.getString(R.string.app_name)
        val algorithm = "SHA512withRSA"
        val notBefore = Date()
        val notAfter = Date(System.currentTimeMillis() + CERT_VALIDITY_MS)
        val extensions = CertificateExtensions().apply {
            set("SubjectKeyIdentifier", SubjectKeyIdentifierExtension(KeyIdentifier(pubKey).identifier))
            set("PrivateKeyUsage", PrivateKeyUsageExtension(notBefore, notAfter))
        }
        val name = X500Name(subject)
        val info = X509CertInfo().apply {
            set("version", CertificateVersion(2))
            set("serialNumber", CertificateSerialNumber(Random.nextInt() and Int.MAX_VALUE))
            set("algorithmID", CertificateAlgorithmId(AlgorithmId.get(algorithm)))
            set("subject", CertificateSubjectName(name))
            set("key", CertificateX509Key(pubKey))
            set("validity", CertificateValidity(notBefore, notAfter))
            set("issuer", CertificateIssuerName(name))
            set("extensions", extensions)
        }
        return X509CertImpl(info).apply { sign(privKey, algorithm) }
    }

    private fun writeCertificate(certificate: Certificate) {
        certFile.outputStream().use { os ->
            os.write(X509Factory.BEGIN_CERT.toByteArray())
            os.write('\n'.code)
            BASE64Encoder().encode(certificate.encoded, os)
            os.write('\n'.code)
            os.write(X509Factory.END_CERT.toByteArray())
        }
    }

    companion object {
        // Uma validade longa evita ter que parear de novo.
        private const val CERT_VALIDITY_MS = 10L * 365 * 24 * 60 * 60 * 1000

        @Volatile
        private var instance: AdbConnectionManager? = null

        fun get(context: Context): AdbConnectionManager =
            instance ?: synchronized(this) {
                instance ?: AdbConnectionManager(context.applicationContext).also { instance = it }
            }
    }
}
