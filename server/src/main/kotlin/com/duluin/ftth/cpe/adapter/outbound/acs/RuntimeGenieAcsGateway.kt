package com.duluin.ftth.cpe.adapter.outbound.acs

import com.duluin.ftth.cpe.application.port.outbound.AcsGateway
import com.duluin.ftth.cpe.application.port.outbound.WifiChange
import com.duluin.ftth.cpe.domain.model.FirmwareFile
import com.duluin.ftth.cpe.domain.model.SpeedDirection
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.time.Duration

@Component
@Profile("!test")
class RuntimeGenieAcsGateway(
    private val clients: GenieAcsClientProvider,
    @Value("\${ftth.cpe.temperature-params:}") private val temperatureParams: String,
    @Value("\${ftth.cpe.diagnostics.download-url:http://speedtest.tele2.net/10MB.zip}") private val downloadUrl: String,
    @Value("\${ftth.cpe.diagnostics.upload-url:http://speedtest.tele2.net/upload.php}") private val uploadUrl: String,
    @Value("\${ftth.cpe.diagnostics.upload-bytes:10485760}") private val uploadBytes: Long,
    @Value("\${ftth.cpe.diagnostics.timeout:PT25S}") private val diagnosticsTimeout: Duration,
    @Value("\${ftth.cpe.diagnostics.poll-interval:PT2S}") private val pollInterval: Duration,
) : AcsGateway {
    private fun session(): GenieAcsGateway {
        val snapshot = clients.snapshot()
        return GenieAcsGateway(snapshot.client, snapshot.healthClient, temperatureParams, downloadUrl, uploadUrl, uploadBytes, diagnosticsTimeout, pollInterval)
    }

    override fun listDevices() = session().listDevices()
    override fun findDevice(genieacsId: String) = session().findDevice(genieacsId)
    override fun wifiNetworks(genieacsId: String) = session().wifiNetworks(genieacsId)
    override fun connectedHosts(genieacsId: String) = session().connectedHosts(genieacsId)
    override fun reboot(genieacsId: String) = session().reboot(genieacsId)
    override fun applyWifi(genieacsId: String, change: WifiChange) = session().applyWifi(genieacsId, change)
    override fun runPing(genieacsId: String, host: String, count: Int, beforePost: () -> Unit) = session().runPing(genieacsId, host, count, beforePost)
    override fun runSpeedTest(genieacsId: String, direction: SpeedDirection, beforePost: () -> Unit) = session().runSpeedTest(genieacsId, direction, beforePost)
    override fun availableFirmware(productClass: String?, oui: String?) = session().availableFirmware(productClass, oui)
    override fun pushFirmware(genieacsId: String, file: FirmwareFile) = session().pushFirmware(genieacsId, file)
    override fun factoryReset(genieacsId: String) = session().factoryReset(genieacsId)
    override fun requestConnection(genieacsId: String) = session().requestConnection(genieacsId)
    override fun probe() = session().probe()
}
