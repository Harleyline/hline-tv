package app.orchard.tv.weather

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The weather's towns: the ones the owner adds in Personalize are capped at two, survive a restart
 * through the prefs, and the rotation walks them in order. One flow, in order: Weather is a
 * singleton whose in-memory state one test cannot reset for the next.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WeatherCitiesTest {

    private val context: Context = RuntimeEnvironment.getApplication()

    private fun city(name: String, lat: Double, lon: Double) =
        Cities.City(name, emptyList(), "浙江", "杭州", lat, lon, 0, "Test$name")

    @Test fun theTownsFlow() {
        context.getSharedPreferences("weather", Context.MODE_PRIVATE).edit().clear().commit()
        Weather.init(context)
        val prefs = context.getSharedPreferences("weather", Context.MODE_PRIVATE)

        // Nothing added: no rotation, the cursor stays put.
        assertFalse(Weather.rotating)
        Weather.advance()
        assertEquals(0, Weather.cursor)

        // Three towns in (the owner asked for three beside the located one), the fourth refused.
        assertTrue(Weather.addCity(city("杭州", 30.27, 120.15)))
        assertTrue(Weather.addCity(city("北京", 39.90, 116.39)))
        assertTrue(Weather.addCity(city("广州", 23.12, 113.25)))
        assertFalse(Weather.addCity(city("上海", 31.22, 121.45)))
        assertEquals(listOf("杭州", "北京", "广州"), Weather.extras.map { it.name })
        assertTrue(Weather.extras.size == Weather.MAX_EXTRA_CITIES)

        // Written for the next start: the names and the coordinates.
        val saved = prefs.getString("extras", null)!!
        assertTrue(saved.contains("杭州\tTest杭州\t30.27\t120.15"))
        assertTrue(saved.contains("北京\tTest北京\t39.9\t116.39"))

        // The rotation walks the towns in the order added, then round again. Nothing is located
        // here, so the three added ones are the whole board.
        assertTrue(Weather.rotating)
        assertEquals(listOf("杭州", "北京", "广州"), Weather.slots.map { it.name })
        Weather.advance()
        assertEquals(1, Weather.cursor)
        assertEquals("北京", Weather.shown?.name)
        Weather.advance()
        assertEquals(2, Weather.cursor)
        assertEquals("广州", Weather.shown?.name)
        Weather.advance()
        assertEquals(0, Weather.cursor)
        assertEquals("杭州", Weather.shown?.name)
        // Taking one out leaves the other two turning, back from the first.
        Weather.removeCity("北京")
        assertEquals(listOf("杭州", "广州"), Weather.extras.map { it.name })
        assertEquals(0, Weather.cursor)
        assertTrue(Weather.rotating)

        // The place reads in the language it is spoken in.
        val slot = CitySlot("杭州", "Hangzhou", 30.27f, 120.15f)
        assertEquals("杭州", Weather.placeName(slot, "zh"))
        assertEquals("杭州", Weather.placeName(slot, "ja"))
        assertEquals("Hangzhou", Weather.placeName(slot, "en"))
        assertEquals("Hangzhou", Weather.placeName(slot, "ko"))
        assertNull(Weather.placeName(null, "zh"))
    }
}
