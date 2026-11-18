package com.example

import android.content.Context
import android.os.Build
import android.util.Base64
import com.google.protobuf.ByteString
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import pairing.Pairingmessage.PairingConfiguration
import pairing.Pairingmessage.PairingEncoding
import pairing.Pairingmessage.PairingMessage
import pairing.Pairingmessage.PairingOption
import pairing.Pairingmessage.PairingRequest
import pairing.Pairingmessage.PairingSecret
import pairing.Pairingmessage.RoleType
import remote.Remotemessage.RemoteConfigure
import remote.Remotemessage.RemoteDeviceInfo
import remote.Remotemessage.RemoteDirection
import remote.Remotemessage.RemoteKeyCode
import remote.Remotemessage.RemoteKeyInject
import remote.Remotemessage.RemoteMessage
import remote.Remotemessage.RemotePingResponse
import remote.Remotemessage.RemoteSetActive
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.IOException
import java.math.BigInteger
import java.net.InetSocketAddress
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.concurrent.Executors
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class TvConnectionManager(
    val host: String,
    val context: Context,
    private val remoteSocketProvider: ((String, Int) -> SSLSocket)? = null
) {

    class PairingRequiredException(message: String, cause: Throwable? = null) : IOException(message, cause)

    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        PAIRING,
        CONNECTED,
        ERROR
    }

    sealed class PairingResult {
        data object Success : PairingResult()
        data class InvalidCode(val reason: String) : PairingResult()
        data class Error(val message: String) : PairingResult()
    }

    companion object {
        const val PAIRING_PORT = 6467
        const val REMOTE_PORT = 6466
        private const val CONNECT_TIMEOUT_MS = 5000
        private const val HANDSHAKE_TIMEOUT_MS = 6000L
        private const val CLIENT_FEATURES = 622
    }

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private val writeLock = Any()
    private val sendExecutor = Executors.newSingleThreadExecutor()

    @Volatile
    private var pairingSocket: SSLSocket? = null

    @Volatile
    private var remoteSocket: SSLSocket? = null

    @Volatile
    private var remoteReaderThread: Thread? = null

    private var sslContext: SSLContext? = null
    private var clientCert: X509Certificate? = null
    private var serverCert: X509Certificate? = null

    private fun generateCertificate(): SSLContext {
        sslContext?.let { return it }

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
                clientCert = null
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

        return SSLContext.getInstance("TLS").apply {
            init(keyManagerFactory.keyManagers, trustAllCerts, SecureRandom())
            sslContext = this
        }
    }

    private fun closePairingSocket() {
        try {
            pairingSocket?.close()
        } catch (_: Exception) {
        } finally {
            pairingSocket = null
        }
    }

    private fun closeRemoteSocket() {
        val sock = remoteSocket
        remoteSocket = null
        try {
            sock?.close()
        } catch (_: Exception) {
        }
    }

    suspend fun startPairing(port: Int = PAIRING_PORT) = withContext(Dispatchers.IO) {
        try {
            closeRemoteSocket()
            closePairingSocket()
            _statusMessage.value = null
            _connectionState.value = ConnectionState.PAIRING

            val ssl = generateCertificate()
            val socket = ssl.socketFactory.createSocket() as SSLSocket
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            socket.soTimeout = 15000
            socket.startHandshake()
            pairingSocket = socket

            val out = socket.outputStream
            val input = socket.inputStream

            val msg = PairingMessage.newBuilder()
                .setProtocolVersion(2)
                .setStatus(PairingMessage.Status.STATUS_OK)
                .setPairingRequest(
                    PairingRequest.newBuilder()
                        .setServiceName("AndroidRemote")
                        .setClientName("AndroidRemote")
                        .build()
                )
                .build()
            msg.writeDelimitedTo(out)
            out.flush()

            val reqAck = PairingMessage.parseDelimitedFrom(input)
            if (reqAck == null || reqAck.status != PairingMessage.Status.STATUS_OK) {
                throw IOException("PairingRequest rejected: ${reqAck?.status}")
            }

            val optionMsg = PairingMessage.newBuilder()
                .setProtocolVersion(2)
                .setStatus(PairingMessage.Status.STATUS_OK)
                .setPairingOption(
                    PairingOption.newBuilder()
                        .setPreferredRole(RoleType.ROLE_TYPE_INPUT)
                        .addInputEncodings(
                            PairingEncoding.newBuilder()
                                .setType(PairingEncoding.EncodingType.ENCODING_TYPE_HEXADECIMAL)
                                .setSymbolLength(6)
                                .build()
                        )
                        .build()
                )
                .build()
            optionMsg.writeDelimitedTo(out)
            out.flush()

            val optAck = PairingMessage.parseDelimitedFrom(input)
            if (optAck == null || optAck.status != PairingMessage.Status.STATUS_OK) {
                throw IOException("PairingOption rejected: ${optAck?.status}")
            }

            val configMsg = PairingMessage.newBuilder()
                .setProtocolVersion(2)
                .setStatus(PairingMessage.Status.STATUS_OK)
                .setPairingConfiguration(
                    PairingConfiguration.newBuilder()
                        .setClientRole(RoleType.ROLE_TYPE_INPUT)
                        .setEncoding(
                            PairingEncoding.newBuilder()
                                .setType(PairingEncoding.EncodingType.ENCODING_TYPE_HEXADECIMAL)
                                .setSymbolLength(6)
                                .build()
                        )
                        .build()
                )
                .build()
            configMsg.writeDelimitedTo(out)
            out.flush()

            val configAck = PairingMessage.parseDelimitedFrom(input)
            if (configAck == null || configAck.status != PairingMessage.Status.STATUS_OK) {
                throw IOException("PairingConfiguration rejected: ${configAck?.status}")
            }

            // Keep socket open waiting for user to enter the 6-character hex code shown on TV
            socket.soTimeout = 0
        } catch (e: Exception) {
            e.printStackTrace()
            closePairingSocket()
            _statusMessage.value = e.message ?: "Pairing failed"
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
        val pubKey = cert.publicKey as RSAPublicKey
        var mod = pubKey.modulus.toByteArray()
        if (mod.size > 1 && mod[0] == 0.toByte()) {
            mod = mod.copyOfRange(1, mod.size)
        }
        var exp = pubKey.publicExponent.toByteArray()
        if (exp.size > 1 && exp[0] == 0.toByte()) {
            exp = exp.copyOfRange(1, exp.size)
        }
        return Pair(mod, exp)
    }

    suspend fun sendPairingCode(code: String): PairingResult = withContext(Dispatchers.IO) {
        val cleanCode = code.trim().replace(" ", "").uppercase()
        if (cleanCode.length != 6 || !cleanCode.all { it in '0'..'9' || it in 'A'..'F' }) {
            return@withContext PairingResult.InvalidCode("Enter the 6-character hex code shown on TV (0-9, A-F).")
        }

        val socket = pairingSocket
        if (socket == null || socket.isClosed) {
            _connectionState.value = ConnectionState.ERROR
            return@withContext PairingResult.Error("Pairing session expired. Please reconnect.")
        }

        try {
            val codeBytes = hexStringToBytes(cleanCode)
            val cCert = clientCert ?: throw IllegalStateException("Client certificate missing")
            val sCert = serverCert ?: throw IllegalStateException("Server certificate missing")

            val (clientMod, clientExp) = getRsaModulusAndExponent(cCert)
            val (serverMod, serverExp) = getRsaModulusAndExponent(sCert)

            val md = MessageDigest.getInstance("SHA-256")
            md.update(clientMod)
            md.update(clientExp)
            md.update(serverMod)
            md.update(serverExp)
            md.update(hexStringToBytes(cleanCode.substring(2)))
            val hash = md.digest()

            if (hash[0] != codeBytes[0]) {
                return@withContext PairingResult.InvalidCode("Incorrect pairing code. Check the TV and try again.")
            }

            val secretMsg = PairingMessage.newBuilder()
                .setProtocolVersion(2)
                .setStatus(PairingMessage.Status.STATUS_OK)
                .setPairingSecret(
                    PairingSecret.newBuilder()
                        .setSecret(ByteString.copyFrom(hash))
                        .build()
                )
                .build()
            secretMsg.writeDelimitedTo(socket.outputStream)
            socket.outputStream.flush()

            socket.soTimeout = 10000
            val ack = PairingMessage.parseDelimitedFrom(socket.inputStream)
            closePairingSocket()

            if (ack == null || ack.status != PairingMessage.Status.STATUS_OK) {
                _connectionState.value = ConnectionState.ERROR
                return@withContext PairingResult.Error("TV rejected pairing (${ack?.status ?: "no response"}).")
            }

            _statusMessage.value = null
            _connectionState.value = ConnectionState.CONNECTING
            PairingResult.Success
        } catch (e: Exception) {
            e.printStackTrace()
            closePairingSocket()
            _statusMessage.value = e.message ?: "Pairing failed"
            _connectionState.value = ConnectionState.ERROR
            PairingResult.Error(e.message ?: "Pairing failed")
        }
    }

    suspend fun connectRemote(port: Int = REMOTE_PORT) = withContext(Dispatchers.IO) {
        closePairingSocket()
        closeRemoteSocket()
        _statusMessage.value = null
        _connectionState.value = ConnectionState.CONNECTING

        val socket = try {
            remoteSocketProvider?.invoke(host, port) ?: run {
                val ssl = generateCertificate()
                (ssl.socketFactory.createSocket() as SSLSocket).also { s ->
                    s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                    s.soTimeout = HANDSHAKE_TIMEOUT_MS.toInt()
                    s.keepAlive = true
                    s.startHandshake()
                }
            }
        } catch (e: SSLException) {
            _connectionState.value = ConnectionState.DISCONNECTED
            throw PairingRequiredException("TLS handshake rejected; pairing required", e)
        } catch (e: Exception) {
            _statusMessage.value = e.message ?: "Connection failed"
            _connectionState.value = ConnectionState.ERROR
            throw e
        }

        remoteSocket = socket
        val handshakeReady = CompletableDeferred<Unit>()
        startRemoteReaderLoop(socket, handshakeReady)

        try {
            withTimeout(HANDSHAKE_TIMEOUT_MS) {
                handshakeReady.await()
            }
            socket.soTimeout = 0
            _statusMessage.value = null
            _connectionState.value = ConnectionState.CONNECTED
        } catch (e: PairingRequiredException) {
            if (remoteSocket === socket) {
                closeRemoteSocket()
                _connectionState.value = ConnectionState.DISCONNECTED
            }
            throw e
        } catch (e: Exception) {
            if (remoteSocket === socket) {
                closeRemoteSocket()
                _statusMessage.value = e.message ?: "Handshake failed"
                _connectionState.value = ConnectionState.ERROR
            }
            throw e
        }
    }

    private fun startRemoteReaderLoop(socket: SSLSocket, handshakeReady: CompletableDeferred<Unit>) {
        val thread = Thread({
            var receivedConfigure = false
            var receivedSetActive = false
            var negotiatedCode1 = CLIENT_FEATURES

            try {
                val input = socket.inputStream
                while (remoteSocket === socket && !socket.isClosed) {
                    val msg = try {
                        RemoteMessage.parseDelimitedFrom(input)
                            ?: throw EOFException("Connection closed by TV")
                    } catch (ex: Exception) {
                        if (!isSocketTimeout(ex)) throw ex
                        if (handshakeReady.isCompleted) {
                            socket.soTimeout = 0
                            continue
                        }
                        if (receivedConfigure && !receivedSetActive) {
                            sendRemoteMessage(
                                socket,
                                RemoteMessage.newBuilder()
                                    .setRemoteSetActive(
                                        RemoteSetActive.newBuilder()
                                            .setActive(negotiatedCode1)
                                            .build()
                                    )
                                    .build()
                            )
                            receivedSetActive = true
                            socket.soTimeout = 0
                            handshakeReady.complete(Unit)
                            continue
                        }
                        if (receivedConfigure && receivedSetActive) {
                            socket.soTimeout = 0
                            handshakeReady.complete(Unit)
                            continue
                        }
                        throw ex
                    }

                    when {
                        msg.hasRemoteConfigure() -> {
                            val serverCode1 = msg.remoteConfigure.code1
                            negotiatedCode1 = if (serverCode1 != 0) {
                                (serverCode1 and CLIENT_FEATURES).takeIf { it != 0 } ?: CLIENT_FEATURES
                            } else {
                                CLIENT_FEATURES
                            }
                            sendRemoteMessage(socket, buildClientConfigureMessage(negotiatedCode1))
                            receivedConfigure = true
                            if (!handshakeReady.isCompleted) {
                                socket.soTimeout = 1000
                            }
                        }

                        msg.hasRemoteSetActive() -> {
                            val serverActive = msg.remoteSetActive.active
                            val activeToSend = if (serverActive != 0) {
                                (serverActive and negotiatedCode1).takeIf { it != 0 } ?: negotiatedCode1
                            } else {
                                negotiatedCode1
                            }
                            if (!receivedSetActive || serverActive != 0) {
                                sendRemoteMessage(
                                    socket,
                                    RemoteMessage.newBuilder()
                                        .setRemoteSetActive(
                                            RemoteSetActive.newBuilder()
                                                .setActive(activeToSend)
                                                .build()
                                        )
                                        .build()
                                )
                            }
                            receivedSetActive = true
                            if (receivedConfigure && !handshakeReady.isCompleted) {
                                socket.soTimeout = 800
                            }
                        }

                        msg.hasRemoteStart() -> {
                            if (msg.remoteStart.started) {
                                if (!receivedSetActive) {
                                    sendRemoteMessage(
                                        socket,
                                        RemoteMessage.newBuilder()
                                            .setRemoteSetActive(
                                                RemoteSetActive.newBuilder()
                                                    .setActive(negotiatedCode1)
                                                    .build()
                                            )
                                            .build()
                                    )
                                    receivedSetActive = true
                                }
                                socket.soTimeout = 0
                                if (!handshakeReady.isCompleted) {
                                    handshakeReady.complete(Unit)
                                }
                            }
                        }

                        msg.hasRemotePingRequest() -> {
                            sendRemoteMessage(
                                socket,
                                RemoteMessage.newBuilder()
                                    .setRemotePingResponse(
                                        RemotePingResponse.newBuilder()
                                            .setVal1(msg.remotePingRequest.val1)
                                            .build()
                                    )
                                    .build()
                            )
                            if (receivedConfigure && receivedSetActive && !handshakeReady.isCompleted) {
                                socket.soTimeout = 0
                                handshakeReady.complete(Unit)
                            }
                        }

                        msg.hasRemoteError() -> {
                            val err = IOException("RemoteError from TV: ${msg.remoteError.value}")
                            if (!receivedConfigure) {
                                throw PairingRequiredException("TV rejected remote session before configure", err)
                            }
                            throw err
                        }

                        else -> {
                            if (receivedConfigure && receivedSetActive && !handshakeReady.isCompleted) {
                                socket.soTimeout = 0
                                handshakeReady.complete(Unit)
                            }
                        }
                    }
                }
                if (remoteSocket === socket) {
                    closeRemoteSocket()
                    if (_connectionState.value == ConnectionState.CONNECTED) {
                        _connectionState.value = ConnectionState.DISCONNECTED
                    }
                }
            } catch (e: Exception) {
                handleReaderFailure(socket, handshakeReady, e, receivedConfigure)
            }
        }, "TvRemoteReader")
        thread.isDaemon = true
        remoteReaderThread = thread
        thread.start()
    }

    private fun isSocketTimeout(throwable: Throwable?): Boolean {
        var current = throwable
        while (current != null) {
            if (current is java.net.SocketTimeoutException) {
                return true
            }
            current = current.cause
        }
        return false
    }

    private fun isTlsOrEofFailure(throwable: Throwable?): Boolean {
        var current = throwable
        while (current != null) {
            if (current is EOFException || current is SSLException || current is PairingRequiredException) {
                return true
            }
            current = current.cause
        }
        return false
    }

    private fun handleReaderFailure(
        socket: SSLSocket,
        handshakeReady: CompletableDeferred<Unit>,
        e: Exception,
        receivedConfigure: Boolean
    ) {
        if (!handshakeReady.isCompleted) {
            val mapped = if (!receivedConfigure && isTlsOrEofFailure(e)) {
                PairingRequiredException("TV rejected or closed connection before configure; pairing required", e)
            } else {
                e
            }
            handshakeReady.completeExceptionally(mapped)
        }
        if (remoteSocket === socket) {
            closeRemoteSocket()
            if (_connectionState.value == ConnectionState.CONNECTED) {
                _connectionState.value = ConnectionState.DISCONNECTED
            }
        }
    }

    private fun buildClientConfigureMessage(code1: Int): RemoteMessage {
        return RemoteMessage.newBuilder()
            .setRemoteConfigure(
                RemoteConfigure.newBuilder()
                    .setCode1(code1)
                    .setDeviceInfo(
                        RemoteDeviceInfo.newBuilder()
                            .setModel(Build.MODEL ?: "AndroidRemote")
                            .setVendor(Build.MANUFACTURER ?: "Google")
                            .setUnknown1(1)
                            .setUnknown2("1")
                            .setPackageName(context.packageName)
                            .setAppVersion("1.0")
                            .build()
                    )
                    .build()
            )
            .build()
    }

    private fun sendRemoteMessage(socket: SSLSocket?, message: RemoteMessage): Boolean {
        if (socket == null || socket.isClosed) return false
        return synchronized(writeLock) {
            try {
                if (socket.isClosed) return@synchronized false
                val out = socket.outputStream
                message.writeDelimitedTo(out)
                out.flush()
                true
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }
    }

    fun disconnect() {
        closePairingSocket()
        closeRemoteSocket()
        _statusMessage.value = null
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    fun sendKey(keyCode: Int, direction: RemoteDirection = RemoteDirection.SHORT) {
        val socket = remoteSocket
        if (_connectionState.value != ConnectionState.CONNECTED || socket == null || socket.isClosed) {
            return
        }
        val remoteKeyCode = RemoteKeyCode.forNumber(keyCode) ?: return
        sendExecutor.execute {
            val keyMsg = RemoteMessage.newBuilder()
                .setRemoteKeyInject(
                    RemoteKeyInject.newBuilder()
                        .setKeyCode(remoteKeyCode)
                        .setDirection(direction)
                        .build()
                )
                .build()
            val sent = sendRemoteMessage(socket, keyMsg)
            if (!sent && remoteSocket === socket) {
                closeRemoteSocket()
                _connectionState.value = ConnectionState.DISCONNECTED
            }
        }
    }
}
