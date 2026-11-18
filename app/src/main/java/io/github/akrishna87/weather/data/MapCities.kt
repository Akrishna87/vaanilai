package io.github.akrishna87.weather.data

internal data class MapCity(val name: String, val lat: Double, val lon: Double, val rank: Int)

/** The asset is ordered by display priority (national capitals, then larger cities). */
internal fun readMapCities(source: Sequence<String>): List<MapCity> = source
    .filter { it.isNotBlank() && !it.startsWith('#') }
    .map { row ->
        val (rank, lat, lon, name) = row.split('\t', limit = 4)
        MapCity(name, lat.toDouble(), lon.toDouble(), rank.toInt())
    }
    .toList()
