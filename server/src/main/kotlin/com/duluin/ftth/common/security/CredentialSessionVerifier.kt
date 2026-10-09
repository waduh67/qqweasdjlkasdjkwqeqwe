package com.duluin.ftth.common.security

interface CredentialSessionVerifier {
    fun isCurrent(identity: SessionIdentity): Boolean
}
