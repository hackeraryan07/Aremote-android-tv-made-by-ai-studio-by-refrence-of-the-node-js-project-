import java.security.*
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.math.BigInteger
import java.util.Date
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

fun main() {
    val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
    keyPairGenerator.initialize(2048)
    val keyPair = keyPairGenerator.generateKeyPair()

    val certBuilder = JcaX509v3CertificateBuilder(
        X500Name("CN=AndroidTVRemote, O=Google, L=MTV, ST=CA, C=US"),
        BigInteger.valueOf(System.currentTimeMillis()),
        Date(System.currentTimeMillis() - 1000L * 60 * 60 * 24 * 30),
        Date(System.currentTimeMillis() + 1000L * 60 * 60 * 24 * 365 * 10),
        X500Name("CN=AndroidTVRemote, O=Google, L=MTV, ST=CA, C=US"),
        keyPair.public
    )
    val signer = JcaContentSignerBuilder("SHA256WithRSAEncryption").build(keyPair.private)
    val clientCert = JcaX509CertificateConverter().getCertificate(certBuilder.build(signer))

    val privateKeyStr = Base64.getEncoder().encodeToString(keyPair.private.encoded)
    val certStr = Base64.getEncoder().encodeToString(clientCert.encoded)

    try {
        val kf = KeyFactory.getInstance("RSA")
        val privateKey2 = kf.generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(privateKeyStr)))
        
        val cf = CertificateFactory.getInstance("X.509")
        val clientCert2 = cf.generateCertificate(ByteArrayInputStream(Base64.getDecoder().decode(certStr))) as X509Certificate
        println("Success!")
    } catch (e: Exception) {
        e.printStackTrace()
    }
}
