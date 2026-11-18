package io.github.akrishna87.weather.data

import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Locale

/**
 * Named tropical cyclones, from three feeds:
 *  - GDACS (https://www.gdacs.org), the UN / European Commission disaster alert system: every
 *    ocean, with each storm's track and wind areas.
 *  - NOAA's National Hurricane Center (https://www.nhc.noaa.gov): the Atlantic and the East and
 *    Central Pacific, with the latest official intensity, pressure and movement.
 *  - The US Joint Typhoon Warning Center (https://www.metoc.navy.mil/jtwc): every ocean except the
 *    Atlantic, as plain-text warnings. GDACS can be slow or unreachable from some networks, so this
 *    is the fallback — the Bay of Bengal storms it carries are the ones nearest India.
 */
object StormFeeds {
    private const val GDACS = "https://www.gdacs.org/gdacsapi/api"

    suspend fun gdacs(): List<Storm> {
        val today = LocalDate.now(ZoneOffset.UTC)
        val search = "$GDACS/events/geteventlist/SEARCH?eventlist=TC&alertlevel=Green;Orange;Red" +
            "&fromDate=${today.minusDays(14)}&toDate=${today.plusDays(1)}"
        // GDACS's server is slow to first byte (seconds even from a fast network), so give it
        // more than the usual timeout before falling back to the map's own event list.
        val text = try {
            Http.get(search, timeoutMs = 45_000)
        } catch (e: Exception) {
            // The map's own list of current events, in the same format.
            Http.get("$GDACS/events/geteventlist/MAP?eventtypes=TC", timeoutMs = 45_000)
        }
        if (text.isBlank()) return emptyList() // GDACS answers an empty body when nothing matches
        val features = JSONObject(text).optJSONArray("features") ?: return emptyList()
        val cutoff = LocalDateTime.now(ZoneOffset.UTC).minusHours(36)
        val byEvent = LinkedHashMap<String, Storm>()
        for (i in 0 until features.length()) {
            val f = features.optJSONObject(i) ?: continue
            val p = f.optJSONObject("properties") ?: continue
            if (!p.optString("eventtype").equals("TC", true)) continue
            val current = p.optString("iscurrent").equals("true", true)
            val ends = p.str("todate")?.let { runCatching { LocalDateTime.parse(it.take(19)) }.getOrNull() }
            // GDACS can keep "iscurrent" set for days after a storm dies, so go by its last update too.
            if (ends?.isBefore(cutoff) ?: !current) continue
            val coords = f.optJSONObject("geometry")?.optJSONArray("coordinates") ?: continue
            val lon = coords.optDouble(0)
            val lat = coords.optDouble(1)
            if (lat.isNaN() || lon.isNaN()) continue
            val sev = p.optJSONObject("severitydata")
            val sevText = sev?.str("severitytext") ?: ""
            val unit = sev?.optString("severityunit")?.lowercase(Locale.ROOT) ?: ""
            val wind = sev?.dbl("severity")?.let { s ->
                when (unit) {
                    "kt", "knots" -> s * 1.852
                    "mph" -> s * 1.609344
                    else -> s // km/h
                }
            }
            val name = (p.str("eventname") ?: p.str("name")?.substringAfterLast(" ") ?: continue)
                .replace(Regex("""[-\s]\d{2}$"""), "") // "POLO-26" → "POLO"
                .let { titleCase(it) }
            val (kind, level) = kindFromGdacs(sevText, wind, lat, lon)
            val urls = p.optJSONObject("url")
            val eventId = p.optString("eventid")
            val storm = Storm(
                key = "gdacs-$eventId",
                name = name,
                kind = kind,
                level = level,
                lat = lat,
                lon = lon,
                windKmh = wind,
                windIsPeak = true,
                pressureHpa = null,
                movingTowardDeg = null,
                movingKmh = null,
                alertLevel = p.str("alertlevel"),
                updated = p.str("todate"),
                sources = listOf("GDACS"),
                link = urls?.str("report") ?: "https://www.gdacs.org/report.aspx?eventtype=TC&eventid=$eventId",
                geometryUrl = urls?.str("geometry")
                    ?: "$GDACS/polygons/getgeometry?eventtype=TC&eventid=$eventId&episodeid=${p.optString("episodeid")}",
            )
            // Keep the latest episode of each event.
            val old = byEvent[eventId]
            if (old == null || (storm.updated ?: "") >= (old.updated ?: "")) byEvent[eventId] = storm
        }
        return byEvent.values.toList()
    }

