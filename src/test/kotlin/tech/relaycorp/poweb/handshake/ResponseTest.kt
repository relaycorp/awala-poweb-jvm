package tech.relaycorp.poweb.handshake

import com.google.protobuf.ByteString
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import tech.relaycorp.poweb.internal.protobuf_messages.handshake.Response as PBResponse

class ResponseTest {
    val signature1 = "signature1".toByteArray()
    val signature2 = "signature2".toByteArray()

    @Nested
    inner class Serialize {
        @Test
        fun `Response with no signatures`() {
            val response = Response(arrayOf())

            val serialization = response.serialize()

            val pbResponse = PBResponse.parseFrom(serialization)
            assertEquals(0, pbResponse.gatewayNonceSignaturesCount)
        }

        @Test
        fun `Response with one signature`() {
            val response = Response(arrayOf(signature1))

            val serialization = response.serialize()

            val pbResponse = PBResponse.parseFrom(serialization)
            assertEquals(1, pbResponse.gatewayNonceSignaturesCount)
            assertEquals(
                    listOf(signature1.asList()),
                    pbResponse.gatewayNonceSignaturesList.map { it.toByteArray().asList() }
            )
        }

        @Test
        fun `Response with two signatures`() {
            val response = Response(arrayOf(signature1, signature2))

            val serialization = response.serialize()

            val pbResponse = PBResponse.parseFrom(serialization)
            assertEquals(2, pbResponse.gatewayNonceSignaturesCount)
            assertEquals(
                    listOf(signature1.asList(), signature2.asList()),
                    pbResponse.gatewayNonceSignaturesList.map { it.toByteArray().asList() }
            )
        }
    }

    @Nested
    inner class Deserialize {
        @Test
        fun `Invalid serialization should be refused`() {
            val serialization = "This is invalid".toByteArray()

            val exception = assertThrows<InvalidResponseException> {
                Response.deserialize(serialization)
            }

            assertEquals("Message is not a valid response", exception.message)
        }

        @Test
        fun `Valid serialization with no signatures should be accepted`() {
            val serialization = PBResponse.newBuilder().build().toByteArray()

            val response = Response.deserialize(serialization)

            assertEquals(0, response.nonceSignatures.size)
        }

        @Test
        fun `Valid serialization with one signature should be accepted`() {
            val serialization = PBResponse.newBuilder()
                    .addGatewayNonceSignatures(ByteString.copyFrom(signature1))
                    .build()
                    .toByteArray()

            val response = Response.deserialize(serialization)

            assertEquals(signature1.asList(), response.nonceSignatures.first().asList())
        }

        @Test
        fun `Valid serialization with multiple signatures should be accepted`() {
            val serialization = PBResponse.newBuilder()
                    .addAllGatewayNonceSignatures(mutableListOf(
                            ByteString.copyFrom(signature1),
                            ByteString.copyFrom(signature2))
                    )
                    .build().toByteArray()

            val response = Response.deserialize(serialization)

            assertEquals(signature1.asList(), response.nonceSignatures.first().asList())
        }
    }
}
