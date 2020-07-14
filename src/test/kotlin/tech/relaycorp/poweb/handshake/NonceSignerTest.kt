package tech.relaycorp.poweb.handshake

import org.bouncycastle.asn1.ASN1InputStream
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.ASN1Primitive
import org.bouncycastle.asn1.DEROctetString
import org.bouncycastle.asn1.cms.Attribute
import org.bouncycastle.asn1.cms.ContentInfo
import org.bouncycastle.cms.CMSSignedData
import org.bouncycastle.util.CollectionStore
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import tech.relaycorp.relaynet.issueEndpointCertificate
import tech.relaycorp.relaynet.wrappers.generateRSAKeyPair
import java.security.MessageDigest
import java.time.ZonedDateTime
import kotlin.test.assertEquals

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
    fun `Serialization should be DER-encoded`() {
        val serialization = signer.sign(nonce)

        parseDer(serialization)
    }

    @Test
    fun `SignedData value should be wrapped in a ContentInfo value`() {
        val serialization = signer.sign(nonce)

        ContentInfo.getInstance(parseDer(serialization))
    }

    @Test
    fun `SignedData version should be set to 1`() {
        val serialization = signer.sign(nonce)

        val cmsSignedData = parseCmsSignedData(serialization)

        assertEquals(1, cmsSignedData.version)
    }

    @Test
    fun `Plaintext should not be embedded`() {
        val serialization = signer.sign(nonce)

        val cmsSignedData = parseCmsSignedData(serialization)

        assertEquals(null, cmsSignedData.signedContent)
    }

    @Nested
    inner class SignerInfo {
        @Test
        fun `There should only be one SignerInfo`() {
            val serialization = signer.sign(nonce)

            val cmsSignedData = parseCmsSignedData(serialization)

            assertEquals(1, cmsSignedData.signerInfos.size())
        }

        @Test
        fun `SignerInfo version should be set to 1`() {
            val serialization = signer.sign(nonce)

            val cmsSignedData = parseCmsSignedData(serialization)

            val signerInfo = cmsSignedData.signerInfos.first()
            assertEquals(1, signerInfo.version)
        }

        @Test
        fun `SignerIdentifier should be IssuerAndSerialNumber`() {
            val serialization = signer.sign(nonce)

            val cmsSignedData = parseCmsSignedData(serialization)

            val signerInfo = cmsSignedData.signerInfos.first()
            assertEquals(certificate.certificateHolder.issuer, signerInfo.sid.issuer)
            assertEquals(
                    certificate.certificateHolder.serialNumber,
                    signerInfo.sid.serialNumber
            )
        }

        @Nested
        inner class SignedAttributes {
            @Test
            fun `Signed attributes should be present`() {
                val serialization = signer.sign(nonce)

                val cmsSignedData = parseCmsSignedData(serialization)

                val signerInfo = cmsSignedData.signerInfos.first()

                assert(0 < signerInfo.signedAttributes.size())
            }

            @Test
            fun `Content type attribute should be set to CMS Data`() {
                val serialization = signer.sign(nonce)

                val cmsSignedData = parseCmsSignedData(serialization)

                val signerInfo = cmsSignedData.signerInfos.first()

                val cmsContentTypeAttrOid = ASN1ObjectIdentifier("1.2.840.113549.1.9.3")
                val contentTypeAttrs =
                        signerInfo.signedAttributes.getAll(cmsContentTypeAttrOid)
                assertEquals(1, contentTypeAttrs.size())
                val contentTypeAttr = contentTypeAttrs.get(0) as Attribute
                assertEquals(1, contentTypeAttr.attributeValues.size)
                val cmsDataOid = "1.2.840.113549.1.7.1"
                assertEquals(cmsDataOid, contentTypeAttr.attributeValues[0].toString())
            }

            @Test
            fun `Plaintext digest should be present`() {
                val serialization = signer.sign(nonce)

                val cmsSignedData = parseCmsSignedData(serialization)

                val signerInfo = cmsSignedData.signerInfos.first()

                val cmsDigestAttributeOid = ASN1ObjectIdentifier("1.2.840.113549.1.9.4")
                val digestAttrs =
                        signerInfo.signedAttributes.getAll(cmsDigestAttributeOid)
                assertEquals(1, digestAttrs.size())
                val digestAttr = digestAttrs.get(0) as Attribute
                assertEquals(1, digestAttr.attributeValues.size)
                val digest = MessageDigest.getInstance("SHA-256").digest(nonce)
                assertEquals(
                        digest.asList(),
                        (digestAttr.attributeValues[0] as DEROctetString).octets.asList()
                )
            }
        }
    }

    @Test
    fun `Signer certificate should be attached`() {
        val serialization = signer.sign(nonce)

        val cmsSignedData = parseCmsSignedData(serialization)

        val attachedCerts =
                (cmsSignedData.certificates as CollectionStore).asSequence().toList()
        assertEquals(1, attachedCerts.size)
        assertEquals(certificate.certificateHolder, attachedCerts[0])
    }

    @Test
    fun `SHA-256 should be used`() {
        val serialization = signer.sign(nonce)

        val cmsSignedData = parseCmsSignedData(serialization)

        assertEquals(1, cmsSignedData.digestAlgorithmIDs.size)
        val sha256Oid = ASN1ObjectIdentifier("2.16.840.1.101.3.4.2.1")
        assertEquals(sha256Oid, cmsSignedData.digestAlgorithmIDs.first().algorithm)

        val signerInfo = cmsSignedData.signerInfos.first()

        assertEquals(sha256Oid, signerInfo.digestAlgorithmID.algorithm)
    }

    @Test
    fun `Signature should verify`() {
        val serialization = signer.sign(nonce)

        val signerCertificate = CMSUtils.verifySignature(serialization, nonce)

        assertEquals(certificate, signerCertificate)
    }

    private fun parseDer(derSerialization: ByteArray): ASN1Primitive {
        val asn1Stream = ASN1InputStream(derSerialization)
        return asn1Stream.readObject()
    }

    private fun parseCmsSignedData(serialization: ByteArray): CMSSignedData {
        val contentInfo = ContentInfo.getInstance(
                parseDer(serialization)
        )
        return CMSSignedData(contentInfo)
    }
}