    /** A storm's observed and forecast track, plus its wind areas, from GDACS. */
    suspend fun track(geometryUrl: String): StormTrack {
        val fc = JSONObject(Http.get(geometryUrl, timeoutMs = 45_000))
        val features = fc.optJSONArray("features") ?: JSONArray()
        val lines = ArrayList<List<LatLon>>()
        val points = ArrayList<TrackPoint>()
        val areas = ArrayList<List<LatLon>>()
        for (i in 0 until features.length()) {
            val f = features.optJSONObject(i) ?: continue
            val g = f.optJSONObject("geometry") ?: continue
            val c = g.optJSONArray("coordinates") ?: continue
            val props = f.optJSONObject("properties")
            when (g.optString("type")) {
                "Point" -> {
                    val lon = c.optDouble(0); val lat = c.optDouble(1)
                    if (lat.isNaN() || lon.isNaN()) continue
                    val label = props?.let { pr ->
                        listOf("trackdate", "polygondate", "date", "datetime").firstNotNullOfOrNull { pr.str(it) }
                    }
                    val forecast = props?.toString()?.contains("forecast", ignoreCase = true) == true
                    points += TrackPoint(lat, lon, label, forecast)
                }
                "LineString" -> lines += ring(c)
                "MultiLineString" -> for (k in 0 until c.length()) c.optJSONArray(k)?.let { lines += ring(it) }
                "Polygon" -> c.optJSONArray(0)?.let { areas += ring(it, maxPoints = 300) }
                "MultiPolygon" -> for (k in 0 until c.length()) {
                    c.optJSONArray(k)?.optJSONArray(0)?.let { areas += ring(it, maxPoints = 300) }
                }
            }
        }
        // GDACS often sends one position plus a wind area around each position along the track;
        // the centres of those areas mark the track.
        if (points.size <= 1) {
            for (area in areas) {
                val spanLat = area.maxOf { it.lat } - area.minOf { it.lat }
                if (area.size < 3 || spanLat > 15) continue // skip forecast cones and other big shapes
                val c = LatLon(area.sumOf { it.lat } / area.size, area.sumOf { it.lon } / area.size)
                if (points.none { kotlin.math.abs(it.lat - c.lat) < 0.25 && kotlin.math.abs(it.lon - c.lon) < 0.25 }) {
                    points += TrackPoint(c.lat, c.lon, null, forecast = false)
                }
            }
        }
        return StormTrack(lines.filter { it.size > 1 }, points, areas.filter { it.size > 2 })
    }

    private fun ring(c: JSONArray, maxPoints: Int = 2000): List<LatLon> {
        val step = maxOf(1, c.length() / maxPoints)
        return (0 until c.length() step step).mapNotNull { k ->
            val p = c.optJSONArray(k) ?: return@mapNotNull null
            LatLon(p.optDouble(1), p.optDouble(0)).takeIf { !it.lat.isNaN() && !it.lon.isNaN() }
        }
    }

    /**
     * The US Joint Typhoon Warning Center's active tropical cyclones. Its feed is a small index of
     * links, one per storm, to that storm's plain-text warning; each warning is fetched and parsed.
     * Covers every ocean except the Atlantic (which the NHC covers).
     */
    suspend fun jtwc(): List<Storm> {
        val rss = Http.get("https://www.metoc.navy.mil/jtwc/rss/jtwc.rss")
        // Links look like .../products/io0526web.txt; the "significant tropical weather" advisories
        // (abioweb.txt and friends) are also *web.txt, so require the per-storm id (letters+digits).
        val warnings = Regex("""https://www\.metoc\.navy\.mil/jtwc/products/[a-z]{2}\d{4}web\.txt""")
            .findAll(rss).map { it.value }.distinct().toList()
        // One storm's warning being slow or unreadable shouldn't sink the whole feed.
        return warnings.mapNotNull { url ->
            val text = try {
                Http.get(url)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@mapNotNull null
            }
            parseJtwcWarning(text, url)
        }
    }

