package tech.relaycorp.poweb.handshake

import org.bouncycastle.asn1.ASN1InputStream
import org.bouncycastle.asn1.cms.ContentInfo
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cert.selector.X509CertificateHolderSelector
import org.bouncycastle.cms.CMSProcessableByteArray
import org.bouncycastle.cms.CMSSignedData
import org.bouncycastle.cms.SignerInformation
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder
import org.bouncycastle.util.Selector
import tech.relaycorp.relaynet.wrappers.x509.Certificate

object CMSUtils {
    internal fun verifySignature(
        cmsSignedData: ByteArray,
        plaintext: ByteArray
    ): Certificate {
        val signedData = parseCmsSignedData(cmsSignedData, plaintext)

        val signerInfo = getSignerInfoFromSignedData(signedData)

        // We shouldn't have to force this type cast but this is the only way I could get the code to work and, based on
        // what I found online, that's what others have had to do as well
        @Suppress("UNCHECKED_CAST") val signerCertSelector = X509CertificateHolderSelector(
                signerInfo.sid.issuer,
                signerInfo.sid.serialNumber
        ) as Selector<X509CertificateHolder>

        val signerCertMatches = signedData.certificates.getMatches(signerCertSelector)
        val signerCertificateHolder = signerCertMatches.first()
        val verifier = JcaSimpleSignerInfoVerifierBuilder().build(signerCertificateHolder)

        signerInfo.verify(verifier)

        return Certificate(signerCertificateHolder)
    }

    private fun parseCmsSignedData(
        cmsSignedDataSerialized: ByteArray,
        expectedPlaintext: ByteArray
    ): CMSSignedData {
        val asn1Stream = ASN1InputStream(cmsSignedDataSerialized)
        val asn1Sequence = asn1Stream.readObject()
        val contentInfo = ContentInfo.getInstance(asn1Sequence)
        return CMSSignedData(CMSProcessableByteArray(expectedPlaintext), contentInfo)
    }

    private fun getSignerInfoFromSignedData(signedData: CMSSignedData): SignerInformation {
        val signersCount = signedData.signerInfos.size()
        if (signersCount != 1) {
            throw Exception("SignedData should contain exactly one SignerInfo (got $signersCount)")
        }
        return signedData.signerInfos.first()
    }
}
