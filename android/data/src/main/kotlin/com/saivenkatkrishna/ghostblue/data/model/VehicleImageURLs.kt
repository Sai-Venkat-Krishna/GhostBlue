package com.saivenkatkrishna.ghostblue.data.model

/**
 * The renders the iOS app shows (`HomeVehicleImageView`): one side view per
 * model and paint colour, shared by the phone, its widgets and the watch.
 */
object VehicleImageURLs {
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

    /** Same URL as iOS, double slash included. */
    fun url(modelName: String, color: VehicleImageColor): String =
        "https://bluelink.andyfase.com/app-assets/car-images//${modelKey(modelName)}/${color.rawValue}.png"
}
