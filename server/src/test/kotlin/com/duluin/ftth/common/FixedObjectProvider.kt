package com.duluin.ftth.common

import org.springframework.beans.factory.NoSuchBeanDefinitionException
import org.springframework.beans.factory.ObjectProvider

/**
 * `ObjectProvider` sederhana untuk unit test: membungkus satu nilai, atau ketiadaannya.
 *
 * Beberapa komponen di `common` menerima dependensinya lewat [ObjectProvider] justru supaya
 * tetap berjalan saat bean-nya tak ada (implementasinya tinggal di module lain). Test-nya
 * perlu bisa menirukan KEDUA keadaan itu, dan Spring tak menyediakan pembungkus siap pakai.
 */
class FixedObjectProvider<T : Any>(private val value: T?) : ObjectProvider<T> {
    override fun getObject(): T = value ?: throw NoSuchBeanDefinitionException("tak ada nilai di provider uji")
    override fun getObject(vararg args: Any?): T = getObject()
    override fun getIfAvailable(): T? = value
    override fun getIfUnique(): T? = value
}
