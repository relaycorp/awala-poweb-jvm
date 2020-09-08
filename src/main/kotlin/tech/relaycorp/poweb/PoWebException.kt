package tech.relaycorp.poweb

public abstract class PoWebException internal constructor(
    message: String?,
    cause: Throwable? = null
) : Exception(message, cause)

/**
 * Base class for connectivity errors and errors caused by the server.
 */
public abstract class ServerException internal constructor(message: String, cause: Throwable?) :
    PoWebException(message, cause)

/**
 * Error before or while connected to the server.
 *
 * The client should retry later.
 */
public class ServerConnectionException(message: String, cause: Throwable? = null) :
    ServerException(message, cause)

/**
 * The server sent an invalid message or behaved in any other way that violates the PoWeb binding.
 *
 * The server didn't adhere to the protocol. Retrying later is unlikely to make a difference.
 */
public class ServerBindingException(message: String, cause: Throwable? = null) :
    ServerException(message, cause)

/**
 * The server refused to accept a parcel.
 */
public class RejectedParcelException(message: String) : PoWebException(message)

/**
 * Base class for exceptions (supposedly) caused by the client.
 */
public abstract class ClientException internal constructor(message: String) :
    PoWebException(message)

/**
 * The server claims that the client is violating the PoWeb binding.
 *
 * Retrying later is unlikely to make a difference.
 */
public class ClientBindingException(message: String, public val statusCode: Int) :
    ClientException(message)

/**
 * The client made a mistake while specifying the nonce signer(s).
 */
public class NonceSignerException(message: String) : ClientException(message)
