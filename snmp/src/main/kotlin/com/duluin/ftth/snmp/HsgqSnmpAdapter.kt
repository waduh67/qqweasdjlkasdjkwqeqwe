package com.duluin.ftth.snmp

import com.duluin.ftth.contract.OltTarget
import com.duluin.ftth.contract.OnuOperationalStatus
import com.duluin.ftth.contract.OnuReading
import org.slf4j.LoggerFactory
import java.time.Instant

class HsgqSnmpAdapter(
    private val readerFactory: SnmpReaderFactory = SnmpReaderFactory { host, port, community ->
        SnmpSession.open(host, port, community)
    },
    private val clock: () -> Instant = Instant::now,
) : OltAdapter {
    private val epon = HsgqEponSnmpAdapter(readerFactory, clock)
    private val log = LoggerFactory.getLogger(javaClass)

    override val vendor: String get() = VENDOR
    override val oidPlan: List<OidRole> get() = epon.oidPlan

    override fun probe(target: OltTarget): ProbeResult = epon.probe(target)

    override fun oidPlanFor(systemDescription: String?): List<OidRole> =
        if (isGpon(systemDescription)) gponOidPlan else epon.oidPlan

    override fun inventoryIndex(index: String): String? {
        val base = index.removeSuffix(".0.0")
        return base.takeIf { it.toLongOrNull() in 0L..0xFFFF_FFFFL }
    }

    override fun ontIdFrom(index: String): String? {
        val value = inventoryIndex(index)?.toLongOrNull() ?: return null
        val pon = (value shr 8) and 0xFF
        val onu = value and 0xFF
        return "PON${pon.toString().padStart(2, '0')}/$onu"
    }

    override fun pollOnus(target: OltTarget): List<OnuReading> {
        val community = target.snmpCommunity
            ?: throw OltProtocolException("Community string SNMP belum diisi untuk ${target.oltCode}")
        readerFactory.open(target.host, target.snmpPort, community).use { reader ->
            if (isGpon(reader.get(SnmpSession.SYS_DESCR))) return pollGpon(target, reader)
        }
        return epon.pollOnus(target)
    }

    private fun pollGpon(target: OltTarget, reader: SnmpReader): List<OnuReading> {
        val observedAt = clock()
        val inventory = reader.walkTable(listOf(SERIAL_OID, STATUS_OID))
        val optical = try {
            reader.walkTable(listOf(RX_POWER_OID, TX_POWER_OID))
        } catch (ex: Exception) {
            log.warn("Optik HSGQ GPON {} tidak terbaca; inventori tetap diproses", target.oltCode)
            emptyMap()
        }
        return inventory.mapNotNull { (index, row) ->
            val serial = row[SERIAL_OID]?.let(::normalizeSerial) ?: return@mapNotNull null
            val value = index.toLongOrNull() ?: return@mapNotNull null
            // .65535.65535 adalah optik port OLT, bukan ONU bernomor nol.
            val optics = optical["$index.0.0"].orEmpty()
            OnuReading(
                serialNumber = serial,
                oltCode = target.oltCode,
                ponPortLabel = "PON${(value shr 8) and 0xFF}",
                status = STATUS_MAPPING[row[STATUS_OID]?.trim()] ?: OnuOperationalStatus.UNKNOWN,
                rxPowerDbm = opticalPower(optics[RX_POWER_OID]),
                txPowerDbm = opticalPower(optics[TX_POWER_OID]),
                uptimeSeconds = null,
                distanceMeters = null,
                observedAt = observedAt,
            )
        }
    }

    private val gponOidPlan: List<OidRole> get() = listOf(
        OidRole("SERIAL", "Serial number ONU", SERIAL_OID, essential = true, interpret = ::normalizeSerial),
        OidRole("STATUS", "Running state ONU", STATUS_OID, essential = true) { STATUS_MAPPING[it.trim()]?.name },
        OidRole("NAME", "Nama ONU di OLT", NAME_OID, interpret = ::textOrNull),
        OidRole("STATE", "State ONU", STATE_OID) { STATE_MAPPING[it.trim()] },
        OidRole("CONFIG_STATE", "Config state ONU", CONFIG_STATE_OID) { CONFIG_MAPPING[it.trim()] },
        OidRole("RX_POWER", "Redaman terima (RX)", RX_POWER_OID, essential = true, interpret = ::dbmOrNull),
        OidRole("TX_POWER", "Daya kirim (TX)", TX_POWER_OID, interpret = ::dbmOrNull),
        OidRole("LAST_ON", "Last up time (jam OLT)", LAST_UP_OID, interpret = ::textOrNull),
        OidRole("DEVICE_TYPE", "Device type", null),
        OidRole("LAST_OFF", "Last down time", null),
        OidRole("DOWN_CAUSE", "Last down cause", null),
    )

    private fun isGpon(description: String?): Boolean = description?.let(GPON_MODEL::containsMatchIn) == true

    private fun normalizeSerial(raw: String): String? =
        raw.trim().uppercase().takeIf(SERIAL_FORMAT::matches)

    private fun textOrNull(raw: String): String? = raw.trim().takeIf(String::isNotEmpty)

    private fun opticalPower(raw: String?): Double? {
        val value = raw?.trim()?.toLongOrNull() ?: return null
        return (value / 100.0).takeIf { it in -50.0..10.0 }
    }

    private fun dbmOrNull(raw: String): String? = opticalPower(raw)?.let { "$it dBm" }

    companion object {
        const val VENDOR = "HSGQ"
        private const val BASE = "1.3.6.1.4.1.50224.3.12"
        const val NAME_OID = "$BASE.2.1.2"
        const val STATE_OID = "$BASE.2.1.3"
        const val STATUS_OID = "$BASE.2.1.4"
        const val CONFIG_STATE_OID = "$BASE.2.1.5"
        const val SERIAL_OID = "$BASE.2.1.15"
        const val LAST_UP_OID = "$BASE.2.1.20"
        const val RX_POWER_OID = "$BASE.3.1.4"
        const val TX_POWER_OID = "$BASE.3.1.5"

        private val GPON_MODEL = Regex("\\bHSGQ-G01ID\\b", RegexOption.IGNORE_CASE)
        private val SERIAL_FORMAT = Regex("[A-Z]{4}[0-9A-F]{8}")
        private val STATUS_MAPPING = mapOf(
            "0" to OnuOperationalStatus.UNKNOWN,
            "1" to OnuOperationalStatus.ONLINE,
            "2" to OnuOperationalStatus.OFFLINE,
        )
        private val STATE_MAPPING = mapOf(
            "0" to "Inactive", "1" to "Active", "2" to "Disable",
            "3" to "Enable", "4" to "ActiveS", "5" to "Awake",
        )
        private val CONFIG_MAPPING = mapOf("0" to "Initial", "1" to "Normal", "2" to "Fail")
    }
}
