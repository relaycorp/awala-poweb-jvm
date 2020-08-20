package tech.relaycorp.poweb

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.features.websocket.DefaultClientWebSocketSession
import io.ktor.client.features.websocket.WebSockets
import io.ktor.client.features.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.http.cio.websocket.CloseReason
import io.ktor.http.cio.websocket.Frame
import io.ktor.http.cio.websocket.close
import io.ktor.http.cio.websocket.readBytes
import io.ktor.util.KtorExperimentalAPI
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import tech.relaycorp.poweb.handshake.Challenge
import tech.relaycorp.poweb.handshake.InvalidChallengeException
import tech.relaycorp.poweb.handshake.NonceSigner
import tech.relaycorp.poweb.handshake.Response
import tech.relaycorp.relaynet.messages.InvalidMessageException
import tech.relaycorp.relaynet.messages.control.ParcelDelivery
import java.io.Closeable
import java.io.EOFException
import java.net.ConnectException

/**
 * PoWeb client.
 *
 * @param hostName The IP address or domain for the PoWeb server
 * @param port The port for the PoWeb server
 * @param useTls Whether the PoWeb server uses TLS
 *
 * The underlying connection is created lazily.
 */
@KtorExperimentalAPI
public class PoWebClient internal constructor(
    internal val hostName: String,
    internal val port: Int,
    internal val useTls: Boolean
) : Closeable {
    internal var ktorClient = HttpClient(OkHttp) {
        install(WebSockets)
    }

    private val wsScheme = if (useTls) "wss" else "ws"

    /**
     * Close the underlying connection to the server (if any).
     */
    override fun close(): Unit = ktorClient.close()

    /**
     * Collect parcels on behalf of the specified nodes.
     *
     * @param nonceSigners The nonce signers for each node whose parcels should be collected
     * @param streamingMode Which streaming mode to ask the server to use
     */
    @Throws(
        ServerConnectionException::class,
        InvalidServerMessageException::class,
        NonceSignerException::class
    )
    public suspend fun collectParcels(
        nonceSigners: Array<NonceSigner>,
        streamingMode: StreamingMode = StreamingMode.KeepAlive
    ): Flow<ParcelCollector> = flow {
        if (nonceSigners.isEmpty()) {
            throw NonceSignerException("At least one nonce signer must be specified")
        }

        val streamingModeHeader = Pair(StreamingMode.HEADER_NAME, streamingMode.headerValue)
        wsConnect(PARCEL_COLLECTION_ENDPOINT_PATH, listOf(streamingModeHeader)) {
            try {
                handshake(nonceSigners)
            } catch (exc: ClosedReceiveChannelException) {
                throw ServerConnectionException(
                    "Server closed the connection during the handshake",
                    exc
                )
            }
            collectAndAckParcels(this, this@flow)

            // The server must've closed the connection for us to get here, since we're consuming
            // all incoming messages indefinitely.
            val reason = closeReason.await()!!
            if (reason.code != CloseReason.Codes.NORMAL.code) {
                throw ServerConnectionException(
                    "Server closed the connection unexpectedly " +
                        "(code: ${reason.code}, reason: ${reason.message})"
                )
            }
        }
    }

    @Throws(PoWebException::class)
    private suspend fun collectAndAckParcels(
        webSocketSession: DefaultClientWebSocketSession,
        flowCollector: FlowCollector<ParcelCollector>
    ) {
        for (frame in webSocketSession.incoming) {
            val delivery = try {
                ParcelDelivery.deserialize(frame.readBytes())
            } catch (exc: InvalidMessageException) {
                webSocketSession.close(
                    CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Invalid parcel delivery")
                )
                throw InvalidServerMessageException("Received invalid message from server", exc)
            }
            val collector = ParcelCollector(delivery.parcelSerialized) {
                webSocketSession.outgoing.send(Frame.Text(delivery.deliveryId))
            }
            flowCollector.emit(collector)
        }
    }

    internal suspend fun wsConnect(
        path: String,
        headers: List<Pair<String, String>>? = null,
        block: suspend DefaultClientWebSocketSession.() -> Unit
    ) = try {
        ktorClient.webSocket(
            "$wsScheme://$hostName:$port$path",
            { headers?.forEach { header(it.first, it.second) } },
            block
        )
    } catch (exc: ConnectException) {
        throw ServerConnectionException("Server is unreachable", exc)
    } catch (exc: EOFException) {
        throw ServerConnectionException("Connection was closed abruptly", exc)
    }

    public companion object {
        internal const val PARCEL_COLLECTION_ENDPOINT_PATH = "/v1/parcel-collection"

        private const val DEFAULT_LOCAL_PORT = 276
        private const val DEFAULT_REMOTE_PORT = 443

        /**
         * Connect to a private gateway from a private endpoint.
         *
         * @param port The port for the PoWeb server
         *
         * TLS won't be used.
         */
        public fun initLocal(port: Int = DEFAULT_LOCAL_PORT): PoWebClient =
            PoWebClient("127.0.0.1", port, false)

        /**
         * Connect to a public gateway from a private gateway via TLS.
         *
         * @param hostName The IP address or domain for the PoWeb server
         * @param port The port for the PoWeb server
         */
        public fun initRemote(hostName: String, port: Int = DEFAULT_REMOTE_PORT): PoWebClient =
            PoWebClient(hostName, port, true)
    }
}

@Throws(PoWebException::class)
internal suspend fun DefaultClientWebSocketSession.handshake(nonceSigners: Array<NonceSigner>) {
    val challengeRaw = incoming.receive()
    val challenge = try {
        Challenge.deserialize(challengeRaw.readBytes())
    } catch (exc: InvalidChallengeException) {
        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, ""))
        throw InvalidServerMessageException("Server sent an invalid handshake challenge", exc)
    }
    val nonceSignatures = nonceSigners.map { it.sign(challenge.nonce) }.toTypedArray()
    val response = Response(nonceSignatures)
    outgoing.send(Frame.Binary(true, response.serialize()))
}
