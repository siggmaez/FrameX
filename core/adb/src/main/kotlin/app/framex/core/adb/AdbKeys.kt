package app.framex.core.adb

import android.content.Context
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
import java.io.ByteArrayInputStream
import java.io.File
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.Random

/** This phone's own identity for wireless debugging. Created once, kept in FrameX's private storage, never shared. */
class AdbKeys(context: Context) {
    val privateKey: PrivateKey
    val certificate: Certificate

    init {
        val keyFile = File(context.filesDir, "adb_key.pk8")
        val certFile = File(context.filesDir, "adb_cert.der")
        val loaded = runCatching {
            val k = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
            val c = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(certFile.readBytes()))
            k to c
        }.getOrNull()
        val pair = loaded ?: generate().also { (k, c) ->
            keyFile.writeBytes(k.encoded)
            certFile.writeBytes(c.encoded)
        }
        privateKey = pair.first
        certificate = pair.second
    }

    private fun generate(): Pair<PrivateKey, Certificate> {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048, SecureRandom())
        val kp = kpg.generateKeyPair()
        val algorithm = "SHA512withRSA"
        val notBefore = Date()
        val notAfter = Date(System.currentTimeMillis() + 10L * 365 * 24 * 3600 * 1000)
        val ext = CertificateExtensions()
        ext.set("SubjectKeyIdentifier", SubjectKeyIdentifierExtension(KeyIdentifier(kp.public).identifier))
        ext.set("PrivateKeyUsage", PrivateKeyUsageExtension(notBefore, notAfter))
        val name = X500Name("CN=FrameX")
        val info = X509CertInfo()
        info.set("version", CertificateVersion(2))
        info.set("serialNumber", CertificateSerialNumber(Random().nextInt() and Int.MAX_VALUE))
        info.set("algorithmID", CertificateAlgorithmId(AlgorithmId.get(algorithm)))
        info.set("subject", CertificateSubjectName(name))
        info.set("key", CertificateX509Key(kp.public))
        info.set("validity", CertificateValidity(notBefore, notAfter))
        info.set("issuer", CertificateIssuerName(name))
        info.set("extensions", ext)
        val cert = X509CertImpl(info)
        cert.sign(kp.private, algorithm)
        return kp.private to cert
    }
}
