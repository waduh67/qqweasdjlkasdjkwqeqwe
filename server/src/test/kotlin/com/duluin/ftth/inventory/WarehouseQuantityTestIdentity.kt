package com.duluin.ftth.inventory

import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.common.domain.identity.MacIdentity
import com.duluin.ftth.common.domain.identity.SerialIdentity
import com.duluin.ftth.customer.domain.model.Onu
import com.duluin.ftth.inventory.domain.model.StockIdentity
import com.duluin.ftth.inventory.domain.model.StockQuantity
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EmptySource
import org.junit.jupiter.params.provider.ValueSource
import java.util.Locale
import java.util.UUID

class WarehouseQuantityTestIdentity {
    @Test
    fun `serial equality uses canonical claim while original display remains intact`() {
        val raw = " \tont-i001 \n"
        val serial = SerialIdentity.parse(raw)
        assertThat(serial.raw).isEqualTo(raw)
        assertThat(serial.canonical).isEqualTo("ONT-I001")
        assertThat(serial).isEqualTo(SerialIdentity.parse("ONT-I001")).hasSameHashCodeAs(SerialIdentity.parse("ont-i001"))
        assertThat(SerialIdentity.parse(serial.canonical)).isEqualTo(serial)
        assertThat(setOf(serial, SerialIdentity.parse("ONT-I001"))).hasSize(1)
        assertThat(SerialIdentity.parse("ONT-1")).isNotEqualTo(SerialIdentity.parse("ONT1"))
        assertThat(SerialIdentity.parse("AA-BB")).isNotEqualTo(SerialIdentity.parse("AA:BB"))
    }

    @Test
    @ResourceLock("java.util.Locale.default")
    fun `identity parsing and customer creation ignore Turkish default locale`() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertThat(SerialIdentity.parse(" ont-i001 ").canonical).isEqualTo("ONT-I001")
            assertThat(Onu.create(UUID.randomUUID(), UUID.randomUUID(), " ont-i001 ", null).serialNumber).isEqualTo("ONT-I001")
            assertThat(MacIdentity.parse("aa-bb:cc-dd:ee-ff").canonical).isEqualTo("AA:BB:CC:DD:EE:FF")
            assertThat(SerialIdentity.parse("stra\u00dfe")).isEqualTo(SerialIdentity.parse("STRASSE"))
        } finally {
            Locale.setDefault(original)
        }
    }

    @ParameterizedTest
    @EmptySource
    @ValueSource(strings = [" ", "\t\n", "\u2003"])
    fun `empty serials have no claim`(input: String) {
        assertThatThrownBy { SerialIdentity.parse(input) }.isInstanceOf(ValidationException::class.java)
        assertThatThrownBy { StockIdentity.parse(input, null) }.isInstanceOf(ValidationException::class.java)
    }

    @ParameterizedTest
    @ValueSource(strings = ["aabbccddeeff", "aa:bb:cc:dd:ee:ff", "AA-BB-CC-DD-EE-FF", " aa-bb:cc-dd:ee-ff ", "aabb.ccdd.eeff"])
    fun `valid MAC separator variants share one canonical claim`(input: String) {
        val mac = MacIdentity.parse(input)
        assertThat(mac.raw).isEqualTo(input)
        assertThat(mac.canonical).isEqualTo("AA:BB:CC:DD:EE:FF")
        assertThat(mac).isEqualTo(MacIdentity.parse("AABBCCDDEEFF")).hasSameHashCodeAs(MacIdentity.parse("AABBCCDDEEFF"))
        assertThat(MacIdentity.parse(mac.canonical)).isEqualTo(mac)
    }

    @ParameterizedTest
    @EmptySource
    @ValueSource(strings = [" ", "aabbccddeef", "aabbccddeeff0", "gg:bb:cc:dd:ee:ff", "aa!bb!cc!dd!ee!ff",
        "aa::bb:cc:dd:ee:ff", ":aabbccddeeff", "aabbccddeeff:", "a:abb:cc:dd:ee:ff", "aa bb cc dd ee ff",
        "aa.bb.cc.dd.ee.ff", "aabb.ccdd-eeff", "aa:bb:cc:dd:ee:fg", "\uFF21\uFF21:bb:cc:dd:ee:ff", "aa:bb:cc:dd:ee:\u0661\u0662"])
    fun `malformed MAC values cannot form a claim`(input: String) {
        assertThatThrownBy { MacIdentity.parse(input) }.isInstanceOf(ValidationException::class.java)
        assertThatThrownBy { StockIdentity.parse("ONT-1", input) }.isInstanceOf(ValidationException::class.java)
    }

    @Test
    fun `stock device identities keep raw values and are always exactly one EA`() {
        val identity = StockIdentity.parse(" ont-1 ", "aa-bb:cc-dd:ee-ff")
        assertThat(identity.quantity).isEqualTo(StockQuantity.each("1"))
        assertThat(identity.serial.raw).isEqualTo(" ont-1 ")
        assertThat(identity.mac?.raw).isEqualTo("aa-bb:cc-dd:ee-ff")
        assertThat(identity).isEqualTo(StockIdentity.parse("ONT-1", "AA:BB:CC:DD:EE:FF"))
        assertThat(StockIdentity.parse("ONT-1", null).mac).isNull()
    }
}
