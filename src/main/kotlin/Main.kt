import com.ghgande.j2mod.modbus.facade.ModbusTCPMaster
import com.google.gson.JsonParser
import com.influxdb.client.InfluxDBClient
import com.influxdb.client.InfluxDBClientFactory
import com.influxdb.client.domain.WritePrecision
import com.influxdb.client.write.Point
import okhttp3.*
import org.pmw.tinylog.Logger
import java.time.Duration
import java.time.Instant
import java.util.*
import kotlin.concurrent.scheduleAtFixedRate

private const val POLL_INTERVAL_MINUTES = 2L
private const val FRONIUS_API_URL = "http://fronius-wechselrichter/solar_api/v1/GetPowerFlowRealtimeData.fcgi"

private const val INFLUX_DB_URL = "http://raspberrypi:8086"
private const val INFLUX_DB_TOKEN = "my-token"
private const val INFLUX_DB_ORG = "my-org"
private const val INFLUX_DB_BUCKET = "weber"

fun main() {

    println(
        """
---
        _ _   _                                 _____ 
  /\/\ (_) |_| |_ ___ _ ____      _____  __ _  |___  |
 /    \| | __| __/ _ \ '__\ \ /\ / / _ \/ _` |    / / 
/ /\/\ \ | |_| ||  __/ |   \ V  V /  __/ (_| |   / /  
\/    \/_|\__|\__\___|_|    \_/\_/ \___|\__, |  /_/   
                                        |___/                  

Varta Batterie to InfluxDB version 0.5.0
---
    """.trimIndent()
    )

    val token = INFLUX_DB_TOKEN.toCharArray()
    val org = INFLUX_DB_ORG
    val bucket = INFLUX_DB_BUCKET
    val influxDBClient: InfluxDBClient = InfluxDBClientFactory.create(INFLUX_DB_URL, token, org, bucket)

    val master = ModbusTCPMaster("varta-batterie", 502)
    master.connect()

    val client = OkHttpClient.Builder()
        .build()

    Timer().scheduleAtFixedRate(0L, Duration.ofMinutes(POLL_INTERVAL_MINUTES).toMillis()) {
        try {
            val point = Point.measurement("mitterweg7")
                .time(Instant.now().toEpochMilli(), WritePrecision.MS)
            try {
                readAndWriteHttpData(client, point)
            } catch (e: Exception) {
                Logger.warn(e, "Exception while fetching HTTP data: ")
            }
            try {
                readAndWriteModbusData(master, point)
            } catch (e: Exception) {
                Logger.warn(e, "Exception while fetching modbus data: ")
            }
            influxDBClient.writeApiBlocking.writePoint(point)
        } catch (e: Exception) {
            Logger.warn(e, "Exception while writing data to InfluxDB: ")
        }
    }
}

private fun readAndWriteModbusData(
    master: ModbusTCPMaster,
    point: Point
) {
    val resp1 = master.readMultipleRegisters(1066, 1).map {
        it.value.toShort()
    }.first()
    point.addField("active_power", resp1)

    val resp2 = master.readMultipleRegisters(1078, 1).map {
        it.value.toShort()
    }.first()
    point.addField("grid_power", resp2)

    val resp3 = master.readMultipleRegisters(1068, 1).map {
        it.value
    }.first()
    point.addField("SOC", resp3)
    Logger.info("Wrote Modbus data to InfluxDB")
}

private fun readAndWriteHttpData(
    client: OkHttpClient,
    point: Point
) {
    val request = Request.Builder().url(FRONIUS_API_URL).build()
    val body = client.newCall(request).execute().body!!
    val p = JsonParser.parseString(body.string())
        .asJsonObject.get("Body")
        .asJsonObject.get("Data")
        .asJsonObject.get("Inverters")
        .asJsonObject.get("1")
        .asJsonObject.get("P")
        .asInt
    point.addField("PMB", p)
    Logger.info("Wrote HTTP data to InfluxDB")
}