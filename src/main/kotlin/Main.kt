import com.ghgande.j2mod.modbus.facade.ModbusTCPMaster
import com.influxdb.client.InfluxDBClient
import com.influxdb.client.InfluxDBClientFactory
import com.influxdb.client.domain.WritePrecision
import com.influxdb.client.write.Point
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.internal.toLongOrDefault
import org.pmw.tinylog.Logger
import java.time.Duration
import java.time.Instant
import java.util.*
import kotlin.concurrent.scheduleAtFixedRate

private const val POLL_INTERVAL_MINUTES = 2L

private const val BATTERY_CONF_URL = "http://varta-batterie/cgi/ems_conf.js"
private const val BATTERY_DATA_URL = "http://varta-batterie/cgi/ems_data.js"

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

Varta Batterie to InfluxDB version 0.4.1
---
    """.trimIndent()
    )

    val token = INFLUX_DB_TOKEN.toCharArray()
    val org = INFLUX_DB_ORG
    val bucket = INFLUX_DB_BUCKET
    val influxDBClient: InfluxDBClient = InfluxDBClientFactory.create(INFLUX_DB_URL, token, org, bucket)

    val client = OkHttpClient()
    val rowNameRequest: Request = Request.Builder()
        .url(BATTERY_CONF_URL)
        .build()

    val dataRequest: Request = Request.Builder()
        .url(BATTERY_DATA_URL)
        .build()

    val master = ModbusTCPMaster("varta-batterie", 502)
    master.connect()

    val rowNamesString = client.newCall(rowNameRequest).execute().body!!.string()

    val rowNames = rowNamesString.lines()
        .filter {
            it.isNotBlank()
        }
        .filter {
            it.startsWith("WR_Conf")
        }
        .associate {
            val split = it.split(" = ")
            val names = split[1]
                .replace("[", "")
                .replace("]", "")
                .replace(";", "")
                .replace("\"", "")
                .split(",")
            split[0].replace("_Conf", "") to names
        }

    Timer().scheduleAtFixedRate(0L, Duration.ofMinutes(POLL_INTERVAL_MINUTES).toMillis()) {
        try {
            val point = Point.measurement("mitterweg7")
                .time(Instant.now().toEpochMilli(), WritePrecision.MS)
            readAndWriteHttpData(client, dataRequest, rowNames, point)
            readAndWriteModbusData(master, point)
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
    dataRequest: Request,
    rowNames: Map<String, List<String>>,
    point: Point
) {
    val dataString = client.newCall(dataRequest).execute().body!!.string()
    val data = dataString.lines()
        .filter {
            it.isNotBlank()
        }
        .filter {
            it.startsWith("WR_Data")
        }
        .associate {
            val split = it.split(" = ")
            val names = split[1]
                .replace("[", "")
                .replace("]", "")
                .replace(";", "")
                .replace("\"", "")
                .split(",")
            split[0].replace("_Data", "") to names
        }
    val wechselRichterRowNames = rowNames["WR"]!!
    val wechselRichterData = data["WR"]!!
    val pmb = wechselRichterRowNames.zip(wechselRichterData).first {
        it.first == "PMB"
    }.second.toLongOrDefault(-1)
    point.addField("PMB", pmb)
    Logger.info("Wrote WR data to InfluxDB")
}