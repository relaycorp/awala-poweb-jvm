package tech.relaycorp.poweb

import com.nhaarman.mockitokotlin2.spy
import com.nhaarman.mockitokotlin2.verify
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.okhttp.OkHttpEngine
import io.ktor.client.features.websocket.DefaultClientWebSocketSession
import io.ktor.client.features.websocket.WebSockets
import io.ktor.client.request.HttpRequestData
import io.ktor.http.URLProtocol
import io.ktor.http.cio.websocket.CloseReason
import io.ktor.http.fullPath
import io.ktor.util.InternalAPI
import io.ktor.util.KtorExperimentalAPI
import kotlinx.coroutines.runBlocking
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tech.relaycorp.poweb.handshake.Challenge
import tech.relaycorp.poweb.handshake.InvalidMessageException
import tech.relaycorp.poweb.handshake.NonceSigner
import tech.relaycorp.relaynet.crypto.SignedData
import tech.relaycorp.relaynet.issueEndpointCertificate
import tech.relaycorp.relaynet.wrappers.generateRSAKeyPair
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@KtorExperimentalAPI
class PoWebClientTest {
    @Nested
    inner class Constructor {
        @Nested
        inner class InitLocal {
            @Test
            fun `Host name should be the localhost IP address`() {
                val client = PoWebClient.initLocal()

                assertEquals("127.0.0.1", client.hostName)
            }

            @Test
            fun `TLS should not be used`() {
                val client = PoWebClient.initLocal()

                assertFalse(client.useTls)
            }

            @Test
            fun `Port should default to 276`() {
                val client = PoWebClient.initLocal()

                assertEquals(276, client.port)
            }

            @Test
            fun `Port should be overridable`() {
                val customPort = 13276
                val client = PoWebClient.initLocal(customPort)

                assertEquals(customPort, client.port)
            }
        }

        @Nested
        inner class InitRemote {
            private val hostName = "gb.relaycorp.tech"

            @Test
            fun `Specified host name should be honored`() {
                val client = PoWebClient.initRemote(hostName)

                assertEquals(hostName, client.hostName)
            }

            @Test
            fun `TLS should be used`() {
                val client = PoWebClient.initRemote(hostName)

                assertTrue(client.useTls)
            }

            @Test
            fun `Port should default to 443`() {
                val client = PoWebClient.initRemote(hostName)

                assertEquals(443, client.port)
            }

            @Test
            fun `Port should be overridable`() {
                val customPort = 1234
                val client = PoWebClient.initRemote(hostName, customPort)

                assertEquals(customPort, client.port)
            }
        }
    }

    @InternalAPI
    @Test
    fun `OkHTTP should be the client engine`() {
        val client = PoWebClient.initLocal()

        assertTrue(client.ktorClient.engine is OkHttpEngine)
    }

    @Test
    fun `Close method should close underlying Ktor client`() {
        val client = PoWebClient.initLocal()
        client.ktorClient = spy(client.ktorClient)

        client.close()

        verify(client.ktorClient).close()
    }

    @Nested
    inner class WebSocketConnection : WebSocketTestCase(false) {
        private val hostName = "127.0.0.1"
        private val port = 13276
        private val path = "/v1/the-endpoint"

        @Test
        fun `Client should use WS if TLS is not required`() {
            val wsRequest = wsConnect(false) {}

            assertEquals(URLProtocol.WS, wsRequest.url.protocol)
        }

        @Test
        fun `Client should use WSS if TLS is required`() {
            val wsRequest = wsConnect(true) {}

            assertEquals(URLProtocol.WSS, wsRequest.url.protocol)
        }

        @Test
        fun `Client should connect to specified host`() {
            val wsRequest = wsConnect {}

            assertEquals(hostName, wsRequest.url.host)
        }

        @Test
        fun `Client should connect to specified port`() {
            val wsRequest = wsConnect {}

            assertEquals(port, wsRequest.url.port)
        }

        @Test
        fun `Client should connect to specified path`() {
            val wsRequest = wsConnect {}

            assertEquals(path, wsRequest.url.fullPath)
        }

        @Test
        fun `Specified block should be called`(): Unit = runBlocking {
            setWebSocketListener(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.close(1000, "No-op")
                }
            })
            val client = PoWebClient.initLocal(mockWebServer.port)

