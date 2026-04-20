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
    private val ALPHA = 0.15f // Lower means more smoothing

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

        // Immediately fetch the last known location to avoid waiting for the first GPS fix
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

            // Handle 360 -> 0 wrap around for smoothing
            if (abs(currentAzimuth - smoothedAzimuth) > 180) {
                if (currentAzimuth > smoothedAzimuth) smoothedAzimuth += 360
                else smoothedAzimuth -= 360
            }

            smoothedAzimuth = smoothedAzimuth + ALPHA * (currentAzimuth - smoothedAzimuth)
            
            var normalizedAzimuth = smoothedAzimuth % 360
            if (normalizedAzimuth < 0) normalizedAzimuth += 360

            _azimuth.value = normalizedAzimuth
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    companion object {
        /**
         * Calculates the distance between two points on the Earth's surface using the Haversine formula.
         * The Haversine formula provides great-circle distances between two points on a sphere
         * from their longitudes and latitudes.
         * 
         * Formula:
         * a = sinÂ²(Î”lat/2) + cos(lat1).cos(lat2).sinÂ²(Î”long/2)
         * c = 2.atan2(âˆša, âˆš(1âˆ’a))
         * d = R.c
         * 
         * @return distance in meters
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
         * Calculates the initial bearing from point 1 to point 2.
         * Bearing is the direction you need to move to get from point 1 to point 2 directly.
         * 
         * @return bearing in degrees [0, 360)
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
