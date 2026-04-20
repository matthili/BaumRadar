package at.mafue.baumradar.app.ui

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CitySelectionScreen(isWizard: Boolean, onWizardComplete: () -> Unit) {
    val context = LocalContext.current.applicationContext as Application
    val viewModel: CitySelectionViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return CitySelectionViewModel(context) as T
            }
        }
    )

    val catalog by viewModel.catalog.collectAsState()
    val downloaded by viewModel.downloadedCities.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val downloadProgress by viewModel.downloadProgress.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(if (isWizard) "Willkommen bei BaumRadar" else "Städte verwalten") })
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else {
                Column {
                    if (isWizard) {
                        Text(
                            text = "Bitte wähle mindestens eine Stadt aus, für die du die Baum-Daten herunterladen möchtest.",
                            modifier = Modifier.padding(16.dp)
                        )
                    }

                    LazyColumn(modifier = Modifier.weight(1f)) {
                        items(catalog) { city ->
                            val isDownloaded = downloaded.contains(city.id)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(text = city.name, style = MaterialTheme.typography.titleMedium)
                                    Text(text = if (isDownloaded) "Installiert" else "Nicht installiert", style = MaterialTheme.typography.bodySmall)
                                }
                                Switch(
                                    checked = isDownloaded,
                                    onCheckedChange = { viewModel.toggleCity(city) },
                                    enabled = downloadProgress == null
                                )
                            }
                            Divider()
                        }
                    }

                    if (isWizard) {
                        Button(
                            onClick = onWizardComplete,
                            enabled = downloaded.isNotEmpty() && downloadProgress == null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                        ) {
                            Text("Weiter")
                        }
                    }
                }
            }

            if (downloadProgress != null) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f),
                    modifier = Modifier.fillMaxSize()
                ) {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(text = downloadProgress ?: "")
                    }
                }
            }
        }
    }
}