            var wasBlockRun = false
            client.wsConnect(path) { wasBlockRun = true }

            assertTrue(wasBlockRun)
        }

        private fun wsConnect(
            useTls: Boolean = false,
            block: suspend DefaultClientWebSocketSession.() -> Unit
        ): HttpRequestData {
            val client = PoWebClient(hostName, port, useTls)
            var connectionRequest: HttpRequestData? = null
            client.ktorClient = HttpClient(MockEngine) {
                install(WebSockets)

                engine {
                    addHandler { request ->
                        connectionRequest = request
                        error("Nothing to see here")
                    }
                }
            }

            assertThrows<IllegalStateException> { runBlocking { client.wsConnect(path, block) } }
            assertTrue(connectionRequest is HttpRequestData)

            return connectionRequest as HttpRequestData
        }
    }

    @Nested
    inner class Handshake : WebSocketTestCase() {
        private val nonce = "nonce".toByteArray()
        private val challengeSerialized = Challenge(nonce).serialize().toByteString()

        // Compute client on demand because getting the server port will start the server
        private val client by lazy { PoWebClient.initLocal(mockWebServer.port) }

        private val signer = generateDummySigner()

        @AfterEach
        fun closeClient() = client.ktorClient.close()

        @Test
        fun `Getting an invalid challenge should result in an exception`() {
            var closeCode: Int? = null

            setWebSocketListener(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send("Not a valid challenge")
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    closeCode = code
                    super.onClosing(webSocket, code, reason)
                }
            })

            client.use {
                val exception = assertThrows<PoWebException> {
                    runBlocking { client.wsConnect("/") { handshake(arrayOf(signer)) } }
                }

                assertEquals("Server sent an invalid handshake challenge", exception.message)
                assertTrue(exception.cause is InvalidMessageException)
            }
            await().until { closeCode is Int }
            assertEquals(CloseReason.Codes.VIOLATED_POLICY.code.toInt(), closeCode)
        }

        @Test
        fun `At least one nonce signer should be required`() {
            var closeCode: Int? = null

            setWebSocketListener(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send(challengeSerialized)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    closeCode = code
                }
            })

            client.use {
                val exception = assertThrows<PoWebException> {
                    runBlocking {
                        client.wsConnect("/") { handshake(emptyArray()) }
                    }
                }

                assertEquals("At least one nonce signer must be specified", exception.message)
            }
            await().until { closeCode is Int }
            assertEquals(CloseReason.Codes.NORMAL.code.toInt(), closeCode)
        }

        @Test
        fun `Challenge nonce should be signed with each signer`() {
            var response: tech.relaycorp.poweb.handshake.Response? = null

            setWebSocketListener(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send(challengeSerialized)
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    response = tech.relaycorp.poweb.handshake.Response.deserialize(
                            bytes.toByteArray()
                    )
                    webSocket.close(1000, "")
                }
            })

            val signer2 = generateDummySigner()

            client.use {
                runBlocking { client.wsConnect("/") { handshake(arrayOf(signer, signer2)) } }

                await().until { response is tech.relaycorp.poweb.handshake.Response }

                val nonceSignatures = response!!.nonceSignatures
                val nonce1SignedData = SignedData.deserialize(nonceSignatures[0])
                nonce1SignedData.verify(nonce)
                assertEquals(
                    signer.certificate,
                    nonce1SignedData.signerCertificate
                )
                val nonce2SignedData = SignedData.deserialize(nonceSignatures[1])
                nonce2SignedData.verify(nonce)
                assertEquals(
                    signer2.certificate,
                    nonce2SignedData.signerCertificate
                )
            }
        }

        private fun generateDummySigner(): NonceSigner {
            val keyPair = generateRSAKeyPair()
            val certificate = issueEndpointCertificate(
                    keyPair.public,
                    keyPair.private,
                    ZonedDateTime.now().plusDays(1))
            return NonceSigner(certificate, keyPair.private)
        }
    }

    @Nested
    inner class ParcelCollection {
        @Test
        @Disabled
        fun `Request should be made to the parcel collection endpoint`() {
        }

        @Test
        @Disabled
        fun `Call should return if server closed connection normally`() {
        }

        @Test
        @Disabled
        fun `An exception should be thrown if the server closes the connection with an error`() {
        }
    }
}
