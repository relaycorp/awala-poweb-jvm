package tech.relaycorp.poweb

import com.nhaarman.mockitokotlin2.spy
import com.nhaarman.mockitokotlin2.verify
import io.ktor.client.engine.okhttp.OkHttpEngine
import io.ktor.client.features.websocket.DefaultClientWebSocketSession
import io.ktor.client.request.HttpRequestData
import io.ktor.http.URLProtocol
import io.ktor.http.cio.websocket.CloseReason
import io.ktor.util.InternalAPI
import io.ktor.util.KtorExperimentalAPI
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runBlockingTest
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tech.relaycorp.poweb.handshake.InvalidMessageException
import tech.relaycorp.poweb.handshake.NonceSigner
import tech.relaycorp.poweb.websocket.ActionSequence
import tech.relaycorp.poweb.websocket.ChallengeAction
import tech.relaycorp.poweb.websocket.CloseConnectionAction
import tech.relaycorp.poweb.websocket.MockKtorClientManager
import tech.relaycorp.poweb.websocket.ParcelDeliveryAction
import tech.relaycorp.poweb.websocket.SendTextMessageAction
import tech.relaycorp.poweb.websocket.ServerShutdownAction
import tech.relaycorp.poweb.websocket.WebSocketTestCase
import tech.relaycorp.relaynet.issueEndpointCertificate
import tech.relaycorp.relaynet.messages.control.NonceSignature
import tech.relaycorp.relaynet.wrappers.generateRSAKeyPair
import java.io.EOFException
import java.net.ConnectException
import java.nio.charset.Charset
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@KtorExperimentalAPI
class PoWebClientTest {
    @Nested
    @Suppress("RedundantInnerClassModifier")
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
    @Suppress("RedundantInnerClassModifier")
    @ExperimentalCoroutinesApi
    inner class WebSocketConnection : WebSocketTestCase(false) {
        private val hostName = "127.0.0.1"
        private val port = 13276
        private val path = "/v1/the-endpoint"

        @Test
        fun `Failing to connect to the server should throw an exception`() {
            // Connect to an invalid port
            val client = PoWebClient.initLocal(mockWebServer.port - 1)

            client.use {
                val exception = assertThrows<PoWebException> {
                    runBlocking { client.wsConnect(path) {} }
                }

                assertEquals("Server is unreachable", exception.message)
                assertTrue(exception.cause is ConnectException)
            }
        }

        @Test
        fun `Losing the connection abruptly should throw an exception`(): Unit = runBlocking {
            val client = PoWebClient.initLocal(mockWebServer.port)
            setListenerActions(ServerShutdownAction())

            client.use {
                val exception = assertThrows<PoWebException> {
                    runBlocking { client.wsConnect(path) {} }
                }

                assertEquals("Connection was closed abruptly", exception.message)
                assertTrue(exception.cause is EOFException)
            }
        }

        @Test
        fun `Client should use WS if TLS is not required`() = runBlockingTest {
            val wsRequest = mockWSConnect(false) {}

            assertEquals(URLProtocol.WS, wsRequest.url.protocol)
        }

        @Test
        fun `Client should use WSS if TLS is required`() = runBlockingTest {
            val wsRequest = mockWSConnect(true) {}

            assertEquals(URLProtocol.WSS, wsRequest.url.protocol)
        }

        @Test
        fun `Client should connect to specified host and port`(): Unit = runBlocking {
            setListenerActions(CloseConnectionAction())
            val client = PoWebClient.initLocal(mockWebServer.port)

            client.wsConnect(path) {}

            assertTrue(listener!!.connected)
        }

        @Test
        fun `Client should connect to specified path`() = runBlocking {
            val wsRequest = mockWSConnect {}

            assertEquals(path, wsRequest.url.encodedPath)
        }

        @Test
        fun `Specified block should be called`(): Unit = runBlocking {
            setListenerActions(CloseConnectionAction())
            val client = PoWebClient.initLocal(mockWebServer.port)

            var wasBlockRun = false
            client.wsConnect(path) { wasBlockRun = true }

            assertTrue(wasBlockRun)
        }

        private suspend fun mockWSConnect(
            useTls: Boolean = false,
            block: suspend DefaultClientWebSocketSession.() -> Unit
        ): HttpRequestData {
            val client = PoWebClient(hostName, port, useTls)
            val ktorClientManager = MockKtorClientManager()
            client.ktorClient = ktorClientManager.ktorClient

            ktorClientManager.useClient {
                client.wsConnect(path, block)
            }

            return ktorClientManager.request
        }
    }

    @Nested
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
            val mockClient = PoWebClient.initLocal()
            val ktorClientManager = MockKtorClientManager()
            mockClient.ktorClient = ktorClientManager.ktorClient

            ktorClientManager.useClient {
                mockClient.collectParcels(arrayOf(signer)).toList()
            }

