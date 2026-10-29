package com.saivenkatkrishna.ghostblue.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImagePainter
import coil3.compose.AsyncImage
import com.saivenkatkrishna.ghostblue.data.model.VehicleImageColor

/** The renders the iOS app shows: one side view per model and paint colour. */
object VehicleImages {
    // First match wins, so the longer names come first.
    private val modelImageKeys = listOf(
        "ioniq5n" to "ioniq5n", "ioniq5" to "ioniq5", "ioniq6" to "ioniq6", "ioniq9" to "ioniq9",
        "ioniq" to "ioniq", "ev6" to "ev6", "ev9" to "ev9", "kona" to "kona", "niro" to "niro",
    )

    /** Width over height of a render, before one has loaded. */
    const val ASPECT_RATIO = 400f / 157f

    fun modelKey(modelName: String): String {
        val normalized = modelName.replace(Regex("""^\d{4}\s+"""), "")
            .lowercase().replace(" ", "").replace("-", "")
        return modelImageKeys.firstOrNull { normalized.contains(it.first) }?.second ?: "ioniq5"
    }

    /** Same URL as iOS's `HomeVehicleImageView`, double slash included. */
    fun url(modelName: String, color: VehicleImageColor): String =
        "https://bluelink.andyfase.com/app-assets/car-images//${modelKey(modelName)}/${color.rawValue}.png"

    /** Swatch colours for the paint picker, from iOS's Vehicle Center. */
    fun swatch(color: VehicleImageColor): Color = when (color) {
        VehicleImageColor.WHITE -> Color(0xFFF2F2F2)
        VehicleImageColor.BLACK -> Color(0xFF262626)
        VehicleImageColor.GREY -> Color(0xFF8C8C8C)
        VehicleImageColor.BLUE -> Color(0xFF3373D9)
        VehicleImageColor.GREEN -> Color(0xFF2EAD4D)
        VehicleImageColor.RED -> Color(0xFFD92626)
        VehicleImageColor.SILVER -> Color(0xFFC7C7CC)
        VehicleImageColor.MATTE_GREY -> Color(0xFF6B7075)
        VehicleImageColor.METALLIC_GREY -> Color(0xFF7A808A)
        VehicleImageColor.ORANGE -> Color(0xFFF25914)
    }
}

/** False draws the stand-in instead of fetching renders: previews and tests. */
val LocalRemoteImages = staticCompositionLocalOf { true }

/**
 * The car in its chosen colour. Coil caches renders on disk; when a colour
 * has no render the white one is shown, and with neither a car icon.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun VehicleImage(model: String, color: VehicleImageColor, modifier: Modifier = Modifier, description: String = model) {
    val primary = VehicleImages.url(model, color)
    val fallback = VehicleImages.url(model, VehicleImageColor.WHITE)
    var url by remember(primary) { mutableStateOf(primary) }
    val placeholder = @Composable {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(
                Icons.Rounded.DirectionsCar,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                modifier = Modifier.size(72.dp),
            )
        }
    }
    Box(modifier.aspectRatio(VehicleImages.ASPECT_RATIO).semantics { contentDescription = description }) {
        if (LocalInspectionMode.current || !LocalRemoteImages.current) {
            placeholder()
            return@Box
        }
        var state by remember(url) { mutableStateOf<AsyncImagePainter.State>(AsyncImagePainter.State.Empty) }
        AsyncImage(
            model = url,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
            onState = { next ->
                state = next
                if (next is AsyncImagePainter.State.Error && url != fallback) url = fallback
            },
        )
        when (state) {
            is AsyncImagePainter.State.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                LoadingIndicator(Modifier.padding(8.dp).size(40.dp))
            }
            is AsyncImagePainter.State.Error -> placeholder()
            else -> {}
        }
    }
}
