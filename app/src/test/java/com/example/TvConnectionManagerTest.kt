package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import remote.Remotemessage.RemoteConfigure
import remote.Remotemessage.RemoteDeviceInfo
import remote.Remotemessage.RemoteDirection
import remote.Remotemessage.RemoteKeyCode
import remote.Remotemessage.RemoteMessage
import remote.Remotemessage.RemotePingRequest
import remote.Remotemessage.RemoteSetActive
import remote.Remotemessage.RemoteStart
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.HandshakeCompletedListener
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TvConnectionManagerTest {

    private class LoopbackSSLSocket(private val delegate: Socket) : SSLSocket() {
        override fun getInputStream(): InputStream = delegate.getInputStream()
        override fun getOutputStream(): OutputStream = delegate.getOutputStream()
        override fun close() = delegate.close()
        override fun isClosed(): Boolean = delegate.isClosed
        override fun setSoTimeout(timeout: Int) { delegate.soTimeout = timeout }
        override fun getSoTimeout(): Int = delegate.soTimeout
        override fun setKeepAlive(on: Boolean) { delegate.keepAlive = on }
        override fun startHandshake() {}
        override fun getSupportedCipherSuites(): Array<String> = emptyArray()
        override fun getEnabledCipherSuites(): Array<String> = emptyArray()
        override fun setEnabledCipherSuites(suites: Array<out String>?) {}
        override fun getSupportedProtocols(): Array<String> = emptyArray()
        override fun getEnabledProtocols(): Array<String> = emptyArray()
        override fun setEnabledProtocols(protocols: Array<out String>?) {}
        override fun getSession(): SSLSession? = null
        override fun addHandshakeCompletedListener(listener: HandshakeCompletedListener?) {}
        override fun removeHandshakeCompletedListener(listener: HandshakeCompletedListener?) {}
        override fun setUseClientMode(mode: Boolean) {}
        override fun getUseClientMode(): Boolean = true
        override fun setNeedClientAuth(need: Boolean) {}
        override fun getNeedClientAuth(): Boolean = false
        override fun setWantClientAuth(want: Boolean) {}
        override fun getWantClientAuth(): Boolean = false
        override fun setEnableSessionCreation(flag: Boolean) {}
        override fun getEnableSessionCreation(): Boolean = true
    }

    private fun loopbackProvider(): (String, Int) -> SSLSocket = { host, port ->
        val raw = Socket()
        raw.connect(InetSocketAddress(host, port), 5000)
        raw.soTimeout = 6000
        LoopbackSSLSocket(raw)
    }

    @Test
    fun `connectRemote completes full v2 handshake responds to ping and injects key`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val serverSocket = ServerSocket(0)
        val port = serverSocket.localPort

        val pingReplyLatch = CountDownLatch(1)
        val keyReceivedLatch = CountDownLatch(1)
        var receivedKeyCode: RemoteKeyCode? = null
        var receivedDirection: RemoteDirection? = null
        var serverError: Throwable? = null

        val serverThread = Thread {
            try {
                val client = serverSocket.accept()
                val out = client.getOutputStream()
                val input = client.getInputStream()

                // 1. TV sends RemoteConfigure (newer Android TV Remote Service code1 = 638)
                RemoteMessage.newBuilder()
                    .setRemoteConfigure(
                        RemoteConfigure.newBuilder()
                            .setCode1(638)
                            .setDeviceInfo(
                                RemoteDeviceInfo.newBuilder()
                                    .setModel("Google TV")
                                    .setVendor("Google")
                                    .build()
                            )
                            .build()
                    )
                    .build()
                    .writeDelimitedTo(out)
                out.flush()

                // 2. Client must respond with RemoteConfigure
                val clientCfg = RemoteMessage.parseDelimitedFrom(input)
                assertTrue("Expected RemoteConfigure from client", clientCfg != null && clientCfg.hasRemoteConfigure())
                assertEquals(622, clientCfg!!.remoteConfigure.code1)

                // 3. TV sends RemoteSetActive
                RemoteMessage.newBuilder()
                    .setRemoteSetActive(RemoteSetActive.newBuilder().setActive(638).build())
                    .build()
                    .writeDelimitedTo(out)
                out.flush()

                // 4. Client must respond with RemoteSetActive
                val clientActive = RemoteMessage.parseDelimitedFrom(input)
                assertTrue("Expected RemoteSetActive from client", clientActive != null && clientActive.hasRemoteSetActive())
                assertEquals(622, clientActive!!.remoteSetActive.active)

                // 5. TV sends RemoteStart(true)
                RemoteMessage.newBuilder()
                    .setRemoteStart(RemoteStart.newBuilder().setStarted(true).build())
                    .build()
                    .writeDelimitedTo(out)
                out.flush()

                // 6. TV sends RemotePingRequest(val1 = 42)
                RemoteMessage.newBuilder()
                    .setRemotePingRequest(RemotePingRequest.newBuilder().setVal1(42).build())
                    .build()
                    .writeDelimitedTo(out)
                out.flush()

                // Read ping response and injected key in any order
                repeat(2) {
                    val msg = RemoteMessage.parseDelimitedFrom(input) ?: return@repeat
                    if (msg.hasRemotePingResponse()) {
                        assertEquals(42, msg.remotePingResponse.val1)
                        pingReplyLatch.countDown()
                    } else if (msg.hasRemoteKeyInject()) {
                        receivedKeyCode = msg.remoteKeyInject.keyCode
                        receivedDirection = msg.remoteKeyInject.direction
                        keyReceivedLatch.countDown()
                    }
                }
                client.close()
            } catch (t: Throwable) {
                t.printStackTrace()
                serverError = t
            } finally {
                try { serverSocket.close() } catch (_: Exception) {}
            }
        }
        serverThread.start()

        val manager = TvConnectionManager("127.0.0.1", context, loopbackProvider())
        try {
            manager.connectRemote(port)
            assertEquals(TvConnectionManager.ConnectionState.CONNECTED, manager.connectionState.value)

            manager.sendKey(RemoteKeyCode.KEYCODE_DPAD_UP_VALUE)

            assertTrue("Server should receive RemotePingResponse (serverError=$serverError)", pingReplyLatch.await(5, TimeUnit.SECONDS))
            assertTrue("Server should receive RemoteKeyInject (serverError=$serverError)", keyReceivedLatch.await(5, TimeUnit.SECONDS))
            assertEquals(RemoteKeyCode.KEYCODE_DPAD_UP, receivedKeyCode)
            assertEquals(RemoteDirection.SHORT, receivedDirection)
            if (serverError != null) {
                fail("Server error: ${serverError?.message}")
            }
        } finally {
            manager.disconnect()
            serverThread.join(2000)
        }
    }

    @Test
    fun `connectRemote works with older TV service that omits RemoteStart`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val serverSocket = ServerSocket(0)
        val port = serverSocket.localPort

        val keyReceivedLatch = CountDownLatch(1)
        var receivedKeyCode: RemoteKeyCode? = null

        val serverThread = Thread {
            try {
                val client = serverSocket.accept()
                val out = client.getOutputStream()
                val input = client.getInputStream()

                RemoteMessage.newBuilder()
                    .setRemoteConfigure(RemoteConfigure.newBuilder().setCode1(622).build())
                    .build()
                    .writeDelimitedTo(out)
                out.flush()

                RemoteMessage.parseDelimitedFrom(input)

                RemoteMessage.newBuilder()
                    .setRemoteSetActive(RemoteSetActive.newBuilder().setActive(622).build())
                    .build()
                    .writeDelimitedTo(out)
                out.flush()

                RemoteMessage.parseDelimitedFrom(input)

                val keyMsg = RemoteMessage.parseDelimitedFrom(input)
                if (keyMsg != null && keyMsg.hasRemoteKeyInject()) {
                    receivedKeyCode = keyMsg.remoteKeyInject.keyCode
                    keyReceivedLatch.countDown()
                }
                client.close()
            } catch (_: Exception) {
            } finally {
                try { serverSocket.close() } catch (_: Exception) {}
            }
        }
        serverThread.start()

        val manager = TvConnectionManager("127.0.0.1", context, loopbackProvider())
        try {
            manager.connectRemote(port)
            assertEquals(TvConnectionManager.ConnectionState.CONNECTED, manager.connectionState.value)

            manager.sendKey(RemoteKeyCode.KEYCODE_DPAD_CENTER_VALUE)
            assertTrue("Server should receive RemoteKeyInject", keyReceivedLatch.await(5, TimeUnit.SECONDS))
            assertEquals(RemoteKeyCode.KEYCODE_DPAD_CENTER, receivedKeyCode)
        } finally {
            manager.disconnect()
            serverThread.join(2000)
        }
    }

    @Test
    fun `connectRemote throws PairingRequiredException when TV closes socket before configure`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val serverSocket = ServerSocket(0)
        val port = serverSocket.localPort

        val serverThread = Thread {
            try {
                val client = serverSocket.accept()
                client.close()
            } catch (_: Exception) {
            } finally {
                try { serverSocket.close() } catch (_: Exception) {}
            }
        }
        serverThread.start()

        val manager = TvConnectionManager("127.0.0.1", context, loopbackProvider())
        try {
            manager.connectRemote(port)
            fail("Expected PairingRequiredException when TV closes socket immediately")
        } catch (expected: TvConnectionManager.PairingRequiredException) {
            // Expected behavior so ViewModel triggers pairing
        } finally {
            manager.disconnect()
            serverThread.join(2000)
        }
    }
}
