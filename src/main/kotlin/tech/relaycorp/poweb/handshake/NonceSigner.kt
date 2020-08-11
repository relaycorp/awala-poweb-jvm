package tech.relaycorp.poweb.handshake

import tech.relaycorp.relaynet.messages.control.NonceSignature
import tech.relaycorp.relaynet.wrappers.x509.Certificate
import java.security.PrivateKey

class NonceSigner(internal val certificate: Certificate, private val privateKey: PrivateKey) {
    fun sign(nonce: ByteArray): ByteArray {
        val signature = NonceSignature(nonce, certificate)
        return signature.serialize(privateKey)
    }
}
