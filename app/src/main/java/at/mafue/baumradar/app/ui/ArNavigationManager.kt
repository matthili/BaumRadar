package at.mafue.baumradar.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Looper
import com.google.android.gms.location.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.*

/**
 * Verwaltet GPS-Standort und Kompass-Ausrichtung für die Karten- und AR-Ansicht.
 *
 * Kombiniert zwei Sensorquellen:
 * - **FusedLocationProvider** (Google Play Services): Liefert GPS-Standorte
 *   mit 2-Sekunden-Intervall bei hoher Genauigkeit
 * - **TYPE_ROTATION_VECTOR**: Sensor-Fusion aus Gyroskop, Magnetometer und
 *   Beschleunigungssensor für eine stabile Kompassrichtung
 *
 * Die Kompassdaten werden mit einem Tiefpassfilter (Exponential Moving Average)
 * geglättet, um Zittern in der AR-Darstellung zu vermeiden.
 *
 * Stellt außerdem statische Hilfsfunktionen für geographische Berechnungen
 * bereit ([calculateDistance], [calculateBearing]).
 */
class ArNavigationManager(private val context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)

    // Flow for the current location
    private val _currentLocation = MutableStateFlow<Location?>(null)
    val currentLocation: StateFlow<Location?> = _currentLocation

    // Flow for the current compass azimuth (0 to 360 degrees)
    private val _azimuth = MutableStateFlow(0f)
    val azimuth: StateFlow<Float> = _azimuth

    // Smoothing the compass via a simple low-pass filter
    private var smoothedAzimuth = 0f
    /**
     * Glättungsfaktor für den Tiefpassfilter des Kompasses.
     *
     * Formel: `smoothed = smoothed + ALPHA * (current - smoothed)`
     * Kleinere Werte = stärkere Glättung (trägere Reaktion).
     * 0.15 bietet einen guten Kompromiss zwischen Reaktionszeit und Stabilität.
     */
    private val ALPHA = 0.15f

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            _currentLocation.value = result.lastLocation
        }
    }

    @SuppressLint("MissingPermission")
    fun startTracking() {
        // Start compass
        rotationSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }

        // Start location
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000)
            .setMinUpdateIntervalMillis(1000)
            .build()
        
        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            locationCallback,
            Looper.getMainLooper()
        )

        // Sofort den zuletzt bekannten Standort übernehmen, um die Wartezeit
        // bis zum ersten GPS-Fix zu überbrücken (LastLocation ist gecacht und
        // verursacht keinen zusätzlichen Energieverbrauch)
        fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
            if (loc != null && _currentLocation.value == null) {
                _currentLocation.value = loc
            }
        }
    }

    fun stopTracking() {
        sensorManager.unregisterListener(this)
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_ROTATION_VECTOR) {
            val rotationMatrix = FloatArray(9)
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
            
            val orientation = FloatArray(3)
            SensorManager.getOrientation(rotationMatrix, orientation)
            
            // Convert radians to degrees [-180, 180] -> [0, 360]
            var currentAzimuth = Math.toDegrees(orientation[0].toDouble()).toFloat()
            if (currentAzimuth < 0) currentAzimuth += 360f

            // Sonderbehandlung für den 360°/0°-Übergang:
            // Ohne diese Korrektur würde der Tiefpassfilter bei einem Wechsel
            // von z. B. 359° → 1° den Wert auf ~180° glätten statt auf ~0°.
            if (abs(currentAzimuth - smoothedAzimuth) > 180) {
                if (currentAzimuth > smoothedAzimuth) smoothedAzimuth += 360
                else smoothedAzimuth -= 360
            }

            // Exponential Moving Average (EMA) als Tiefpassfilter
            smoothedAzimuth = smoothedAzimuth + ALPHA * (currentAzimuth - smoothedAzimuth)
            
            var normalizedAzimuth = smoothedAzimuth % 360
            if (normalizedAzimuth < 0) normalizedAzimuth += 360

            _azimuth.value = normalizedAzimuth
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    companion object {
        /**
         * Berechnet die Großkreis-Distanz zwischen zwei Koordinaten nach der Haversine-Formel.
         *
         * Die Haversine-Formel liefert exakte Ergebnisse für beliebige Entfernungen auf einer
         * Kugel. Sie ist numerisch stabiler als die Sphärische Kosinusformel bei kleinen Distanzen.
         *
         * Formel:
         * a = sin²(Δlat/2) + cos(lat1) · cos(lat2) · sin²(Δlon/2)
         * c = 2 · atan2(√a, √(1−a))
         * d = R · c
         *
         * @return Distanz in Metern
         */
        fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val R = 6371e3 // Earth's radius in meters
            val phi1 = Math.toRadians(lat1)
            val phi2 = Math.toRadians(lat2)
            val deltaPhi = Math.toRadians(lat2 - lat1)
            val deltaLambda = Math.toRadians(lon2 - lon1)

            val a = sin(deltaPhi / 2) * sin(deltaPhi / 2) +
                    cos(phi1) * cos(phi2) *
                    sin(deltaLambda / 2) * sin(deltaLambda / 2)
            val c = 2 * atan2(sqrt(a), sqrt(1 - a))

            return R * c
        }

        /**
         * Berechnet den Anfangskurs (Initial Bearing) von Punkt 1 zu Punkt 2.
         *
         * Der Bearing ist die Richtung in Grad (0° = Nord, 90° = Ost), in die
         * man sich bewegen muss, um auf dem kürzesten Weg von Punkt 1 zu Punkt 2
         * zu gelangen. Wird für die AR-Pfeilrichtung verwendet.
         *
         * @return Kurs in Grad [0, 360)
         */
        fun calculateBearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val phi1 = Math.toRadians(lat1)
            val phi2 = Math.toRadians(lat2)
            val deltaLambda = Math.toRadians(lon2 - lon1)

            val y = sin(deltaLambda) * cos(phi2)
            val x = cos(phi1) * sin(phi2) -
                    sin(phi1) * cos(phi2) * cos(deltaLambda)
            var bearing = Math.toDegrees(atan2(y, x))
            
            return (bearing + 360) % 360
        }
    }
}
