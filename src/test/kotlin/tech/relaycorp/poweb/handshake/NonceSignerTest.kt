package tech.relaycorp.poweb.handshake

import org.junit.jupiter.api.Test
import tech.relaycorp.relaynet.crypto.SignedData
import tech.relaycorp.relaynet.issueEndpointCertificate
import tech.relaycorp.relaynet.wrappers.generateRSAKeyPair
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NonceSignerTest {
    private val nonce = "The nonce".toByteArray()
    private val keyPair = generateRSAKeyPair()
    private val certificate = issueEndpointCertificate(
        keyPair.public,
        keyPair.private,
        ZonedDateTime.now().plusDays(1)
    )
    private val signer = NonceSigner(certificate, keyPair.private)

    @Test
    fun `Plaintext should not be embedded`() {
        val serialization = signer.sign(nonce)

        val signedData = SignedData.deserialize(serialization)
        assertNull(signedData.plaintext)
    }

    @Test
    fun `Signer certificate should be attached`() {
        val serialization = signer.sign(nonce)

        val signedData = SignedData.deserialize(serialization)
        assertEquals(certificate, signedData.signerCertificate)
    }

    @Test
    fun `Signature should verify`() {
        val serialization = signer.sign(nonce)

        val signedData = SignedData.deserialize(serialization)
        signedData.verify(nonce)
    }
}
