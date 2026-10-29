package com.saivenkatkrishna.ghostblue.wear.complication

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import com.saivenkatkrishna.ghostblue.data.model.BBVehicle
import com.saivenkatkrishna.ghostblue.kit.model.Distance
import com.saivenkatkrishna.ghostblue.wear.R
import com.saivenkatkrishna.ghostblue.wear.WearMainActivity
import com.saivenkatkrishna.ghostblue.wear.wearGraph

/** What a complication says for a car: iOS's Range Ring (battery and range). */
data class ComplicationContent(val name: String, val percent: Float?, val range: String?, val charging: Boolean, val vin: String?) {
    val percentText: String get() = percent?.let { "${it.toInt()}%" } ?: "--"
    val longText: String get() = listOfNotNull(name, percentText, range).joinToString(" · ")

    companion object {
        fun from(vehicle: BBVehicle?, unit: Distance.Units): ComplicationContent {
            val range = vehicle?.evStatus?.evRange ?: vehicle?.gasRange
            return ComplicationContent(
                name = vehicle?.displayName ?: "GhostBlue",
                percent = range?.percentage?.toFloat(),
                range = range?.let { it.range.units.format(it.range.length, unit) },
                charging = vehicle?.evStatus?.charging == true,
                vin = vehicle?.vin,
            )
        }

        val preview = ComplicationContent("IONIQ 5", 84f, "252 mi", charging = false, vin = null)
    }
}

/**
 * Watch-face complications for the selected car, as iOS's watch
 * complications: the battery as a ranged value, short text with the range,
 * or a long line with the name. Tapping one opens the car.
 */
class VehicleComplicationService : SuspendingComplicationDataSourceService() {
    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val graph = applicationContext.wearGraph
        val vehicles = graph.db.vehicles().all().filter { !it.isHidden }
        val vehicle = vehicles.firstOrNull { it.vin == graph.settings.selectedVin.value } ?: vehicles.firstOrNull()
        return data(this, request.complicationType, ComplicationContent.from(vehicle, graph.settings.distanceUnit.value))
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? = data(this, type, ComplicationContent.preview)

    companion object {
        fun data(context: Context, type: ComplicationType, content: ComplicationContent): ComplicationData? {
            val description = PlainComplicationText.Builder("${content.name} battery ${content.percentText}").build()
            val icon = MonochromaticImage.Builder(
                Icon.createWithResource(context, if (content.charging) R.drawable.ic_bolt else R.drawable.ic_notification),
            ).build()
            val tap = PendingIntent.getActivity(
                context,
                // One per car, so the preview (no car) or another car's complication can't replace its extra.
                content.vin?.hashCode() ?: 0,
                Intent(context, WearMainActivity::class.java).putExtra(WearMainActivity.EXTRA_VIN, content.vin),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            return when (type) {
                ComplicationType.RANGED_VALUE -> RangedValueComplicationData.Builder(content.percent ?: 0f, 0f, 100f, description)
                    .setText(PlainComplicationText.Builder(content.percentText).build())
                    .setMonochromaticImage(icon)
                    .setTapAction(tap)
                    .build()
                ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(PlainComplicationText.Builder(content.percentText).build(), description)
                    .apply { content.range?.let { setTitle(PlainComplicationText.Builder(it).build()) } }
                    .setMonochromaticImage(icon)
                    .setTapAction(tap)
                    .build()
                ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(PlainComplicationText.Builder(content.longText).build(), description)
                    .setMonochromaticImage(icon)
                    .setTapAction(tap)
                    .build()
                else -> null
            }
        }
    }
}
