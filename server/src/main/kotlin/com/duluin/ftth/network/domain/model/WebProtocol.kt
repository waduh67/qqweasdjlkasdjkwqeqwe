package com.duluin.ftth.network.domain.model

/**
 * Protokol Web UI / HTTP-API OLT. Sebagian vendor (mis. HSGQ) dikelola langsung
 * lewat Web UI API alih-alih SNMP; vendor lain (mis. ZTE) memakainya untuk membaca
 * metrik yang tak terekspos SNMP (suhu, daya optik).
 */
enum class WebProtocol { HTTP, HTTPS }
