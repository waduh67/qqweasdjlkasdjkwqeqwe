package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class ReferenceWorkOrderTypeIT : WarehouseMasterHttpFixture() {
    private fun owner(): String {
        val owner = tenant()
        val drained = request("POST", "/api/v2/warehouse/workflow/drain", owner, """{"expectedEpoch":0}""")
        assertThat(drained.status).withFailMessage(drained.contentAsString).isEqualTo(200)
        val review = request("GET", "/api/v2/warehouse/workflow/review", owner)
        assertThat(review.status).isEqualTo(200)
        val hash = mapper.readTree(review.contentAsString).path("reviewHash").asString()
        val activated = request("POST", "/api/v2/warehouse/workflow/activate", owner,
            """{"expectedEpoch":1,"reviewHash":"$hash","reason":"Jenis pekerjaan baru"}""")
        assertThat(activated.status).withFailMessage(activated.contentAsString).isEqualTo(200)
        return owner
    }

    @Test fun `owner creates a custom type at default revision and exact retry creates only once`() {
        val owner = owner()
        val key = UUID.randomUUID().toString()
        val body = """{"name":"Pemeriksaan konektor","workType":"PREVENTIVE","materialRequired":true,"photoSlots":["Hasil"]}"""

        val created = request("POST", "/api/v2/work-orders/types", owner, body, key)

        assertThat(created.status).withFailMessage(created.contentAsString).isEqualTo(201)
        val type = mapper.readTree(created.contentAsString)
        assertThat(type.path("revision").asLong()).isZero()
        assertThat(type.path("active").asBoolean()).isTrue()
        assertThat(type.path("workType").asString()).isEqualTo("PREVENTIVE")
        val replay = request("POST", "/api/v2/work-orders/types", owner, body, key)
        assertThat(replay.status).isEqualTo(201)
        assertThat(mapper.readTree(replay.contentAsString)).isEqualTo(type)
        val listed = request("GET", "/api/v2/work-orders/types", owner)
        assertThat(listed.status).isEqualTo(200)
        assertThat(mapper.readTree(listed.contentAsString).filter { it.path("name").asString() == "Pemeriksaan konektor" }).hasSize(1)
    }

    @Test fun `custom type creation rejects a nonzero expected revision without saving`() {
        val owner = owner()
        val body = """{"name":"Pemeriksaan baru","workType":"PREVENTIVE","materialRequired":false,"photoSlots":["Hasil"],"expectedRevision":1}"""

        val rejected = request("POST", "/api/v2/work-orders/types", owner, body)

        assertThat(rejected.status).isEqualTo(409)
        assertThat(mapper.readTree(rejected.contentAsString).path("code").asString()).isEqualTo("STALE_REVISION")
        val listed = request("GET", "/api/v2/work-orders/types", owner)
        assertThat(listed.status).isEqualTo(200)
        assertThat(mapper.readTree(listed.contentAsString).filter { it.path("name").asString() == "Pemeriksaan baru" }).isEmpty()
    }
}
