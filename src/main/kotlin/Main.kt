import com.influxdb.client.InfluxDBClient
import com.influxdb.client.InfluxDBClientFactory
import com.influxdb.client.domain.WritePrecision
import com.influxdb.client.write.Point
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.internal.toLongOrDefault
import org.pmw.tinylog.Logger
import java.time.Instant
import java.util.*
import kotlin.concurrent.scheduleAtFixedRate

fun main() {
    val token = "my-token".toCharArray()
    val org = "my-org"
    val bucket = "weber"
    val influxDBClient: InfluxDBClient = InfluxDBClientFactory.create("http://raspberrypi:8086", token, org, bucket)

    val client = OkHttpClient()
    val rowNameRequest: Request = Request.Builder()
        .url("http://varta-batterie/cgi/ems_conf.js")
        .build()

    val dataRequest: Request = Request.Builder()
        .url("http://varta-batterie/cgi/ems_data.js")
        .build()

    val rowNamesString = client.newCall(rowNameRequest).execute().body!!.string()

    val rowNames = rowNamesString.lines()
        .filter {
            it.isNotBlank()
        }
        .filter {
            it.startsWith("WR_Conf") || it.startsWith("EMeter_Conf")
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

    Timer().scheduleAtFixedRate(0L, 30_000L) {
        try {
            val dataString = client.newCall(dataRequest).execute().body!!.string()
            val data = dataString.lines()
                .filter {
                    it.isNotBlank()
                }
                .filter {
                    it.startsWith("WR_Data") || it.startsWith("EMETER_Data")
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

            val wrPoint: Point = Point.measurement("WR")
                .time(Instant.now().toEpochMilli(), WritePrecision.MS)
            val wechselRichterRowNames = rowNames["WR"]!!
            val wechselRichterData = data["WR"]!!
            wechselRichterRowNames.zip(wechselRichterData).forEach {
                wrPoint.addField(it.first, it.second.toLongOrDefault(-1L))
            }
            influxDBClient.writeApiBlocking.writePoint(wrPoint)
            Logger.info("Wrote WR data to InfluxDB")

            val eMeterPoint: Point = Point.measurement("EMETER")
                .time(Instant.now().toEpochMilli(), WritePrecision.MS)
            val eMeterRowNames = rowNames["EMeter"]!!
            val eMeterRowNamesData = data["EMETER"]!!
            eMeterRowNames.zip(eMeterRowNamesData).forEach {
                eMeterPoint.addField(it.first, it.second.toLongOrDefault(-1L))
            }
            influxDBClient.writeApiBlocking.writePoint(eMeterPoint)
            Logger.info("Wrote WR data to InfluxDB")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}