package tech.relaycorp.poweb.handshake

import com.google.protobuf.ByteString
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import tech.relaycorp.poweb.internal.protobuf_messages.handshake.Challenge as PBChallenge

class ChallengeTest {
    val nonce = "the-nonce".toByteArray()

    @Nested
    inner class Serialize {
        @Test
        fun `Message should be serialized`() {
            val challenge = Challenge(nonce)

            val serialization = challenge.serialize()

            val pbChallenge = PBChallenge.parseFrom(serialization)
            assertEquals(nonce.asList(), pbChallenge.gatewayNonce.toByteArray().asList())
        }
    }

    @Nested
    inner class Deserialize {
        @Test
        fun `Invalid serialization should be refused`() {
            val serialization = "This is invalid".toByteArray()

            val exception = assertThrows<InvalidMessageException> {
                Challenge.deserialize(serialization)
            }

            assertEquals("Message is not a valid challenge", exception.message)
        }

        @Test
        fun `Valid serialization should be accepted`() {
            val serialization = PBChallenge.newBuilder()
                    .setGatewayNonce(ByteString.copyFrom(nonce)).build().toByteArray()

            val challenge = Challenge.deserialize(serialization)

            assertEquals(nonce.asList(), challenge.nonce.asList())
        }
    }
}
