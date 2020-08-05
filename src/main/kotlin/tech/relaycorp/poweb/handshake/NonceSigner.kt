package tech.relaycorp.poweb.handshake

import tech.relaycorp.relaynet.crypto.SignedData
import tech.relaycorp.relaynet.wrappers.x509.Certificate
import java.security.PrivateKey

class NonceSigner(internal val certificate: Certificate, private val privateKey: PrivateKey) {
    fun sign(nonce: ByteArray): ByteArray {
        val signedData = SignedData.sign(
            nonce,
            privateKey,
            certificate,
            setOf(certificate),
            encapsulatePlaintext = false
        )
        return signedData.serialize()
    }
}
