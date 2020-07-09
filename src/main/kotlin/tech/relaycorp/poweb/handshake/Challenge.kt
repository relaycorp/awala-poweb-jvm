package tech.relaycorp.poweb.handshake

import com.google.protobuf.ByteString
import com.google.protobuf.InvalidProtocolBufferException
import tech.relaycorp.poweb.internal.protobuf_messages.handshake.Challenge as PBChallenge

class Challenge(val nonce: ByteArray) {
    fun serialize(): ByteArray {
        val pbChallenge = PBChallenge.newBuilder()
                .setGatewayNonce(ByteString.copyFrom(nonce)).build()
        return pbChallenge.toByteArray()
    }

    companion object {
        fun deserialize(serialization: ByteArray): Challenge {
            val pbChallenge = try {
                PBChallenge.parseFrom(serialization)
            } catch (_: InvalidProtocolBufferException) {
                throw InvalidMessageException("Message is not a valid challenge")
            }
            return Challenge(pbChallenge.gatewayNonce.toByteArray())
        }
    }
}
