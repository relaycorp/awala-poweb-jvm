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
import io.ktor.util.InternalAPI
import io.ktor.util.KtorExperimentalAPI
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runBlockingTest
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tech.relaycorp.poweb.handshake.InvalidMessageException
import tech.relaycorp.poweb.handshake.NonceSigner
import tech.relaycorp.poweb.websocket.ActionSequence
import tech.relaycorp.poweb.websocket.ChallengeAction
import tech.relaycorp.poweb.websocket.CloseConnectionAction
import tech.relaycorp.poweb.websocket.ParcelDeliveryAction
import tech.relaycorp.poweb.websocket.SendTextMessageAction
import tech.relaycorp.relaynet.issueEndpointCertificate
import tech.relaycorp.relaynet.messages.control.NonceSignature
import tech.relaycorp.relaynet.wrappers.generateRSAKeyPair
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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
        fun `Client should connect to specified host and port`(): Unit = runBlocking {
            setListenerActions(CloseConnectionAction(1000))
            val client = PoWebClient.initLocal(mockWebServer.port)

            client.wsConnect(path) {}

            assertNotNull(listener!!.request)
        }

        @Test
        fun `Underlying HTTP request should use GET`() = runBlocking {
            setListenerActions(CloseConnectionAction(1000))
            val client = PoWebClient.initLocal(mockWebServer.port)

            client.wsConnect(path) {}

            assertEquals("GET", listener!!.request!!.method)
        }

        @Test
        fun `Client should connect to specified path`() = runBlocking {
            setListenerActions(CloseConnectionAction(1000))
            val client = PoWebClient.initLocal(mockWebServer.port)

            client.wsConnect(path) {}

            assertEquals(path, listener!!.request!!.url.encodedPath)
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
    @ExperimentalCoroutinesApi
    inner class CollectParcels : WebSocketTestCase() {
        private val nonce = "nonce".toByteArray()

        // Compute client on demand because getting the server port will start the server
        private val client by lazy { PoWebClient.initLocal(mockWebServer.port) }

        private val signer = generateDummySigner()

        private val deliveryId = "the delivery id"
        private val parcelSerialized = "the parcel serialized".toByteArray()

        @AfterEach
        fun closeClient() = client.ktorClient.close()

        @Test
        fun `Request should be made to the parcel collection endpoint`() = runBlocking {
            setListenerActions(ChallengeAction(nonce), CloseConnectionAction(1000))

            client.use {
                client.collectParcels(arrayOf(signer)).collect { }
            }

            assertEquals("/v1/parcel-collection", listener!!.request!!.url.encodedPath)
        }

        @Test
        fun `Getting a closing frame before the handshake should result in an exception`() {
            setListenerActions(CloseConnectionAction(1000))

            client.use {
                val exception = assertThrows<PoWebException> {
                    runBlocking { client.collectParcels(arrayOf(signer)).first() }
                }

                assertEquals("Server closed the connection before the handshake", exception.message)
                assertTrue(exception.cause is ClosedReceiveChannelException)
            }
        }

        @Test
        fun `Getting an invalid challenge should result in an exception`() {
            setListenerActions(SendTextMessageAction("Not a valid challenge"))

            client.use {
                val exception = assertThrows<PoWebException> {
                    runBlocking { client.collectParcels(arrayOf(signer)).first() }
                }

                assertEquals("Server sent an invalid handshake challenge", exception.message)
                assertTrue(exception.cause is InvalidMessageException)
            }
            await().until { listener!!.closingCode is Int }
            assertEquals(CloseReason.Codes.VIOLATED_POLICY.code.toInt(), listener!!.closingCode)
        }

        @Test
        fun `At least one nonce signer should be required`() {
            setListenerActions(ChallengeAction(nonce))

            client.use {
                val exception = assertThrows<PoWebException> {
                    runBlocking { client.collectParcels(emptyArray()).first() }
                }

                assertEquals("At least one nonce signer must be specified", exception.message)
            }
            await().until { listener!!.closingCode is Int }
            assertEquals(CloseReason.Codes.NORMAL.code.toInt(), listener!!.closingCode)
        }

        @Test
        fun `Challenge nonce should be signed with each signer`() {
            setListenerActions(ChallengeAction(nonce), CloseConnectionAction(1000))

            val signer2 = generateDummySigner()

            client.use {
                runBlocking { client.collectParcels(arrayOf(signer, signer2)).collect {} }

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

        @Test
        fun `Call should return if server closed connection normally after the handshake`(): Unit =
            runBlocking {
                setListenerActions(ChallengeAction(nonce), CloseConnectionAction(1000))

                client.use {
                    client.collectParcels(arrayOf(signer)).collect { }
                }

                assertEquals(1000, listener!!.closingCode)
            }

        @Test
        fun `Exception should be thrown if server closes connection with error`() =
            runBlockingTest {
                val code = 1011
                val reason = "Whoops"
                setListenerActions(ChallengeAction(nonce), CloseConnectionAction(code, reason))

                client.use {
                    val exception = assertThrows<PoWebException> {
                        runBlocking { client.collectParcels(arrayOf(signer)).toList() }
                    }

                    assertEquals(
                        "Server closed the connection unexpectedly (code: $code, reason: $reason)",
                        exception.message
                    )
                }
            }

        @Test
        @Disabled
        fun `Cancelling the flow should close the connection normally`() {
        }

        @Test
        fun `No delivery should be output if the server doesn't deliver anything`(): Unit =
            runBlocking {
                setListenerActions(ChallengeAction(nonce), CloseConnectionAction(1000))

                client.use {
                    val deliveries = client.collectParcels(arrayOf(signer)).toList()

                    assertEquals(0, deliveries.size)
                }
            }

        @Test
        @Disabled
        fun `Malformed deliveries should be refused`() {
        }

        @Test
        fun `One delivery should be output if the server delivers one parcel`(): Unit =
            runBlocking {
                setListenerActions(
                    ChallengeAction(nonce),
                    ActionSequence(
                        ParcelDeliveryAction(deliveryId, parcelSerialized),
                        CloseConnectionAction(1000)
                    )
                )

                client.use {
                    val deliveries = client.collectParcels(arrayOf(signer)).toList()

                    assertEquals(1, deliveries.size)
                    assertEquals(
                        parcelSerialized.asList(),
                        deliveries.first().parcelSerialized.asList()
                    )
                }
            }

        @Test
        fun `Multiple deliveries should be output if applicable`(): Unit = runBlocking {
            val parcelSerialized2 = "second parcel".toByteArray()
            setListenerActions(
                ChallengeAction(nonce),
                ActionSequence(
                    ParcelDeliveryAction(deliveryId, parcelSerialized),
                    ParcelDeliveryAction("second delivery id", parcelSerialized2),
                    CloseConnectionAction(1000)
                )
            )

            client.use {
                val deliveries = client.collectParcels(arrayOf(signer)).toList()

                assertEquals(2, deliveries.size)
                assertEquals(
                    parcelSerialized.asList(),
                    deliveries.first().parcelSerialized.asList()
                )
                assertEquals(
                    parcelSerialized2.asList(),
                    deliveries[1].parcelSerialized.asList()
                )
            }
        }

        @Test
        @Disabled
        fun `Each collection acknowledgement should be passed on to the server`() {
        }

        @Test
        @Disabled
        fun `No acknowledgement should be sent to the server is client never acknowledged`() {
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
