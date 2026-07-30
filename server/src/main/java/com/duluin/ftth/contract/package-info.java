/**
 * Kontrak wire lintas-proses server↔collector: DTO protokol collector
 * ({@code CollectorProtocol}) dan codec RADIUS DAE ({@code radius.RadiusDae}).
 *
 * <p>Dideklarasikan sebagai module <b>OPEN</b> karena memang kontrak bersama yang
 * dimaksudkan dipakai bebas oleh module bisnis (mis. {@code bng} memakai codec DAE
 * di sub-package {@code radius}) — enkapsulasi sub-package tidak ditegakkan di sini.
 *
 * <p>Anotasi ditaruh di module {@code server} (bukan di module {@code contract} itu
 * sendiri) agar {@code contract} tetap murni Kotlin/JVM tanpa dependency pihak-ketiga
 * apa pun. Ditulis sebagai {@code package-info.java} karena Kotlin tidak mendukung
 * anotasi tingkat package.
 */
@org.springframework.modulith.ApplicationModule(
        type = org.springframework.modulith.ApplicationModule.Type.OPEN
)
package com.duluin.ftth.contract;
