package com.fitnessapp.summary.garmin

import com.fitnessapp.summary.data.ScaleMeasurement
import com.fitnessapp.summary.debug.AppLog
import com.garmin.fit.BufferEncoder
import com.garmin.fit.DateTime
import com.garmin.fit.File
import com.garmin.fit.FileIdMesg
import com.garmin.fit.Fit
import com.garmin.fit.Manufacturer
import com.garmin.fit.WeightScaleMesg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * Pushes scale weigh-ins INTO Garmin Connect - the one place this app writes to Garmin.
 *
 * Everything else in garmin/ reads. This exists because the user's scale is not a Garmin
 * one and Garmin has no other way to learn its readings; it is off by default for anyone
 * who hasn't logged into the scale's cloud, and gated by an explicit switch for those who
 * have (see ZeppTokenStore.uploadToGarmin).
 *
 * The mechanism is Garmin's own import path, the same one the Garmin Connect app and
 * every third-party scale bridge use: a FIT file of type WEIGHT posted as multipart to
 * `upload-service/upload`. Endpoint, file shape and field mapping follow SmartScaleConnect
 * (`pkg/garmin/client.go`, `pkg/garmin/fit/fit.go`, MIT): file_id with type=weight,
 * manufacturer=garmin, product=2429 (Garmin's scale product code), any serial; one
 * `weight_scale` message per weigh-in. The FIT bytes themselves come from Garmin's
 * official FIT SDK (`com.garmin:fit`), whose setters take physical units and apply the
 * format's scaling internally - so weight goes in as kilograms, not kg*100.
 *
 * What is deliberately NOT sent: the scale's muscle mass. Xiaomi reports total muscle
 * (~75-80% of body weight); Garmin's `muscle_mass` field is skeletal muscle (~40%), and
 * SmartScaleConnect leaves it out for the same reason. It stays in scale_measurements.
 */
class GarminWeightUploader(private val auth: GarminAuthClient) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * Uploads [measurements] as one FIT file. Callers chunk to at most [MAX_PER_FILE] -
     * Garmin rejects large weight files (SmartScaleConnect's finding, mirrored here).
     * Ok carries the count; a 409 from Garmin means "already imported" and counts as Ok.
     */
    suspend fun upload(measurements: List<ScaleMeasurement>): GarminFetch<Int> = withContext(Dispatchers.IO) {
        if (measurements.isEmpty()) return@withContext GarminFetch.Ok(0)
        require(measurements.size <= MAX_PER_FILE) { "Garmin отклоняет большие FIT-файлы с весом: ${measurements.size} > $MAX_PER_FILE" }

        val authHeader = auth.ensureAuthorizationHeader()
            ?: return@withContext GarminFetch.Failed("нет действующего токена Garmin", isNetwork = false)

        val bytes = try {
            encodeWeightFit(measurements)
        } catch (e: Exception) {
            AppLog.e("GarminWeightUploader", "Не удалось собрать FIT-файл", e)
            return@withContext GarminFetch.Failed("FIT: ${e.message ?: e.javaClass.simpleName}", isNetwork = false)
        }

        val filename = "scale-${measurements.first().timestampMillis / 1000}.fit"
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", filename, bytes.toRequestBody("application/octet-stream".toMediaType()))
            .build()
        val request = Request.Builder()
            .url("https://connectapi.${GarminApiClient.DOMAIN}/upload-service/upload")
            .post(body)
            .header("User-Agent", GarminApiClient.DATA_USER_AGENT)
            .header("Authorization", authHeader)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                when (response.code) {
                    200, 201, 202 -> {
                        AppLog.i("GarminWeightUploader", "В Garmin загружено взвешиваний: ${measurements.size} (HTTP ${response.code})")
                        GarminFetch.Ok(measurements.size)
                    }
                    409 -> {
                        AppLog.i("GarminWeightUploader", "Garmin уже держит этот файл (409) - считаем загруженным: ${measurements.size}")
                        GarminFetch.Ok(measurements.size)
                    }
                    else -> {
                        AppLog.w("GarminWeightUploader", "upload-service/upload - HTTP ${response.code}: ${text.take(300)}")
                        GarminFetch.Failed("Garmin отклонил загрузку: HTTP ${response.code}", isNetwork = false)
                    }
                }
            }
        } catch (e: IOException) {
            AppLog.w("GarminWeightUploader", "Не удалось загрузить FIT в Garmin", e)
            GarminFetch.Failed(e.message ?: e.javaClass.simpleName, isNetwork = true)
        }
    }

    private fun encodeWeightFit(measurements: List<ScaleMeasurement>): ByteArray {
        val encoder = BufferEncoder(Fit.ProtocolVersion.V2_0)

        val fileId = FileIdMesg()
        fileId.type = File.WEIGHT
        fileId.manufacturer = Manufacturer.GARMIN
        fileId.product = SCALE_PRODUCT
        fileId.serialNumber = SERIAL_NUMBER
        fileId.timeCreated = DateTime(Date())
        encoder.write(fileId)

        for (m in measurements) {
            val mesg = WeightScaleMesg()
            mesg.timestamp = DateTime(Date(m.timestampMillis))
            mesg.weight = m.weightKg
            if (m.bmi > 0f) mesg.bmi = m.bmi
            if (m.bodyFatPercent > 0f) mesg.percentFat = m.bodyFatPercent
            if (m.bodyWaterPercent > 0f) mesg.percentHydration = m.bodyWaterPercent
            if (m.boneMassGrams > 0) mesg.boneMass = m.boneMassGrams / 1000f
            if (m.metabolicAge > 0) mesg.metabolicAge = m.metabolicAge.toShort()
            if (m.physiqueRating > 0) mesg.physiqueRating = m.physiqueRating.toShort()
            if (m.visceralFat > 0) mesg.visceralFatRating = m.visceralFat.toShort()
            if (m.basalMetabolismKcal > 0) mesg.basalMet = m.basalMetabolismKcal.toFloat()
            encoder.write(mesg)
        }
        return encoder.close()
    }

    companion object {
        const val MAX_PER_FILE = 200
        /** Garmin's product code for its own scale line - what SmartScaleConnect stamps on the file. */
        private const val SCALE_PRODUCT = 2429
        private const val SERIAL_NUMBER = 1234L
    }
}
