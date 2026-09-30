package com.duluin.ftth.mobile.domain

import kotlin.test.*

class MaterialQuantityTest {
    @Test fun exactMillimetresAndWholeUnitsRejectPrecisionLossAndOverflow() {
        assertEquals("82500", MaterialQuantity.measured("82,500", MaterialUnit.MM).base)
        assertEquals("17.500", MaterialQuantity.base("17500").display(MaterialUnit.MM))
        assertEquals("9223372036854775807", MaterialQuantity.measured("9223372036854775.807", MaterialUnit.MM).base)
        assertEquals("1", MaterialQuantity.measured("0001", MaterialUnit.EA).base)
        for (input in listOf("0", "-1", "1.0001", "1e3", "9223372036854775.808", "NaN")) assertFailsWith<IllegalArgumentException> { MaterialQuantity.measured(input, MaterialUnit.MM) }
        assertFailsWith<IllegalArgumentException> { MaterialQuantity.measured("1.5", MaterialUnit.EA) }
        assertFailsWith<IllegalArgumentException> { MaterialQuantity.base("01") }
    }
}
