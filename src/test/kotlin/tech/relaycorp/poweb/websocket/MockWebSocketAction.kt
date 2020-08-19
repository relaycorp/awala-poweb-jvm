package tech.relaycorp.poweb.websocket

import io.ktor.http.cio.websocket.CloseReason
import okhttp3.WebSocket
import okio.ByteString.Companion.toByteString
import tech.relaycorp.poweb.handshake.Challenge
import tech.relaycorp.relaynet.messages.control.ParcelDelivery

sealed class MockWebSocketAction {
    var wasRun = false

    open fun run(webSocket: WebSocket) {
        wasRun = true
    }
}

open class SendBinaryMessageAction(private val message: ByteArray) : MockWebSocketAction() {
    override fun run(webSocket: WebSocket) {
        webSocket.send(message.toByteString())
        super.run(webSocket)
    }
}

class SendTextMessageAction(private val message: String) : MockWebSocketAction() {
    override fun run(webSocket: WebSocket) {
        webSocket.send(message)
        super.run(webSocket)
    }
}

class ChallengeAction(nonce: ByteArray) : SendBinaryMessageAction(Challenge(nonce).serialize())

class ParcelDeliveryAction(deliveryId: String, parcelSerialized: ByteArray) :
    SendBinaryMessageAction(ParcelDelivery(deliveryId, parcelSerialized).serialize())

class CloseConnectionAction(
    private val code: CloseReason.Codes = CloseReason.Codes.NORMAL,
    private val reason: String? = null
) : MockWebSocketAction() {
    override fun run(webSocket: WebSocket) {
        webSocket.close(code.code.toInt(), reason)
        super.run(webSocket)
    }
}

class ActionSequence(private vararg val actions: MockWebSocketAction) : MockWebSocketAction() {
    override fun run(webSocket: WebSocket) {
        actions.forEach { it.run(webSocket) }
        super.run(webSocket)
    }
}
