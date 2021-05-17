package tech.relaycorp.poweb

import io.ktor.http.HttpStatusCode

internal class PoWebClientException(val responseStatus: HttpStatusCode) : Exception()