    /** One JTWC plain-text warning as a storm; null when it can't be read (say an unnamed system). */
    fun parseJtwcWarning(text: String, url: String): Storm? {
        val subj = text.lineSequence().firstOrNull { it.contains("SUBJ/") } ?: return null
        val name = Regex("""\(([A-Z][A-Z0-9-]*)\)\s+WARNING""").find(subj)?.groupValues?.get(1) ?: return null
        val pos = Regex("""NEAR\s+([0-9.]+)([NS])\s+([0-9.]+)([EW])""").find(text) ?: return null
        val lat = pos.groupValues[1].toDouble() * if (pos.groupValues[2] == "S") -1.0 else 1.0
        val lon = pos.groupValues[3].toDouble() * if (pos.groupValues[4] == "W") -1.0 else 1.0
        // JTWC reports one-minute-average winds; the present one is the first "MAX SUSTAINED WINDS".
        val wind = Regex("""MAX SUSTAINED WINDS\s*-\s*(\d+)\s*KT""").find(text)
            ?.groupValues?.get(1)?.toDoubleOrNull()?.times(1.852)
        val move = Regex("""MOVEMENT PAST SIX HOURS\s*-\s*(\d{3})\s*DEGREES\s+AT\s*(\d+)""").find(text)
        val cat = wind?.let(::categoryFor)
        // The forecast positions after the current one: "12 HRS, VALID AT: 171800Z --- 12.5N 86.8E".
        val points = ArrayList<TrackPoint>()
        points += TrackPoint(lat, lon, null, forecast = false)
        for (m in Regex("""(\d+) HRS, VALID AT:\s*\r?\n\s*\d{6}Z --- ([0-9.]+)([NS])\s+([0-9.]+)([EW])""").findAll(text)) {
            val fLat = m.groupValues[2].toDouble() * if (m.groupValues[3] == "S") -1.0 else 1.0
            val fLon = m.groupValues[4].toDouble() * if (m.groupValues[5] == "W") -1.0 else 1.0
            points += TrackPoint(fLat, fLon, "+${m.groupValues[1]}h", forecast = true)
        }
        val id = Regex("""([a-z]{2}\d{4})web""").find(url)?.groupValues?.get(1)
        return Storm(
            key = "jtwc-${id ?: name.lowercase(Locale.ROOT)}",
            name = titleCase(name),
            kind = when (cat) {
                null -> "Tropical Cyclone"
                in 1..5 -> "${regionalName(lat, lon)} · Category $cat"
                0 -> "Tropical Storm"
                else -> "Tropical Depression"
            },
            lat = lat,
            lon = lon,
            windKmh = wind,
            windIsPeak = false,
            pressureHpa = null, // JTWC's warning text doesn't carry the central pressure
            movingTowardDeg = move?.groupValues?.get(1)?.toDoubleOrNull(),
            movingKmh = move?.groupValues?.get(2)?.toDoubleOrNull()?.times(1.852), // JTWC gives knots
            alertLevel = null,
            updated = Regex("""(\d{6})Z --- NEAR""").find(text)?.groupValues?.get(1)?.let(::utcIsoFromDayHourMin),
            sources = listOf("JTWC"),
            link = url,
            geometryUrl = null, // the track above is already parsed from the warning text
            track = if (points.size > 1) StormTrack(
                lines = listOf(points.map { LatLon(it.lat, it.lon) }),
                points = points,
                areas = emptyList(),
            ) else null,
        )
    }

    /** "170600" (day, hour, minute UTC) as an ISO timestamp. Warnings are current, so look back at
     *  most a month for the day that matches — handles month rollovers without a year in the feed. */
    private fun utcIsoFromDayHourMin(dhm: String): String? {
        val d = dhm.take(2).toIntOrNull() ?: return null
        val h = dhm.substring(2, 4).toIntOrNull() ?: return null
        val m = dhm.takeLast(2).toIntOrNull() ?: return null
        if (d !in 1..31 || h !in 0..23 || m !in 0..59) return null
        val today = LocalDate.now(ZoneOffset.UTC)
        val date = (0L..31L).map { today.minusDays(it) }.firstOrNull { it.dayOfMonth == d } ?: return null
        // Locale.ROOT so a phone in another locale still gets ASCII digits in the ISO string.
        return String.format(Locale.ROOT, "%04d-%02d-%02dT%02d:%02d:00Z",
            date.year, date.monthValue, date.dayOfMonth, h, m)
    }

