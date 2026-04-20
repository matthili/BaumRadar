package at.mafue.baumradar.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import at.mafue.baumradar.app.data.CityCatalogEntry
import at.mafue.baumradar.app.data.CityManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class CitySelectionViewModel(application: Application) : AndroidViewModel(application) {
    private val cityManager = CityManager(application)
    
    private val _catalog = MutableStateFlow<List<CityCatalogEntry>>(emptyList())
    val catalog: StateFlow<List<CityCatalogEntry>> = _catalog
    
    private val _downloadedCities = MutableStateFlow<Set<String>>(emptySet())
    val downloadedCities: StateFlow<Set<String>> = _downloadedCities
    
    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _downloadProgress = MutableStateFlow<String?>(null)
    val downloadProgress: StateFlow<String?> = _downloadProgress

    init {
        loadCatalog()
    }

    private fun loadCatalog() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val entries = cityManager.getCatalog()
                _catalog.value = entries
                refreshDownloadedStatus(entries)
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun refreshDownloadedStatus(entries: List<CityCatalogEntry>) {
        val downloaded = entries.filter { cityManager.isCityDownloaded(it.id) }.map { it.id }.toSet()
        _downloadedCities.value = downloaded
    }

    fun toggleCity(city: CityCatalogEntry) {
        viewModelScope.launch {
            if (cityManager.isCityDownloaded(city.id)) {
                cityManager.deleteCity(city.id)
                refreshDownloadedStatus(_catalog.value)
            } else {
                _downloadProgress.value = "Starting download..."
                val success = cityManager.downloadAndMergeCity(city) { msg ->
                    _downloadProgress.value = msg
                }
                _downloadProgress.value = null
                refreshDownloadedStatus(_catalog.value)
            }
        }
    }
}
