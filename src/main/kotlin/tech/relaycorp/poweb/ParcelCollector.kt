package tech.relaycorp.poweb

public class ParcelCollector(
    public val parcelSerialized: ByteArray
) {
    public suspend fun ack(): Unit = TODO()
}
