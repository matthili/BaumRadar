package at.mafue.baumradar.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import at.mafue.baumradar.app.ui.theme.BaumRadarTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        setContent {
            BaumRadarTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppContent()
                }
            }
        }
    }
}

@Composable
fun AppContent() {
    val context = androidx.compose.ui.platform.LocalContext.current.applicationContext as android.app.Application
    val cityManager = remember { at.mafue.baumradar.app.data.CityManager(context) }
    val startDestination = if (cityManager.hasAnyCity()) "main" else "wizard"
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = startDestination) {
        composable("wizard") {
            at.mafue.baumradar.app.ui.CitySelectionScreen(
                isWizard = true,
                onWizardComplete = {
                    navController.navigate("main") {
                        popUpTo("wizard") { inclusive = true }
                    }
                }
            )
        }
        composable("main") {
            at.mafue.baumradar.app.ui.MainScreen()
        }
    }
}


