package tech.relaycorp.poweb.websocket

import okhttp3.WebSocket
import okio.ByteString.Companion.toByteString
import tech.relaycorp.poweb.handshake.Challenge
import tech.relaycorp.relaynet.messages.control.ParcelDelivery

sealed class MockWebSocketAction {
    abstract fun run(webSocket: WebSocket)
}

open class SendBinaryMessageAction(private val message: ByteArray) : MockWebSocketAction() {
    override fun run(webSocket: WebSocket) {
        webSocket.send(message.toByteString())
    }
}

class SendTextMessageAction(private val message: String) : MockWebSocketAction() {
    override fun run(webSocket: WebSocket) {
        webSocket.send(message)
    }
}

class ChallengeAction(nonce: ByteArray) : SendBinaryMessageAction(Challenge(nonce).serialize())

class ParcelDeliveryAction(deliveryId: String, parcelSerialized: ByteArray) :
    SendBinaryMessageAction(ParcelDelivery(deliveryId, parcelSerialized).serialize())

class CloseConnectionAction(
    private val code: Int,
    private val reason: String? = null
) : MockWebSocketAction() {
    override fun run(webSocket: WebSocket) {
        webSocket.close(code, reason)
    }
}

class ActionSequence(private vararg val actions: MockWebSocketAction) : MockWebSocketAction() {
    override fun run(webSocket: WebSocket) {
        actions.forEach { it.run(webSocket) }
    }
}
