package app.orchard.tv.system

import android.app.Application
import android.content.Context
import app.orchard.tv.settings.AerialSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * What the anonymous install line promises: one random name per TV, kept; nothing sent while no
 * address was given at build time; and an answer that stays answered.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class UsageReportTest {
    private val application: Application = RuntimeEnvironment.getApplication()

    @Test
    fun installIdIsMadeOnceAndKept() {
        val first = UsageReport.installId(application)
        assertEquals(first, UsageReport.installId(application))
        // A UUID, and nothing derived from the TV: no serial, no MAC, no package name in it.
        assertEquals(36, first.length)
        assertFalse(first.contains(application.packageName))
    }

    @Test
    fun nothingIsSentWithoutAnAddress() {
        // The test build sets no REPORT_URL, which is what ships: the room for an address is empty.
        assertFalse(UsageReport.configured)
        assertEquals(UsageReport.Sent.Off, UsageReport.upload(application))
    }

    @Test
    fun theAnswerToTheConsentCardIsKept() {
        AerialSettings.init(application)
        AerialSettings.chooseReportConsent(false)
        assertEquals(false, AerialSettings.reportConsent)
        AerialSettings.chooseReportConsent(true)
        assertTrue(AerialSettings.reportConsent == true)
        assertEquals(
            "yes",
            application.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("report_consent", null),
        )
    }
}
