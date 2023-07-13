package tech.relaycorp.poweb

import io.ktor.http.cio.websocket.CloseReason
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tech.relaycorp.poweb.websocket.ActionSequence
import tech.relaycorp.poweb.websocket.ChallengeAction
import tech.relaycorp.poweb.websocket.CloseConnectionAction
import tech.relaycorp.poweb.websocket.ParcelDeliveryAction
import tech.relaycorp.poweb.websocket.SendTextMessageAction
import tech.relaycorp.poweb.websocket.WebSocketTestCase
import tech.relaycorp.relaynet.bindings.pdc.DetachedSignatureType
import tech.relaycorp.relaynet.bindings.pdc.NonceSignerException
import tech.relaycorp.relaynet.bindings.pdc.ServerBindingException
import tech.relaycorp.relaynet.bindings.pdc.ServerConnectionException
import tech.relaycorp.relaynet.bindings.pdc.Signer
import tech.relaycorp.relaynet.bindings.pdc.StreamingMode
import tech.relaycorp.relaynet.issueEndpointCertificate
import tech.relaycorp.relaynet.messages.InvalidMessageException
import tech.relaycorp.relaynet.messages.control.HandshakeResponse
import tech.relaycorp.relaynet.testing.pki.KeyPairSet
import tech.relaycorp.relaynet.testing.pki.PDACertPath
import tech.relaycorp.relaynet.wrappers.generateRSAKeyPair
import java.nio.charset.Charset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ParcelCollectionTest : WebSocketTestCase() {
    private val nonce = "nonce".toByteArray()

    // Compute client on demand because getting the server port will start the server
    private val client by lazy { PoWebClient.initLocal(mockWebServer.port) }

    private val signer =
        Signer(PDACertPath.PRIVATE_ENDPOINT, KeyPairSet.PRIVATE_ENDPOINT.private)

    private val deliveryId = "the delivery id"
    private val parcelSerialized = "the parcel serialized".toByteArray()

    @AfterEach
    fun closeClient() = client.ktorClient.close()

    @Test
    fun `Request should be made to the parcel collection endpoint`() {
        val client = PoWebClient.initLocal(mockWebServer.port)
        client.ktorClient = ktorWSClient
        addServerConnection(CloseConnectionAction())

        assertThrows<ServerConnectionException> {
            runBlocking { client.collectParcels(arrayOf(signer)).toList() }
        }

        val request = mockWebServer.takeRequest()
        assertEquals(
            "/v1${PoWebClient.PARCEL_COLLECTION_ENDPOINT_PATH}",
            request.path
        )
    }

    @Nested
    inner class Handshake {
        @Test
        fun `Server closing connection during handshake should throw exception`() {
            addServerConnection(CloseConnectionAction())

            client.use {
                val exception = assertThrows<ServerConnectionException> {
                    runBlocking { client.collectParcels(arrayOf(signer)).first() }
                }

                assertEquals(
                    "Server closed the connection during the handshake",
                    exception.message
                )
                assertTrue(exception.cause is ClosedReceiveChannelException)
            }

            waitForConnectionClosure()
            assertEquals(CloseReason.Codes.NORMAL, listener.closingCode)
        }

        @Test
        fun `Getting an invalid challenge should throw an exception`() {
            addServerConnection(SendTextMessageAction("Not a valid challenge"))

            client.use {
                val exception = assertThrows<ServerBindingException> {
                    runBlocking { client.collectParcels(arrayOf(signer)).first() }
                }

                assertEquals("Server sent an invalid handshake challenge", exception.message)
                assertTrue(exception.cause is InvalidMessageException)
            }

            waitForConnectionClosure()
            assertEquals(CloseReason.Codes.VIOLATED_POLICY, listener.closingCode)
        }

        @Test
        fun `At least one nonce signer should be required`() {
            addServerConnection()

            client.use {
                val exception = assertThrows<NonceSignerException> {
                    runBlocking { client.collectParcels(emptyArray()).first() }
                }

                assertEquals("At least one nonce signer must be specified", exception.message)
            }

            assertFalse(listener.connected)
        }

        @Test
        fun `Challenge nonce should be signed with each signer`() {
            addServerConnection(ChallengeAction(nonce), CloseConnectionAction())

            val signer2 = generateDummySigner()

            client.use {
                runBlocking { client.collectParcels(arrayOf(signer, signer2)).toList() }
            }

            waitForConnectionClosure()

            assertEquals(1, listener.receivedMessages.size)
            val response = HandshakeResponse.deserialize(listener.receivedMessages.first())
            assertEquals(2, response.nonceSignatures.size)
            response.nonceSignatures.forEach {
                DetachedSignatureType.NONCE.verify(
                    it,
                    nonce,
                    listOf(PDACertPath.PRIVATE_GW)
                )
            }
        }
    }

    @Test
    fun `Call should return if server closed connection normally after the handshake`(): Unit =
        runBlocking {
            addServerConnection(ChallengeAction(nonce), CloseConnectionAction())

            client.use {
                client.collectParcels(arrayOf(signer)).collect { }
            }

            waitForConnectionClosure()
            assertEquals(CloseReason.Codes.NORMAL, listener.closingCode)
        }

    @Nested
    inner class ServerInitiatedClosure {
        @Test
        fun `Exception should be thrown if mode is close-upon-completion`() = runTest {
            val code = CloseReason.Codes.VIOLATED_POLICY
            val reason = "Whoops"
            addServerConnection(ChallengeAction(nonce), CloseConnectionAction(code, reason))

            client.use {
                val exception = assertThrows<ServerConnectionException> {
                    client.collectParcels(
                        arrayOf(signer),
                        StreamingMode.CloseUponCompletion
                    ).toList()
                }

                assertEquals(
                    "Server closed the connection unexpectedly " +
                            "(code: ${code.code}, reason: $reason)",
                    exception.message
                )
            }
        }

        @Test
        fun `Exception should be thrown if mode is Keep-Alive and code is not INTERNAL_ERROR`() =
            runTest {
                val code = CloseReason.Codes.VIOLATED_POLICY
                val reason = "Whoops"
                addServerConnection(ChallengeAction(nonce), CloseConnectionAction(code, reason))

                client.use {
                    val exception = assertThrows<ServerConnectionException> {
                        client.collectParcels(
                            arrayOf(signer),
                            StreamingMode.KeepAlive
                        ).toList()
                    }

                    assertEquals(
                        "Server closed the connection unexpectedly " +
                                "(code: ${code.code}, reason: $reason)",
                        exception.message
                    )
                }
            }

        @Test
        fun `Should be reconnected if mode is Keep-Alive and code is INTERNAL_ERROR`() = runTest {
            // The server should end the first connection should end abruptly
            addServerConnection(
                ChallengeAction(nonce),
                CloseConnectionAction(CloseReason.Codes.INTERNAL_ERROR)
            )
            // The second connection should be closed normally
            addServerConnection(
                ChallengeAction(nonce),
                CloseConnectionAction(CloseReason.Codes.NORMAL)
            )

            client.use {
                client.collectParcels(
                    arrayOf(signer),
                    StreamingMode.KeepAlive
                ).toList()

                waitForConnectionClosure()
            }
        }
    }

    @Test
    fun `Cancelling the flow should close the connection normally`(): Unit = runBlocking {
        val undeliveredAction =
            ParcelDeliveryAction("second delivery id", "second parcel".toByteArray())
        addServerConnection(
            ChallengeAction(nonce),
            ParcelDeliveryAction(deliveryId, parcelSerialized),
            undeliveredAction
        )

        client.use {
            val deliveries = client.collectParcels(arrayOf(signer)).take(1).toList()

            assertEquals(1, deliveries.size)
        }

        waitForConnectionClosure()
        assertEquals(CloseReason.Codes.NORMAL, listener.closingCode)
        assertFalse(undeliveredAction.wasRun)
    }

    @Test
    fun `Malformed deliveries should be refused`(): Unit = runBlocking {
        addServerConnection(ChallengeAction(nonce), SendTextMessageAction("invalid"))

        client.use {
            val exception = assertThrows<ServerBindingException> {
                runBlocking { client.collectParcels(arrayOf(signer)).toList() }
            }

            assertEquals("Received invalid message from server", exception.message)
            assertTrue(exception.cause is InvalidMessageException)
        }

        waitForConnectionClosure()
        assertEquals(CloseReason.Codes.VIOLATED_POLICY, listener.closingCode!!)
        assertEquals("Invalid parcel delivery", listener.closingReason!!)
    }

    @Nested
    inner class StreamingModeHeader {
        @Test
        fun `Streaming mode should be Keep-Alive by default`(): Unit = runBlocking {
            addServerConnection(ChallengeAction(nonce), CloseConnectionAction())

            client.use {
                client.collectParcels(arrayOf(signer)).toList()
            }

            waitForConnectionClosure()
            assertEquals(
                StreamingMode.KeepAlive.headerValue,
                listener.request!!.header(StreamingMode.HEADER_NAME)
            )
        }

        @Test
        fun `Streaming mode can be changed on request`(): Unit = runBlocking {
            addServerConnection(ChallengeAction(nonce), CloseConnectionAction())

            client.use {
                client.collectParcels(arrayOf(signer), StreamingMode.CloseUponCompletion).toList()
            }

            waitForConnectionClosure()
            assertEquals(
                StreamingMode.CloseUponCompletion.headerValue,
                listener.request!!.header(StreamingMode.HEADER_NAME)
            )
        }
    }

    @Nested
    inner class Collector {
        @Test
        fun `No collectors should be output if the server doesn't deliver anything`(): Unit =
            runBlocking {
                addServerConnection(ChallengeAction(nonce), CloseConnectionAction())

                client.use {
                    val deliveries = client.collectParcels(arrayOf(signer)).toList()

                    assertEquals(0, deliveries.size)
                }
            }

        @Test
        fun `One collector should be output if there is one delivery`(): Unit =
            runBlocking {
                addServerConnection(
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
        fun `Multiple collectors should be output if there are multiple deliveries`(): Unit =
            runBlocking {
                val parcelSerialized2 = "second parcel".toByteArray()
                addServerConnection(
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
    }

    @Nested
    inner class CollectorTrustedCerts {
        @Test
        fun `Collector should use trusted certificates from nonce signers`() = runBlocking {
            addServerConnection(
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
                    listOf(signer.certificate),
                    deliveries.first().trustedCertificates.toList()
                )
            }
        }
    }

    @Nested
    inner class CollectorACK {
        @Test
        fun `Each ACK should be passed on to the server`(): Unit = runTest {
            addServerConnection(
                ChallengeAction(nonce),
                ParcelDeliveryAction(deliveryId, parcelSerialized),
                CloseConnectionAction()
            )

            client.use {
                client.collectParcels(arrayOf(signer)).collect { it.ack() }
            }

            waitForConnectionClosure()
            // The server should've got two messages: The handshake response and the ACK
            assertEquals(2, listener.receivedMessages.size)
            assertEquals(
                deliveryId,
                listener.receivedMessages[1].toString(Charset.defaultCharset())
            )
        }

        @Test
        fun `Missing ACKs should be honored`(): Unit = runBlocking {
            // The server will deliver 2 parcels but the client will only ACK the first one
            val additionalParcelDelivery =
                ParcelDeliveryAction("second delivery id", "parcel".toByteArray())
            addServerConnection(
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

            waitForConnectionClosure()
            // The server should've got two messages: The handshake response and the first ACK
            assertEquals(2, listener.receivedMessages.size)
            assertEquals(
                deliveryId,
                listener.receivedMessages[1].toString(Charset.defaultCharset())
            )
            assertTrue(additionalParcelDelivery.wasRun)
        }
    }

    private fun generateDummySigner(): Signer {
        val keyPair = generateRSAKeyPair()
        val certificate = issueEndpointCertificate(
            keyPair.public,
            KeyPairSet.PRIVATE_GW.private,
            PDACertPath.PRIVATE_GW.expiryDate,
            PDACertPath.PRIVATE_GW
        )
        return Signer(certificate, keyPair.private)
    }
}
