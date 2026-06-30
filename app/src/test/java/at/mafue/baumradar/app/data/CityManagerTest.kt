package at.mafue.baumradar.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the pure stale-data comparison that decides whether a downloaded city's
 * tree data needs refreshing ([CityManager.isDataStale]).
 */
class CityManagerTest {

    @Test
    fun notDownloadedIsNeverStale() {
        assertFalse(CityManager.isDataStale(downloaded = false, localVersion = "a", remoteVersion = "b"))
        assertFalse(CityManager.isDataStale(downloaded = false, localVersion = null, remoteVersion = "b"))
    }

    @Test
    fun missingRemoteVersionIsNotStale() {
        // Backend shipped no dataVersion → do not force a refresh.
        assertFalse(CityManager.isDataStale(downloaded = true, localVersion = "a", remoteVersion = null))
        assertFalse(CityManager.isDataStale(downloaded = true, localVersion = "a", remoteVersion = ""))
    }

    @Test
    fun equalVersionIsUpToDate() {
        assertFalse(CityManager.isDataStale(downloaded = true, localVersion = "abc123", remoteVersion = "abc123"))
    }

    @Test
    fun differingVersionIsStale() {
        assertTrue(CityManager.isDataStale(downloaded = true, localVersion = "old", remoteVersion = "new"))
    }

    @Test
    fun legacyDownloadWithoutLocalVersionIsStale() {
        // Downloaded before versioning existed (no stored version) → refresh once.
        assertTrue(CityManager.isDataStale(downloaded = true, localVersion = null, remoteVersion = "v2"))
    }
}
