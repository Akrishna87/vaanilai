package io.github.akrishna87.weather.ui

import io.github.akrishna87.weather.data.MapCity
import kotlin.math.round

internal data class CityLabelViewport(
    val lon: Double,
    val lat: Double,
    val ppd: Float,
    val width: Float,
    val height: Float,
) {
    // Use the nearest copy of a longitude, including after panning across the date line.
    fun x(lon: Double): Float {
        val delta = lon - this.lon
        return width / 2f + ((delta - 360 * round(delta / 360)) * ppd).toFloat()
    }

    fun y(lat: Double): Float = height / 2f + ((this.lat - lat) * ppd).toFloat()
}

internal data class LabelBounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun overlaps(other: LabelBounds): Boolean =
        left < other.right && right > other.left && top < other.bottom && bottom > other.top
}

internal data class CityLabel(val name: String, val x: Float, val y: Float, val textX: Float)

internal fun layoutCityLabels(
    cities: List<MapCity>,
    view: CityLabelViewport,
    density: Float,
    reserved: LabelBounds? = null,
    measure: (String) -> Float,
): List<CityLabel> {
    val span = view.width / view.ppd
    val maxRank = when {
        span <= 12f -> Int.MAX_VALUE
        span <= 28f -> 4
        span <= 45f -> 3
        span <= 75f -> 2
        else -> return emptyList()
    }
    val used = mutableListOf<LabelBounds>()
    if (reserved != null) used.add(reserved)
    val gap = 5f * density
    val pad = 4f * density
    return buildList {
        for (city in cities) {
            if (city.rank > maxRank) continue
            val x = view.x(city.lon)
            val y = view.y(city.lat)
            if (x !in pad..(view.width - pad) || y !in pad..(view.height - pad)) continue

            val textWidth = measure(city.name)
            // Prefer the right of the marker; try the left when another label is in the way.
            for (side in 0..1) {
                val onRight = side == 0
                val textX = if (onRight) x + gap else x - gap - textWidth
                val bounds = LabelBounds(
                    (if (onRight) x else textX) - pad, y - 9f * density,
                    (if (onRight) textX + textWidth else x) + pad, y + 7f * density,
                )
                if (bounds.left < 0 || bounds.right > view.width ||
                    bounds.top < 0 || bounds.bottom > view.height || used.any { it.overlaps(bounds) }
                ) continue
                used.add(bounds)
                add(CityLabel(city.name, x, y, textX))
                break
            }
        }
    }
}