            assertEquals(
                PoWebClient.PARCEL_COLLECTION_ENDPOINT_PATH,
                ktorClientManager.request.url.encodedPath
            )
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
            await().until { listener!!.closingCode != null }
            assertEquals(CloseReason.Codes.VIOLATED_POLICY, listener!!.closingCode)
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
            await().until { listener!!.closingCode != null }
            assertEquals(CloseReason.Codes.NORMAL, listener!!.closingCode)
        }

        @Test
        fun `Challenge nonce should be signed with each signer`() {
            setListenerActions(ChallengeAction(nonce), CloseConnectionAction())

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
                setListenerActions(ChallengeAction(nonce), CloseConnectionAction())

                client.use {
                    client.collectParcels(arrayOf(signer)).collect { }
                }

                assertEquals(CloseReason.Codes.NORMAL, listener!!.closingCode)
            }

        @Test
        fun `Exception should be thrown if server closes connection with error`(): Unit =
            runBlocking {
                val code = CloseReason.Codes.VIOLATED_POLICY
                val reason = "Whoops"
                setListenerActions(ChallengeAction(nonce), CloseConnectionAction(code, reason))

                client.use {
                    val exception = assertThrows<PoWebException> {
                        runBlocking { client.collectParcels(arrayOf(signer)).toList() }
                    }

                    assertEquals(
                        "Server closed the connection unexpectedly " +
                            "(code: ${code.code}, reason: $reason)",
                        exception.message
                    )
                }
            }

        @Test
        fun `Cancelling the flow should close the connection normally`(): Unit = runBlocking {
            val undeliveredAction =
                ParcelDeliveryAction("second delivery id", "second parcel".toByteArray())
            setListenerActions(
                ChallengeAction(nonce),
                ParcelDeliveryAction(deliveryId, parcelSerialized),
                undeliveredAction
            )

            client.use {
                val deliveries = client.collectParcels(arrayOf(signer)).take(1).toList()

                assertEquals(1, deliveries.size)
            }

            assertFalse(undeliveredAction.wasRun)
            assertEquals(CloseReason.Codes.NORMAL, listener!!.closingCode)
        }

        @Test
        fun `No delivery should be output if the server doesn't deliver anything`(): Unit =
            runBlocking {
                setListenerActions(ChallengeAction(nonce), CloseConnectionAction())

                client.use {
                    val deliveries = client.collectParcels(arrayOf(signer)).toList()

                    assertEquals(0, deliveries.size)
                }
            }

        @Test
        fun `Malformed deliveries should be refused`(): Unit = runBlocking {
            setListenerActions(ChallengeAction(nonce), SendTextMessageAction("invalid"))

            client.use {
                val exception = assertThrows<PoWebException> {
                    runBlocking { client.collectParcels(arrayOf(signer)).toList() }
                }

                assertEquals("Received invalid message from server", exception.message)
                assertTrue(
                    // TODO:
                    exception.cause is tech.relaycorp.relaynet.messages.InvalidMessageException
                )

                assertEquals(CloseReason.Codes.VIOLATED_POLICY, listener!!.closingCode!!)
                assertEquals("Invalid parcel delivery", listener!!.closingReason!!)
            }
        }

        @Test
        fun `One delivery should be output if the server delivers one parcel`(): Unit =
            runBlocking {
                setListenerActions(
                    ChallengeAction(nonce),
                    ActionSequence(
                        ParcelDeliveryAction(deliveryId, parcelSerialized),
                        CloseConnectionAction()
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
                    CloseConnectionAction()
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
        fun `Each ACK should be passed on to the server`(): Unit = runBlocking {
            setListenerActions(
                ChallengeAction(nonce),
                ParcelDeliveryAction(deliveryId, parcelSerialized),
                CloseConnectionAction()
            )

            client.use {
                client.collectParcels(arrayOf(signer)).collect { it.ack() }
            }

            // The server should've got two messages: The handshake response and the ACK
            assertEquals(2, listener!!.receivedMessages.size)
            assertEquals(
                deliveryId,
                listener!!.receivedMessages[1].toString(Charset.defaultCharset())
            )
        }

        @Test
        fun `Missing ACKs should be honored`(): Unit = runBlocking {
            // The server will deliver 2 parcels but the client will only ACK the first one
            val additionalParcelDelivery =
                ParcelDeliveryAction("second delivery id", "parcel".toByteArray())
            setListenerActions(
                ChallengeAction(nonce),
                ParcelDeliveryAction(deliveryId, parcelSerialized),
                ActionSequence(
                    additionalParcelDelivery,
                    CloseConnectionAction()
                )
            )

            client.use {
                var wasFirstCollectionAcknowledged = false
                client.collectParcels(arrayOf(signer)).collect {
                    // Only acknowledge the first collection
                    if (!wasFirstCollectionAcknowledged) {
                        it.ack()
                        wasFirstCollectionAcknowledged = true
                    }
                }
            }

            // The server should've got two messages: The handshake response and the first ACK
            assertEquals(2, listener!!.receivedMessages.size)
            assertEquals(
                deliveryId,
                listener!!.receivedMessages[1].toString(Charset.defaultCharset())
            )
            assertTrue(additionalParcelDelivery.wasRun)
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
