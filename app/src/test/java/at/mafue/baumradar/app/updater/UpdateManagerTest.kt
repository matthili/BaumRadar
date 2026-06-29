package at.mafue.baumradar.app.updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regressionstests für die Versionsvergleichs-Logik des Auto-Updaters.
 *
 * Hintergrund: Die Release-Tags dieses Projekts verwenden ein GROSSES „V"-Präfix
 * (z. B. „V1.5"). Eine frühere Implementierung entfernte mit `removePrefix("v")`
 * nur ein kleines „v", wodurch „V1.5" beim Zerlegen zu [5] wurde und fälschlich als
 * neuer galt – der Updater bot endlos ein vermeintliches Update an, obwohl die
 * installierte Version identisch war.
 */
class UpdateManagerTest {

    @Test
    fun `normalizeVersion entfernt grosses und kleines v-Praefix`() {
        assertEquals("1.5", UpdateManager.normalizeVersion("V1.5"))
        assertEquals("1.5", UpdateManager.normalizeVersion("v1.5"))
        assertEquals("1.5", UpdateManager.normalizeVersion("1.5"))
        assertEquals("1.1.1", UpdateManager.normalizeVersion("  V1.1.1  "))
    }

    @Test
    fun `gleiche Version mit V-Tag ist KEIN Update (der eigentliche Bug)`() {
        // App 1.5 darf bei Release-Tag V1.5 NIEMALS ein Update sehen.
        assertFalse(UpdateManager.isRemoteNewer("V1.5", "1.5"))
        assertFalse(UpdateManager.isRemoteNewer("v1.5", "1.5"))
        assertFalse(UpdateManager.isRemoteNewer("1.5", "1.5"))
        assertFalse(UpdateManager.isRemoteNewer("V1.5.0", "1.5")) // 1.5.0 == 1.5
    }

    @Test
    fun `neuere Remote-Version wird erkannt`() {
        assertTrue(UpdateManager.isRemoteNewer("V1.1.1", "1.0"))
        assertTrue(UpdateManager.isRemoteNewer("V1.5", "1.1.1"))
        assertTrue(UpdateManager.isRemoteNewer("v1.6", "1.5"))
        assertTrue(UpdateManager.isRemoteNewer("V2.0", "1.9.9"))
    }

    @Test
    fun `aeltere Remote-Version ist kein Update`() {
        assertFalse(UpdateManager.isRemoteNewer("V1.4.9", "1.5"))
        assertFalse(UpdateManager.isRemoteNewer("V1.0", "1.5"))
        assertFalse(UpdateManager.isRemoteNewer("V1.1.1", "1.5"))
    }
}
