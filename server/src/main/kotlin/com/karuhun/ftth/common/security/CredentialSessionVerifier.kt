package com.karuhun.ftth.common.security

interface CredentialSessionVerifier {
    fun isCurrent(identity: SessionIdentity): Boolean
}
