package com.karuhun.ftth.fieldservice.adapter.outbound.network

import com.karuhun.ftth.fieldservice.application.port.outbound.IpPingPort
import com.karuhun.ftth.fieldservice.application.service.IpProbeResult
import com.karuhun.ftth.fieldservice.domain.Ipv4Block
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.TimeUnit

@Component
class ProcessIpPingAdapter : IpPingPort {
    override fun ping(address: String): IpProbeResult {
        Ipv4Block.number(address)
        val checked = Instant.now()
        val windows = System.getProperty("os.name").startsWith("Windows",ignoreCase=true)
        val args = if (windows) listOf("ping.exe","-n","1","-w","2000",address)
            else listOf("ping","-n","-c","1","-W","2",address)
        var process: Process? = null
        return try {
            val builder = ProcessBuilder(args).redirectErrorStream(true)
            builder.environment()["LC_ALL"] = "C"
            process = builder.start()
            if (!process.waitFor(4,TimeUnit.SECONDS)) {
                process.destroyForcibly()
                IpProbeResult("ERROR",checked,error="Proses ping melebihi batas waktu")
            } else {
                val output = process.inputStream.bufferedReader().readText().take(4096)
                val fatal = listOf("operation not permitted","permission denied","socket: ","could not find","general failure","transmit failed")
                    .any { output.contains(it,ignoreCase=true) }
                val success = if (windows) output.contains(Regex("(?i)TTL[= ]")) else process.exitValue()==0
                val rtt = Regex("(?i)(?:time|waktu)[=<]\\s*([0-9]+(?:[.,][0-9]+)?)\\s*ms").find(output)
                    ?.groupValues?.get(1)?.replace(',','.')?.toDoubleOrNull()
                when {
                    fatal || !windows && process.exitValue()>1 -> IpProbeResult("ERROR",checked,error="Utilitas atau akses ICMP tidak tersedia")
                    success -> IpProbeResult("UP",checked,rtt)
                    else -> IpProbeResult("DOWN",checked)
                }
            }
        } catch (_: Exception) { IpProbeResult("ERROR",checked,error="Tidak dapat menjalankan utilitas ping") }
        finally { if (process?.isAlive == true) process.destroyForcibly() }
    }
}
