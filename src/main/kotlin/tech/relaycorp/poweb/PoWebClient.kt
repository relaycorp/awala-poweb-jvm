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
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import tech.relaycorp.poweb.handshake.Challenge
import tech.relaycorp.poweb.handshake.InvalidMessageException
import tech.relaycorp.poweb.handshake.NonceSigner
import tech.relaycorp.poweb.handshake.Response
import tech.relaycorp.relaynet.messages.control.ParcelDelivery
import java.io.Closeable

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
        wsConnect("/TODO") {
            handshake(nonceSigners)
            try {
                for (frame in incoming) {
                    val delivery = ParcelDelivery.deserialize(frame.readBytes())
                    emit(ParcelCollector(delivery.parcelSerialized))
                }
            } catch (exc: ClosedReceiveChannelException) {
                val reason = closeReason.await()
                if (reason != null && reason.code != CloseReason.Codes.NORMAL.code) {
                    throw PoWebException(
                        "Server closed the connection unexpectedly " +
                            "(code: ${reason.code}, reason: ${reason.message})"
                    )
                }
            }
        }
    }

    internal suspend fun wsConnect(
        path: String,
        block: suspend DefaultClientWebSocketSession.() -> Unit
    ) = ktorClient.webSocket(
        HttpMethod.Get,
        hostName, port, path,
        { url(if (useTls) "wss" else "ws", hostName, port, path) },
        block
    )

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
    val challengeRaw = try {
        incoming.receive()
    } catch (exc: ClosedReceiveChannelException) {
        throw PoWebException("Server closed the connection before the handshake", exc)
    }
    val challenge = try {
        Challenge.deserialize(challengeRaw.readBytes())
    } catch (exc: InvalidMessageException) {
        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, ""))
        throw PoWebException("Server sent an invalid handshake challenge", exc)
    }
    val nonceSignatures = nonceSigners.map { it.sign(challenge.nonce) }.toTypedArray()
    val response = Response(nonceSignatures)
    outgoing.send(Frame.Binary(true, response.serialize()))
}
