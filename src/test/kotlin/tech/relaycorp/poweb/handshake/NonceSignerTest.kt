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

        val cmsSignedData = SignedData.deserialize(serialization)

        assertNull(cmsSignedData.plaintext)
    }

    @Test
    fun `Signer certificate should be attached`() {
        val serialization = signer.sign(nonce)

        val cmsSignedData = SignedData.deserialize(serialization)

        assertEquals(1, cmsSignedData.attachedCertificates.size)
        assertEquals(certificate, cmsSignedData.attachedCertificates.first())
    }

    @Test
    fun `Signature should verify`() {
        val serialization = signer.sign(nonce)

        val signedData = SignedData.deserialize(serialization)
        signedData.verify(nonce)

        assertEquals(certificate, signedData.signerCertificate)
    }
}
