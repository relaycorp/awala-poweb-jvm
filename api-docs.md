# Module poweb

This is the JVM implementation of the [Relaynet PoWeb binding](https://specs.relaynet.network/RS-016), meant to be used on Android 5+ and Java 8+ platforms.

## Install

This package can be retrieved from [JCenter](https://bintray.com/relaycorp/maven/tech.relaycorp.poweb). For example, using the Gradle Groovy DSL:

```
implementation 'tech.relaycorp:poweb:1.5.13'
```

## Usage

The first step is to generate and store the key pair that your private node will use on the network:

```kotlin
val keyPair = generateRSAKeyPair()
securelyStorePrivateKey(keyPair.private)
```

Next, initialize the client, which depends on the nature of your software:

- If it is a Relaynet-compatible app, you'll want to use [tech.relaycorp.poweb.PoWebClient.initLocal]; e.g.:
  ```kotlin
  val client = PoWebClient.initLocal()
  ```
  
  Note: If you're building a Relaynet-compatible app for Android, use [the endpoint library](https://github.com/relaycorp/relaynet-endpoint-android) instead as it offers a high-level interface to interact with the Android Gateway.
- If it's a private gateway, like the [Android Gateway](https://github.com/relaycorp/relaynet-gateway-android), you'll want to connect to the public gateway using [tech.relaycorp.poweb.PoWebClient.initRemote]; e.g.:
  ```kotlin
  val client = PoWebClient.initRemote("poweb-frankfurt.relaycorp.cloud")
  ```

### Private node registration

Per the PoWeb spec, before your private endpoint can exchange parcels with its gateway, it must register with it in order to get a certificate.

The registration step is preceded by a pre-registration that depends on the type of server you're connecting to. If you're connecting to a private gateway, the pre-registration process is determined by the private gateway implementation; for example, as of this writing, the Android Gateway uses a bound service. If you're connecting to a public gateway, to can pre-register using [tech.relaycorp.poweb.PoWebClient.preRegisterNode]; e.g.:

```kotlin
val registrationRequest = client.preRegisterNode(keyPair.public)
```

Once pre-registration is complete, you can complete the registration by passing the registration request `registrationRequest` to [tech.relaycorp.poweb.PoWebClient.registerNode]. This registration request contains the registration authorization created by the server. For example, you may complete the registration as follows:

```kotlin
val requestSerialized = registrationRequest.serialize(keyPair.private)
val registration = client.registerNode(requestSerialized)
```

Finally, you can now store the certificates contained in the [registration](https://docs.relaycorp.tech/relaynet-jvm/api/relaynet/tech.relaycorp.relaynet.messages.control/-private-node-registration/index.html).

### Request signing

Now that your private node is registered with its gateway, you have to use the certificate issued by its gateway and its corresponding private key to sign requests to exchange parcels. You can do so by initializing a _signer_ and passing it to such requests; e.g.:

```kotlin
val signer = Signer(registration.privateNodeCertificate, keyPair.private)
```

### Delivering parcels

Use [tech.relaycorp.poweb.PoWebClient.deliverParcel] to deliver parcels to the gateway; e.g.:

```kotlin
client.deliverParcel(parcelSerialized, signer)
```

### Collecting parcels

Use [tech.relaycorp.poweb.PoWebClient.collectParcels] to collect parcels from the gateway; e.g.:

```kotlin
client.collectParcels(arrayOf(signer)).map {
    val parcel = it.deserializeAndValidateParcel()
    println("Parcel ${parcel.id} bound for ${parcel.recipientAddress} is valid")
    storeParcel(it.parcelSerialized)
    it.ack()
}
```

### Closing the connection

Make sure to close the connection by calling [tech.relaycorp.poweb.PoWebClient.close] when you're done, or wrapping the statements using the client with a `.use` block as illustrated below:

```kotlin
client.use {
    client.deliverParcel(parcelSerialized, signer)
}
```

# Package tech.relaycorp.poweb

This package contains the PoWeb client.
