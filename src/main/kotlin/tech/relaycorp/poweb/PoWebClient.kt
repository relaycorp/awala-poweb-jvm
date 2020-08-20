package tech.relaycorp.poweb

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.features.websocket.DefaultClientWebSocketSession
import io.ktor.client.features.websocket.WebSockets
import io.ktor.client.features.websocket.webSocket
import io.ktor.client.request.url
import io.ktor.http.HttpMethod
import io.ktor.http.cio.websocket.CloseReason
import io.ktor.http.cio.websocket.Frame
import io.ktor.http.cio.websocket.close
import io.ktor.http.cio.websocket.readBytes
import io.ktor.util.KtorExperimentalAPI
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import tech.relaycorp.poweb.handshake.Challenge
import tech.relaycorp.poweb.handshake.NonceSigner
import tech.relaycorp.poweb.handshake.Response
import tech.relaycorp.relaynet.messages.InvalidMessageException
import tech.relaycorp.relaynet.messages.control.ParcelDelivery
import java.io.Closeable
import java.io.EOFException
import java.net.ConnectException

@KtorExperimentalAPI
public class PoWebClient internal constructor(
    internal val hostName: String,
    internal val port: Int,
    internal val useTls: Boolean
) : Closeable {
    internal var ktorClient = HttpClient(OkHttp) {
        install(WebSockets)
    }

    override fun close(): Unit = ktorClient.close()

    @Throws(PoWebException::class)
    public suspend fun collectParcels(
        nonceSigners: Array<NonceSigner>
    ): Flow<ParcelCollector> = flow {
        wsConnect("/v1/parcel-collection") {
            handshake(nonceSigners)
            collectAndAckParcels(this, this@flow)

            // The server must've closed the connection for us to get here, since we're consuming
            // all incoming messages indefinitely.
            val reason = closeReason.await()!!
            if (reason.code != CloseReason.Codes.NORMAL.code) {
                throw PoWebException(
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
                throw PoWebException("Received invalid message from server", exc)
            }
            val collector = ParcelCollector(delivery.parcelSerialized) {
                webSocketSession.outgoing.send(Frame.Text(delivery.deliveryId))
            }
            flowCollector.emit(collector)
        }
    }

    internal suspend fun wsConnect(
        path: String,
        block: suspend DefaultClientWebSocketSession.() -> Unit
    ) = try {
        ktorClient.webSocket(
            HttpMethod.Get,
            hostName,
            port,
            path,
            { url(if (useTls) "wss" else "ws", hostName, port, path) },
            block
        )
    } catch (exc: ConnectException) {
        throw PoWebException("Server is unreachable", exc)
    } catch (exc: EOFException) {
        throw PoWebException("Connection was closed abruptly", exc)
    }

    public companion object {
        private const val defaultLocalPort = 276
        private const val defaultRemotePort = 443

        public fun initLocal(port: Int = defaultLocalPort): PoWebClient =
            PoWebClient("127.0.0.1", port, false)

        public fun initRemote(hostName: String, port: Int = defaultRemotePort): PoWebClient =
            PoWebClient(hostName, port, true)
    }
}

@Throws(PoWebException::class)
internal suspend fun DefaultClientWebSocketSession.handshake(nonceSigners: Array<NonceSigner>) {
    if (nonceSigners.isEmpty()) {
        throw PoWebException("At least one nonce signer must be specified")
    }
    val challengeRaw = incoming.receive()
    val challenge = try {
        Challenge.deserialize(challengeRaw.readBytes())
    } catch (exc: tech.relaycorp.poweb.handshake.InvalidMessageException) { // TODO:
        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, ""))
        throw PoWebException("Server sent an invalid handshake challenge", exc)
    }
    val nonceSignatures = nonceSigners.map { it.sign(challenge.nonce) }.toTypedArray()
    val response = Response(nonceSignatures)
    outgoing.send(Frame.Binary(true, response.serialize()))
}
