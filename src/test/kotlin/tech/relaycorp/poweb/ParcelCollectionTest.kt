package tech.relaycorp.poweb

import io.ktor.http.cio.websocket.CloseReason
import io.ktor.util.KtorExperimentalAPI
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tech.relaycorp.poweb.websocket.ActionSequence
import tech.relaycorp.poweb.websocket.ChallengeAction
import tech.relaycorp.poweb.websocket.CloseConnectionAction
import tech.relaycorp.poweb.websocket.MockKtorClientManager
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
import tech.relaycorp.relaynet.testing.CertificationPath
import tech.relaycorp.relaynet.testing.KeyPairSet
import tech.relaycorp.relaynet.wrappers.generateRSAKeyPair
import java.nio.charset.Charset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@KtorExperimentalAPI
class ParcelCollectionTest : WebSocketTestCase() {
    private val nonce = "nonce".toByteArray()

    // Compute client on demand because getting the server port will start the server
    private val client by lazy { PoWebClient.initLocal(mockWebServer.port) }

    private val signer =
        Signer(CertificationPath.PRIVATE_ENDPOINT, KeyPairSet.PRIVATE_ENDPOINT.private)

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

    @Nested
    inner class Handshake {
        @Test
        fun `Server closing connection during handshake should throw exception`() {
            setListenerActions(CloseConnectionAction())

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

            awaitForConnectionClosure()
            assertEquals(CloseReason.Codes.NORMAL, listener!!.closingCode)
        }

        @Test
        fun `Getting an invalid challenge should throw an exception`() {
            setListenerActions(SendTextMessageAction("Not a valid challenge"))

            client.use {
                val exception = assertThrows<ServerBindingException> {
                    runBlocking { client.collectParcels(arrayOf(signer)).first() }
                }

                assertEquals("Server sent an invalid handshake challenge", exception.message)
                assertTrue(exception.cause is InvalidMessageException)
            }

            awaitForConnectionClosure()
            assertEquals(CloseReason.Codes.VIOLATED_POLICY, listener!!.closingCode)
        }

        @Test
        fun `At least one nonce signer should be required`() {
            setListenerActions()

            client.use {
                val exception = assertThrows<NonceSignerException> {
                    runBlocking { client.collectParcels(emptyArray()).first() }
                }

                assertEquals("At least one nonce signer must be specified", exception.message)
            }

            assertFalse(listener!!.connected)
        }

        @Test
        fun `Challenge nonce should be signed with each signer`() {
            setListenerActions(ChallengeAction(nonce), CloseConnectionAction())

            val signer2 = generateDummySigner()

            client.use {
                runBlocking { client.collectParcels(arrayOf(signer, signer2)).toList() }
            }

            awaitForConnectionClosure()

            assertEquals(1, listener!!.receivedMessages.size)
            val response = HandshakeResponse.deserialize(listener!!.receivedMessages.first())
            assertEquals(2, response.nonceSignatures.size)
            response.nonceSignatures.forEach {
                DetachedSignatureType.NONCE.verify(
                    it,
                    nonce,
                    listOf(CertificationPath.PRIVATE_GW)
                )
            }
        }
    }

    @Test
    fun `Call should return if server closed connection normally after the handshake`(): Unit =
        runBlocking {
            setListenerActions(ChallengeAction(nonce), CloseConnectionAction())

            client.use {
                client.collectParcels(arrayOf(signer)).collect { }
            }

            awaitForConnectionClosure()
            assertEquals(CloseReason.Codes.NORMAL, listener!!.closingCode)
        }

    @Test
    fun `Exception should be thrown if server closes connection with error`(): Unit =
        runBlocking {
            val code = CloseReason.Codes.VIOLATED_POLICY
            val reason = "Whoops"
            setListenerActions(ChallengeAction(nonce), CloseConnectionAction(code, reason))

            client.use {
                val exception = assertThrows<ServerConnectionException> {
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

        awaitForConnectionClosure()
        assertEquals(CloseReason.Codes.NORMAL, listener!!.closingCode)
        assertFalse(undeliveredAction.wasRun)
    }

    @Test
    fun `Malformed deliveries should be refused`(): Unit = runBlocking {
        setListenerActions(ChallengeAction(nonce), SendTextMessageAction("invalid"))

        client.use {
            val exception = assertThrows<ServerBindingException> {
                runBlocking { client.collectParcels(arrayOf(signer)).toList() }
            }

            assertEquals("Received invalid message from server", exception.message)
            assertTrue(exception.cause is InvalidMessageException)
        }

        awaitForConnectionClosure()
        assertEquals(CloseReason.Codes.VIOLATED_POLICY, listener!!.closingCode!!)
        assertEquals("Invalid parcel delivery", listener!!.closingReason!!)
    }

    @Nested
    inner class StreamingModeHeader {
        @Test
        fun `Streaming mode should be Keep-Alive by default`(): Unit = runBlocking {
            setListenerActions(ChallengeAction(nonce), CloseConnectionAction())

            client.use {
                client.collectParcels(arrayOf(signer)).toList()
            }

            awaitForConnectionClosure()
            assertEquals(
                StreamingMode.KeepAlive.headerValue,
                listener!!.request!!.header(StreamingMode.HEADER_NAME)
            )
        }

        @Test
        fun `Streaming mode can be changed on request`(): Unit = runBlocking {
            setListenerActions(ChallengeAction(nonce), CloseConnectionAction())

            client.use {
                client.collectParcels(arrayOf(signer), StreamingMode.CloseUponCompletion).toList()
            }

            awaitForConnectionClosure()
            assertEquals(
                StreamingMode.CloseUponCompletion.headerValue,
                listener!!.request!!.header(StreamingMode.HEADER_NAME)
            )
        }
    }

    @Nested
    inner class Collector {
        @Test
        fun `No collectors should be output if the server doesn't deliver anything`(): Unit =
            runBlocking {
                setListenerActions(ChallengeAction(nonce), CloseConnectionAction())

                client.use {
                    val deliveries = client.collectParcels(arrayOf(signer)).toList()

                    assertEquals(0, deliveries.size)
                }
            }

        @Test
        fun `One collector should be output if there is one delivery`(): Unit =
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
        fun `Multiple collectors should be output if there are multiple deliveries`(): Unit =
            runBlocking {
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
    }

    @Nested
    inner class CollectorTrustedCerts {
        @Test
        fun `Collector should use trusted certificates from nonce signers`() = runBlocking {
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
                    listOf(signer.certificate),
                    deliveries.first().trustedCertificates.toList()
                )
            }
        }
    }

    @Nested
    inner class CollectorACK {
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

            awaitForConnectionClosure()
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

            awaitForConnectionClosure()
            // The server should've got two messages: The handshake response and the first ACK
            assertEquals(2, listener!!.receivedMessages.size)
            assertEquals(
                deliveryId,
                listener!!.receivedMessages[1].toString(Charset.defaultCharset())
            )
            assertTrue(additionalParcelDelivery.wasRun)
        }
    }

    private fun generateDummySigner(): Signer {
        val keyPair = generateRSAKeyPair()
        val certificate = issueEndpointCertificate(
            keyPair.public,
            KeyPairSet.PRIVATE_GW.private,
            CertificationPath.PRIVATE_GW.expiryDate,
            CertificationPath.PRIVATE_GW
        )
        return Signer(certificate, keyPair.private)
    }
}
