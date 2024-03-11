import com.ghgande.j2mod.modbus.facade.ModbusTCPMaster
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.pmw.tinylog.Logger
import java.time.Duration
import java.util.*
import kotlin.concurrent.scheduleAtFixedRate
import kotlin.math.sqrt

private const val FRONIUS_API_URL = "http://fronius-wechselrichter/solar_api/v1/GetPowerFlowRealtimeData.fcgi"
private const val WALLBOX_POWER_DRAW_URL = "http://warp2-296n/meter/values"
private const val WALLBOX_CURRENT_UPDATE_URL = "http://warp2-296n/evse/global_current_update"
private const val WALLBOX_CURRENT_URL = "http://warp2-296n/evse/global_current"
private const val WALLBOX_START_CHARGE_URL = "http://warp2-296n/evse/start_charging "
private const val WALLBOX_STOP_CHARGE_URL = "http://warp2-296n/evse/stop_charging"

private const val VARTA_BATTERIE = "varta-batterie"

private const val MIN_OVERPRODUCTION_WATTS = 3000

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
test
Charging version 0.1.0
---
    """.trimIndent()
    )

    val master = ModbusTCPMaster(VARTA_BATTERIE, 502)
    master.connect()

    val client = OkHttpClient.Builder()
        .build()

    Timer().scheduleAtFixedRate(0L, Duration.ofSeconds(30).toMillis()) {
        val configuredChargeCurrentMilliAmps = readChargeCurrent(client)
        if (configuredChargeCurrentMilliAmps == 16_000) {
            Logger.info("Voigas mode activated, because 16A are configured in the Wallbox. Not changing current based on solar power.")
        } else {
            val batteryChargingPowerWatts = readBatteryChargingPower(master)
            val gridPowerWatts = readGridPower(master)
            val solarPanelPowerWatts = readSolarPanelPower(client)
            val wallboxPowerWatts = readWallboxPower(client)

            val powerUsage = solarPanelPowerWatts - batteryChargingPowerWatts - gridPowerWatts
            val overProductionWatts = solarPanelPowerWatts - powerUsage + wallboxPowerWatts
            val nextChargingCurrentMilliAmps = powerToMilliAmps(overProductionWatts).coerceAtMost(15_000f)

            Logger.info(
                """
                Setting charge based on solar power:
                    - Grid Power: ${gridPowerWatts}W
                    - Battery charging power: ${batteryChargingPowerWatts}W
                    - Wallbox power: ${wallboxPowerWatts}W
                    - Solar panel power: ${solarPanelPowerWatts}W
                    - Power usage: ${powerUsage}W
                    - Overproduction: ${overProductionWatts}W
                    - Next Charging Power: ${overProductionWatts}W
                    - Next Charging Current: ${nextChargingCurrentMilliAmps.toInt()}mA
                """.trimIndent())

            if (overProductionWatts < MIN_OVERPRODUCTION_WATTS) {
                stopCharge(client)
            } else {
                startCharge(client)
                setChargeCurrent(nextChargingCurrentMilliAmps, client)
            }
        }
    }
}

private fun powerToMilliAmps(powerWatts: Int) = (powerWatts / (400f  * sqrt(3f))) * 1000f

private fun readChargeCurrent(
    client: OkHttpClient,
): Int = try {
    val request = Request.Builder().url(WALLBOX_CURRENT_URL).build()
    val body = client.newCall(request).execute().body!!
    JsonParser.parseString(body.string())
        .asJsonObject.get("current")
        .asInt
} catch (e: Exception) {
    Logger.warn(e, "Exception while fetching current data from Wallbox: ")
    0
}

private fun setChargeCurrent(nextChargingCurrentMilliAmps: Float, client: OkHttpClient) {
    val jsonString = "{\"current\":${nextChargingCurrentMilliAmps.toInt()}}"
    val mediaType = "application/json; charset=utf-8".toMediaType()
    val requestBody: RequestBody = jsonString.toRequestBody(mediaType)

    try {
        val request = Request.Builder().url(WALLBOX_CURRENT_UPDATE_URL).post(requestBody).build()
        val response = client.newCall(request).execute()
        println(response.body!!.string())
        check(response.code == 200)
    } catch (e: Exception) {
        Logger.warn(e, "Exception while changing wallbox current: ")
    }
}

private fun startCharge(client: OkHttpClient) {
    try {
        println("Starting Charging")
        val jsonString = "{}"
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val request = Request.Builder().url(WALLBOX_START_CHARGE_URL).post(jsonString.toRequestBody(mediaType)).build()
        val response = client.newCall(request).execute()
        println(response.body!!.string())
        check(response.code == 200)
    } catch (e: Exception) {
        Logger.warn(e, "Exception while starting charging: ")
    }
}

private fun stopCharge(client: OkHttpClient) {
    try {
        println("Stopping Charging")
        val jsonString = "{}"
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val request = Request.Builder().url(WALLBOX_STOP_CHARGE_URL).post(jsonString.toRequestBody(mediaType)).build()
        val response = client.newCall(request).execute()
        println(response.body!!.string())
        check(response.code == 200)
    } catch (e: Exception) {
        Logger.warn(e, "Exception while stopping charging: ")
    }
}

private fun readBatteryChargingPower(
    master: ModbusTCPMaster
): Int = try {
    master.readMultipleRegisters(1066, 1).map {
        it.value.toShort()
    }.first().toInt()
} catch (e: Exception) {
    Logger.warn(e, "Exception while fetching Modbus data: ")
    0
}

private fun readGridPower(
    master: ModbusTCPMaster
): Int = try {
    master.readMultipleRegisters(1078, 1).map {
        it.value.toShort()
    }.first().toInt()
} catch (e: Exception) {
    Logger.warn(e, "Exception while fetching Modbus data from battery: ")
    0
}


private fun readSolarPanelPower(
    client: OkHttpClient
): Int = try {
    val request = Request.Builder().url(FRONIUS_API_URL).build()
    val body = client.newCall(request).execute().body!!
    JsonParser.parseString(body.string())
        .asJsonObject.get("Body")
        .asJsonObject.get("Data")
        .asJsonObject.get("Inverters")
        .asJsonObject.get("1")
        .asJsonObject.get("P")
        .asInt
} catch (e: Exception) {
    Logger.warn(e, "Exception while fetching HTTP data from inverter: ")
    0
}

private fun readWallboxPower(
    client: OkHttpClient,
): Int = try {
    val request = Request.Builder().url(WALLBOX_POWER_DRAW_URL).build()
    val body = client.newCall(request).execute().body!!
    JsonParser.parseString(body.string())
        .asJsonObject.get("power")
        .asInt
} catch (e: Exception) {
    Logger.warn(e, "Exception while fetching HTTP data from Wallbox: ")
    0
}
