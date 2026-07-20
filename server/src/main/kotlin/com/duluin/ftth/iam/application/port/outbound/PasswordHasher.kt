package com.duluin.ftth.iam.application.port.outbound

/** Port hashing password — implementasi (BCrypt) berada di lapisan adapter. */
interface PasswordHasher {

    fun hash(rawPassword: String): String

    fun matches(rawPassword: String, passwordHash: String): Boolean
}
