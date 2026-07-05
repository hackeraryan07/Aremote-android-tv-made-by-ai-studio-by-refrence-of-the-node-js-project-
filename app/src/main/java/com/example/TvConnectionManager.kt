package com.example

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.KeyStore
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.SSLSocket
import java.security.cert.X509Certificate
import java.security.SecureRandom
import pairing.Pairingmessage.PairingMessage
import pairing.Pairingmessage.PairingRequest
import pairing.Pairingmessage.RoleType
import pairing.Pairingmessage.PairingOption
import pairing.Pairingmessage.PairingEncoding
import pairing.Pairingmessage.PairingConfiguration
import pairing.Pairingmessage.PairingSecret
import remote.Remotemessage.RemoteMessage
import remote.Remotemessage.RemoteConfigure
import remote.Remotemessage.RemoteDeviceInfo
import remote.Remotemessage.RemoteSetActive
import remote.Remotemessage.RemoteKeyInject
import remote.Remotemessage.RemoteDirection
import remote.Remotemessage.RemoteKeyCode
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.util.Date
import javax.net.ssl.KeyManagerFactory

class TvConnectionManager(val host: String, val context: Context) {
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    enum class ConnectionState {
        DISCONNECTED,
        PAIRING,
        CONNECTED,
        ERROR
    }

    private var pairingSocket: SSLSocket? = null
    private var remoteSocket: SSLSocket? = null
    
    private var sslContext: SSLContext? = null
    private var clientCert: X509Certificate? = null
    private var serverCert: X509Certificate? = null

