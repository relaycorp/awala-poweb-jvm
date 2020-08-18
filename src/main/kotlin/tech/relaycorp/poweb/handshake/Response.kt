package tech.relaycorp.poweb.handshake

import com.google.protobuf.ByteString
import com.google.protobuf.InvalidProtocolBufferException
import tech.relaycorp.poweb.internal.protobuf_messages.handshake.Response as PBResponse

public class Response(public val nonceSignatures: Array<ByteArray>) {
    public fun serialize(): ByteArray {
        val pbResponse = PBResponse.newBuilder()
                .addAllGatewayNonceSignatures(nonceSignatures.map { ByteString.copyFrom(it) })
                .build()
        return pbResponse.toByteArray()
    }

    public companion object {
        public fun deserialize(serialization: ByteArray): Response {
            val pbResponse = try {
                PBResponse.parseFrom(serialization)
            } catch (_: InvalidProtocolBufferException) {
                throw InvalidMessageException("Message is not a valid response")
            }
            val nonceSignatures = pbResponse.gatewayNonceSignaturesList.map { it.toByteArray() }
            return Response(nonceSignatures.toTypedArray())
        }
    }
}
