package tech.relaycorp.poweb

public abstract class PoWebException internal constructor(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)

/**
 * Error before or while connected to the server.
 *
 * The client should retry later.
 */
public class ServerConnectionException(message: String, cause: Throwable? = null) :
    PoWebException(message, cause)

/**
 * The server sent an invalid message.
 *
 * The server didn't adhere to the protocol. Retrying later is unlikely to make a difference.
 */
public class InvalidServerMessageException(message: String, cause: Throwable) :
    PoWebException(message, cause)

/**
 * The client made a mistake while specifying the nonce signer(s).
 */
public class NonceSignerException(message: String) : PoWebException(message)
