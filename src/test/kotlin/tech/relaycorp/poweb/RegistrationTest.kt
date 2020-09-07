package tech.relaycorp.poweb

import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class RegistrationTest {
    @Nested
    inner class PreRegistration {
        @Test
        @Disabled
        fun `Request should be POSTed to the appropriate endpoint`() {
        }

        @Test
        @Disabled
        fun `Request body should be SHA-256 digest of the node public key`() {
        }

        @Test
        @Disabled
        fun `An invalid response content type should be refused`() {
        }

        @Test
        @Disabled
        fun `20X response status other than 200 should throw an error`() {
        }

        @Test
        @Disabled
        fun `Authorization should be output serialized if status is 200`() {
        }
    }
}