    private fun generateCertificate(): SSLContext {
        if (sslContext != null) return sslContext!!
        
        val prefs = context.getSharedPreferences("tv_cert_prefs", Context.MODE_PRIVATE)
        val privateKeyStr = prefs.getString("private_key", null)
        val certStr = prefs.getString("client_cert", null)

        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
        keyStore.load(null, null)

        if (privateKeyStr != null && certStr != null) {
            try {
                val kf = KeyFactory.getInstance("RSA")
                val privateKey = kf.generatePrivate(PKCS8EncodedKeySpec(Base64.decode(privateKeyStr, Base64.DEFAULT)))
                
                val cf = CertificateFactory.getInstance("X.509")
                clientCert = cf.generateCertificate(ByteArrayInputStream(Base64.decode(certStr, Base64.DEFAULT))) as X509Certificate
                
                keyStore.setKeyEntry("key", privateKey, "password".toCharArray(), arrayOf(clientCert))
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        if (clientCert == null) {
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
            clientCert = JcaX509CertificateConverter().getCertificate(certBuilder.build(signer))
            
            prefs.edit()
                .putString("private_key", Base64.encodeToString(keyPair.private.encoded, Base64.DEFAULT))
                .putString("client_cert", Base64.encodeToString(clientCert!!.encoded, Base64.DEFAULT))
                .apply()
                
            keyStore.setKeyEntry("key", keyPair.private, "password".toCharArray(), arrayOf(clientCert))
        }

        val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        keyManagerFactory.init(keyStore, "password".toCharArray())

        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            override fun checkClientTrusted(certs: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(certs: Array<X509Certificate>, authType: String) {
                if (certs.isNotEmpty()) {
                    serverCert = certs[0]
                }
            }
        })

        sslContext = SSLContext.getInstance("TLS")
        sslContext!!.init(keyManagerFactory.keyManagers, trustAllCerts, SecureRandom())
        return sslContext!!
    }

    suspend fun startPairing() = withContext(Dispatchers.IO) {
        try {
            _connectionState.value = ConnectionState.PAIRING
            val context = generateCertificate()
            pairingSocket = context.socketFactory.createSocket(host, 6467) as SSLSocket
            pairingSocket!!.startHandshake()

            val out = pairingSocket!!.outputStream
            val msg = PairingMessage.newBuilder()
                .setProtocolVersion(2)
                .setStatus(PairingMessage.Status.STATUS_OK)
                .setPairingRequest(PairingRequest.newBuilder()
                    .setServiceName("AndroidRemote")
                    .setClientName("AndroidRemote")
                    .build())
                .build()
            msg.writeDelimitedTo(out)

            val input = pairingSocket!!.inputStream
            var response = PairingMessage.parseDelimitedFrom(input)
            
            val optionMsg = PairingMessage.newBuilder()
                .setProtocolVersion(2)
                .setStatus(PairingMessage.Status.STATUS_OK)
                .setPairingOption(PairingOption.newBuilder()
                    .setPreferredRole(RoleType.ROLE_TYPE_INPUT)
                    .addInputEncodings(PairingEncoding.newBuilder()
                        .setType(PairingEncoding.EncodingType.ENCODING_TYPE_HEXADECIMAL)
                        .setSymbolLength(6)
                        .build())
                    .build())
                .build()
            optionMsg.writeDelimitedTo(out)

            response = PairingMessage.parseDelimitedFrom(input)
            
            val configMsg = PairingMessage.newBuilder()
                .setProtocolVersion(2)
                .setStatus(PairingMessage.Status.STATUS_OK)
                .setPairingConfiguration(PairingConfiguration.newBuilder()
                    .setClientRole(RoleType.ROLE_TYPE_INPUT)
                    .setEncoding(PairingEncoding.newBuilder()
                        .setType(PairingEncoding.EncodingType.ENCODING_TYPE_HEXADECIMAL)
                        .setSymbolLength(6)
                        .build())
                    .build())
                .build()
            configMsg.writeDelimitedTo(out)

            response = PairingMessage.parseDelimitedFrom(input)
            // Wait for secret
        } catch (e: Exception) {
            e.printStackTrace()
            _connectionState.value = ConnectionState.ERROR
        }
    }

    private fun hexStringToBytes(s: String): ByteArray {
        val len = s.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(s[i], 16) shl 4) + Character.digit(s[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    private fun getRsaModulusAndExponent(cert: X509Certificate): Pair<ByteArray, ByteArray> {
        val pubKey = cert.publicKey as java.security.interfaces.RSAPublicKey
        var mod = pubKey.modulus.toByteArray()
        if (mod.size > 256 && mod[0] == 0.toByte()) {
            mod = mod.copyOfRange(1, mod.size)
        }
        var exp = pubKey.publicExponent.toByteArray()
        if (exp.size > 3 && exp[0] == 0.toByte()) {
            exp = exp.copyOfRange(1, exp.size)
        }
        return Pair(mod, exp)
    }

    suspend fun sendPairingCode(code: String) = withContext(Dispatchers.IO) {
        try {
            val codeBytes = hexStringToBytes(code)
            
            val (clientMod, clientExp) = getRsaModulusAndExponent(clientCert!!)
            val (serverMod, serverExp) = getRsaModulusAndExponent(serverCert!!)
            
            val md = MessageDigest.getInstance("SHA-256")
            md.update(clientMod)
            md.update(clientExp)
            md.update(serverMod)
            md.update(serverExp)
            md.update(hexStringToBytes(code.substring(2)))
            val hash = md.digest()

            if (hash[0] != codeBytes[0]) {
                pairingSocket?.close()
                _connectionState.value = ConnectionState.ERROR
                return@withContext
            }

            val secretMsg = PairingMessage.newBuilder()
                .setProtocolVersion(2)
                .setStatus(PairingMessage.Status.STATUS_OK)
                .setPairingSecret(PairingSecret.newBuilder()
                    .setSecret(com.google.protobuf.ByteString.copyFrom(hash))
                    .build())
                .build()
            secretMsg.writeDelimitedTo(pairingSocket!!.outputStream)
            
            val input = pairingSocket!!.inputStream
            val ack = PairingMessage.parseDelimitedFrom(input)
            pairingSocket?.close()
            
            _connectionState.value = ConnectionState.CONNECTED
        } catch (e: Exception) {
            e.printStackTrace()
            _connectionState.value = ConnectionState.ERROR
        }
    }

    suspend fun connectRemote() = withContext(Dispatchers.IO) {
        try {
            val context = generateCertificate()
            remoteSocket = context.socketFactory.createSocket(host, 6466) as SSLSocket
            remoteSocket!!.startHandshake()
            
            val out = remoteSocket!!.outputStream
            val configure = RemoteMessage.newBuilder()
                .setRemoteConfigure(RemoteConfigure.newBuilder()
                    .setCode1(622)
                    .setDeviceInfo(RemoteDeviceInfo.newBuilder()
                        .setModel("AndroidRemote")
                        .setVendor("Google")
                        .setUnknown1(1)
                        .setUnknown2("1")
                        .setPackageName("com.example")
                        .setAppVersion("1.0")
                        .build())
                    .build())
                .build()
            
            configure.writeDelimitedTo(out)
            
            val active = RemoteMessage.newBuilder()
                .setRemoteSetActive(RemoteSetActive.newBuilder().setActive(622).build())
                .build()
            active.writeDelimitedTo(out)
            
            _connectionState.value = ConnectionState.CONNECTED
        } catch (e: Exception) {
            e.printStackTrace()
            _connectionState.value = ConnectionState.ERROR
            throw e
        }
    }

    fun disconnect() {
        try {
            pairingSocket?.close()
            remoteSocket?.close()
            _connectionState.value = ConnectionState.DISCONNECTED
        } catch (e: Exception) { e.printStackTrace() }
    }

    fun sendKey(keyCode: Int, direction: Int = 1) {
        if (_connectionState.value != ConnectionState.CONNECTED || remoteSocket == null) {
            return
        }
        Thread {
            try {
                val dir = if (direction == 1) RemoteDirection.SHORT else RemoteDirection.UNKNOWN_DIRECTION
                val keyMsg = RemoteMessage.newBuilder()
                    .setRemoteKeyInject(RemoteKeyInject.newBuilder()
                        .setKeyCode(RemoteKeyCode.forNumber(keyCode) ?: RemoteKeyCode.KEYCODE_BUTTON_A)
                        .setDirection(dir)
                        .build())
                    .build()
                val out = remoteSocket!!.outputStream
                keyMsg.writeDelimitedTo(out)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }
}
