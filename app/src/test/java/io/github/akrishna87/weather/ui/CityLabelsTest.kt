package io.github.akrishna87.weather.ui

import io.github.akrishna87.weather.data.MapCity
import io.github.akrishna87.weather.data.readMapCities
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CityLabelsTest {
    private fun measure(name: String) = 6f * name.length

    @Test
    fun bundledCitiesAppearAtCountryScaleInIndiaAndElsewhere() {
        val cities = File("src/main/assets/cities.tsv").useLines { readMapCities(it) }
        val india = CityLabelViewport(80.0, 20.0, 400f / 60f, 400f, 700f)
        val indiaNames = layoutCityLabels(cities, india, 1f, measure = ::measure).map { it.name }
        assertTrue(indiaNames.containsAll(listOf("Chennai", "Mumbai", "New Delhi", "Kolkata")))

        val southIndia = india.copy(lon = 78.5, lat = 12.0, ppd = 400f / 8f)
        val southIndiaNames = layoutCityLabels(cities, southIndia, 1f, measure = ::measure).map { it.name }
        assertTrue(southIndiaNames.toString(), southIndiaNames.containsAll(listOf("Coimbatore", "Madurai", "Tiruchirappalli")))

        val japan = india.copy(lon = 138.0, lat = 37.0, ppd = 400f / 38f)
        val japanNames = layoutCityLabels(cities, japan, 1f, measure = ::measure).map { it.name }
        assertTrue("Tokyo" in japanNames)
    }

    @Test
    fun viewportHidesSmallCitiesUntilZoomedInAndOmitsLabelsAtWorldScale() {
        val cities = listOf(MapCity("Small city", 0.0, 0.0, 4))
        val view = CityLabelViewport(0.0, 0.0, 10f, 400f, 700f)
        assertTrue(layoutCityLabels(cities, view, 1f, measure = ::measure).isEmpty())
        assertEquals(1, layoutCityLabels(cities, view.copy(ppd = 20f), 1f, measure = ::measure).size)
        val major = cities.map { it.copy(rank = 0) }
        assertTrue(layoutCityLabels(major, view.copy(ppd = 2f), 1f, measure = ::measure).isEmpty())
    }

    @Test
    fun datelineProjectionAndCollisionRespectPriorityAndSavedPlace() {
        val cities = listOf(MapCity("First", 0.0, -179.0, 0), MapCity("Second", 0.0, -179.0, 0))
        val viewport = CityLabelViewport(179.0, 0.0, 10f, 400f, 500f)
        val label = layoutCityLabels(cities, viewport, 1f, measure = ::measure).single()
        assertEquals("First", label.name)
        assertEquals(220f, label.x, 0.001f)
        val saved = LabelBounds(215f, 240f, 270f, 260f)
        assertTrue(layoutCityLabels(cities, viewport, 1f, saved, ::measure).isEmpty())
    }
}
