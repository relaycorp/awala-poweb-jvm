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
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tech.relaycorp.poweb.handshake.Challenge
import tech.relaycorp.poweb.handshake.InvalidMessageException
import tech.relaycorp.poweb.handshake.NonceSigner
import tech.relaycorp.poweb.websocket.CloseConnectionAction
import tech.relaycorp.poweb.websocket.SendBinaryMessageAction
import tech.relaycorp.poweb.websocket.SendTextMessageAction
import tech.relaycorp.relaynet.issueEndpointCertificate
import tech.relaycorp.relaynet.messages.control.NonceSignature
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
            setListenerActions(CloseConnectionAction(1000, "No-op"))
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
        private val challengeSerialized = Challenge(nonce).serialize()

        // Compute client on demand because getting the server port will start the server
        private val client by lazy { PoWebClient.initLocal(mockWebServer.port) }

        private val signer = generateDummySigner()

        @AfterEach
        fun closeClient() = client.ktorClient.close()

        @Test
        fun `Getting an invalid challenge should result in an exception`() {
            setListenerActions(SendTextMessageAction("Not a valid challenge"))

            client.use {
                val exception = assertThrows<PoWebException> {
                    runBlocking { client.wsConnect("/") { handshake(arrayOf(signer)) } }
                }

                assertEquals("Server sent an invalid handshake challenge", exception.message)
                assertTrue(exception.cause is InvalidMessageException)
            }
            await().until { listener!!.closingCode is Int }
            assertEquals(CloseReason.Codes.VIOLATED_POLICY.code.toInt(), listener!!.closingCode)
        }

        @Test
        fun `At least one nonce signer should be required`() {
            setListenerActions(SendBinaryMessageAction(challengeSerialized))

            client.use {
                val exception = assertThrows<PoWebException> {
                    runBlocking {
                        client.wsConnect("/") { handshake(emptyArray()) }
                    }
                }

                assertEquals("At least one nonce signer must be specified", exception.message)
            }
            await().until { listener!!.closingCode is Int }
            assertEquals(CloseReason.Codes.NORMAL.code.toInt(), listener!!.closingCode)
        }

        @Test
        fun `Challenge nonce should be signed with each signer`() {
            setListenerActions(
                    SendBinaryMessageAction(challengeSerialized),
                    CloseConnectionAction(1000)
            )

            val signer2 = generateDummySigner()

            client.use {
                runBlocking { client.wsConnect("/") { handshake(arrayOf(signer, signer2)) } }

                await().until { 0 < listener!!.receivedMessages.size }

                val response = tech.relaycorp.poweb.handshake.Response.deserialize(
                        listener!!.receivedMessages.first()
                )
                val nonceSignatures = response.nonceSignatures
                val signature1 = NonceSignature.deserialize(nonceSignatures[0])
                assertEquals(nonce.asList(), signature1.nonce.asList())
                assertEquals(signer.certificate, signature1.signerCertificate)
                val signature2 = NonceSignature.deserialize(nonceSignatures[1])
                assertEquals(nonce.asList(), signature2.nonce.asList())
                assertEquals(signer2.certificate, signature2.signerCertificate)
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
