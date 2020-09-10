package tech.relaycorp.poweb

public enum class PoWebContentType(public val value: String) {
    PARCEL("application/vnd.relaynet.parcel"),
    REGISTRATION_AUTHORIZATION("application/vnd.relaynet.node-registration.authorization"),
    REGISTRATION_REQUEST("application/vnd.relaynet.node-registration.request"),
    REGISTRATION("application/vnd.relaynet.node-registration.registration")
}