    suspend fun nhc(): List<Storm> {
        val text = Http.get("https://www.nhc.noaa.gov/CurrentStorms.json")
        val active = JSONObject(text).optJSONArray("activeStorms") ?: return emptyList()
        return (0 until active.length()).mapNotNull { i ->
            val s = active.optJSONObject(i) ?: return@mapNotNull null
            val lat = s.dbl("latitudeNumeric") ?: return@mapNotNull null
            val lon = s.dbl("longitudeNumeric") ?: return@mapNotNull null
            val windKt = s.str("intensity")?.toDoubleOrNull()
            val cls = s.optString("classification").uppercase(Locale.ROOT)
            val wind = windKt?.times(1.852)
            Storm(
                key = "nhc-${s.optString("id")}",
                name = titleCase(s.optString("name")),
                kind = when (cls) {
                    "HU" -> wind?.let { "Hurricane · Category ${categoryFor(it).coerceAtLeast(1)}" } ?: "Hurricane"
                    "TS" -> "Tropical Storm"
                    "TD" -> "Tropical Depression"
                    "STS" -> "Subtropical Storm"
                    "STD" -> "Subtropical Depression"
                    "PTC" -> "Post-Tropical Cyclone"
                    "PC" -> "Potential Tropical Cyclone"
                    else -> cls.ifBlank { "Tropical Cyclone" }
                },
                lat = lat,
                lon = lon,
                windKmh = wind,
                windIsPeak = false,
                pressureHpa = s.str("pressure")?.toDoubleOrNull(),
                movingTowardDeg = s.dbl("movementDir"),
                movingKmh = s.dbl("movementSpeed")?.times(1.609344), // NHC gives mph
                alertLevel = null,
                updated = s.str("lastUpdate"),
                sources = listOf("NHC"),
                link = s.optJSONObject("publicAdvisory")?.str("url") ?: "https://www.nhc.noaa.gov/",
                geometryUrl = null,
            )
        }
    }

    /**
     * One entry per storm: NHC's current figures where it has them, with GDACS's track and
     * alert level. JTWC only adds storms the other two don't already have — it's the fallback
     * for when GDACS is unreachable. Strongest first.
     */
    fun merge(gdacs: List<Storm>, nhc: List<Storm>, jtwc: List<Storm> = emptyList()): List<Storm> {
        val left = gdacs.toMutableList()
        val merged = nhc.map { n ->
            val g = left.firstOrNull {
                it.name.equals(n.name, true) && distanceKm(LatLon(it.lat, it.lon), LatLon(n.lat, n.lon)) < 1500
            }
            if (g == null) n else {
                left.remove(g)
                n.copy(alertLevel = g.alertLevel, geometryUrl = g.geometryUrl, sources = n.sources + g.sources)
            }
        }
        val fromFeeds = merged + left
        val extra = jtwc.filter { j ->
            fromFeeds.none { it.name.equals(j.name, true) && distanceKm(LatLon(it.lat, it.lon), LatLon(j.lat, j.lon)) < 1500 }
        }
        return (fromFeeds + extra).sortedByDescending { it.windKmh ?: 0.0 }
    }

    /**
     * GDACS describes the storm's current class in words ("Tropical Depression", "Category 3",
     * "Hurricane/Typhoon > 74 mph") but gives only its peak wind; turn that into a clean name and
     * a current level, so a storm that has weakened isn't coloured by its peak.
     */
    private fun kindFromGdacs(severityText: String, wind: Double?, lat: Double, lon: Double): Pair<String, Int?> {
        val label = severityText.substringBefore("(").trim().lowercase(Locale.ROOT)
        val regional = regionalName(lat, lon)
        val category = Regex("""category\s*(\d)""").find(label)?.groupValues?.get(1)?.toIntOrNull()
        return when {
            "depression" in label -> "Tropical Depression" to -1
            "tropical storm" in label -> "Tropical Storm" to 0
            category != null -> "$regional · Category $category" to category
            "hurricane" in label || "typhoon" in label || "cyclone" in label ->
                regional to (wind?.let { categoryFor(it) } ?: 1).coerceAtLeast(1)
            wind == null -> "Tropical Cyclone" to null
            categoryFor(wind) >= 1 -> "$regional · Category ${categoryFor(wind)}" to categoryFor(wind)
            categoryFor(wind) == 0 -> "Tropical Storm" to 0
            else -> "Tropical Depression" to -1
        }
    }

    private fun titleCase(s: String) = s.trim().lowercase(Locale.ROOT).split(" ", "-")
        .joinToString(" ") { w -> w.replaceFirstChar { it.titlecase(Locale.ROOT) } }
}
