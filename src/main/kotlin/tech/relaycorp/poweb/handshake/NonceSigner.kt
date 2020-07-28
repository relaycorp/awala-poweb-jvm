package tech.relaycorp.poweb.handshake

import org.bouncycastle.cms.CMSProcessableByteArray
import org.bouncycastle.cms.CMSSignedDataGenerator
import org.bouncycastle.cms.CMSTypedData
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.ContentSigner
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder
import tech.relaycorp.relaynet.wrappers.x509.Certificate
import java.security.PrivateKey

class NonceSigner(internal val certificate: Certificate, private val privateKey: PrivateKey) {
    fun sign(nonce: ByteArray): ByteArray {
        val signedDataGenerator = CMSSignedDataGenerator()

        val signerBuilder = JcaContentSignerBuilder("SHA256WITHRSAANDMGF1")
            .setProvider(BC_PROVIDER)
        val contentSigner: ContentSigner = signerBuilder.build(privateKey)
        val signerInfoGenerator = JcaSignerInfoGeneratorBuilder(
                JcaDigestCalculatorProviderBuilder()
                        .build()
        ).build(contentSigner, certificate.certificateHolder)
        signedDataGenerator.addSignerInfoGenerator(
                signerInfoGenerator
        )

        signedDataGenerator.addCertificate(certificate.certificateHolder)

        val plaintextCms: CMSTypedData = CMSProcessableByteArray(nonce)
        val cmsSignedData = signedDataGenerator.generate(plaintextCms, false)
        return cmsSignedData.encoded
    }

    companion object {
        val BC_PROVIDER = BouncyCastleProvider()
    }
}
