package com.karuhun.ftth.iam.application.port.inbound

/** Query profil pengguna yang sedang login (endpoint `/me`). */
interface AccountQuery {

    fun currentProfile(): AuthUserView
}
