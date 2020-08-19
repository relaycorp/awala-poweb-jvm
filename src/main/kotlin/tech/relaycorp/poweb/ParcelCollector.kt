package tech.relaycorp.poweb

@Suppress("ArrayInDataClass")
public data class ParcelCollector(
    public val parcelSerialized: ByteArray,
    public val ack: suspend () -> Unit
)
