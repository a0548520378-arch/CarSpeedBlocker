package com.example.carspeedblocker

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

object AppState {
    var isWazeForeground: Boolean = false
    var currentSpeedKmh: Float = 0f
    const val SPEED_THRESHOLD_KMH = 15.0f
}

class WazeDetectorService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 100
        }
        this.serviceInfo = info
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val packageName = event.packageName?.toString() ?: return

            // רשימת אפליקציות "שקופות" - אם הן קופצות, אנחנו מתעלמים ולא משנים את מצב הנעילה
            // זה כולל מקלדות נפוצות ואת ממשק המערכת (כמו שינוי ווליום)
            val ignoredPackages = listOf(
                "com.android.systemui",                  // תפריטי מערכת ווליום
                "com.google.android.inputmethod.latin",  // Gboard (מקלדת גוגל)
                "com.sec.android.inputmethod",           // מקלדת סמסונג
                "com.touchtype.swiftkey"                 // מקלדת SwiftKey
            )

            if (ignoredPackages.contains(packageName)) {
                return // אל תעשה כלום, תשאיר את המצב כמו שהוא היה
            }

            // אם האפליקציה היא וויז, נאפשר מגע. כל אפליקציה אחרת (יוטיוב וכו') - נחסום.
            AppState.isWazeForeground = (packageName == "com.waze")
        }
    }

    override fun onInterrupt() {}
}

class SpeedBlockerService : Service(), LocationListener {
    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private var isOverlayAdded = false
    private lateinit var locationManager: LocationManager

    override fun onCreate() {
        super.onCreate()
        startForegroundServiceWithNotification()
        setupOverlayView()
        startLocationTracking()
    }

    private fun startForegroundServiceWithNotification() {
        val channelId = "SpeedBlockerChannel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Speed Blocker Active", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notification = Notification.Builder(this, channelId)
            .setContentTitle("Speed Blocker Active")
            .setContentText("Monitoring speed and Waze...")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .build()
        startForeground(1, notification)
    }

    private fun setupOverlayView() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        
        // יצירת מסך שקוף לחלוטין שעדיין חוסם מגע
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL // ממקם את הטקסט למעלה באמצע
            setBackgroundColor(Color.TRANSPARENT) // *** שינוי קריטי: רקע שקוף לגמרי! ***
            
            // תווית קטנה שתופיע למעלה
            val banner = TextView(this@SpeedBlockerService).apply {
                text = "המסך חסום בנסיעה"
                setTextColor(Color.WHITE)
                textSize = 14f
                setPadding(40, 10, 40, 10)
                // רקע שחור חצי שקוף רק מאחורי הטקסט הקטן למעלה, כדי שיהיה קריא
                setBackgroundColor(Color.parseColor("#99000000")) 
                gravity = Gravity.CENTER
            }
            
            // הגדרות עיצוב לתווית (קצת רווח מלמעלה)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 30, 0, 0)
            }
            
            addView(banner, params)
        }
        overlayView = layout
    }

    private fun startLocationTracking() {
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 1f, this)
        }
    }

    override fun onLocationChanged(location: Location) {
        AppState.currentSpeedKmh = if (location.hasSpeed()) location.speed * 3.6f else 0f
        checkAndToggleOverlay()
    }

    private fun checkAndToggleOverlay() {
        val shouldBlock = AppState.currentSpeedKmh > AppState.SPEED_THRESHOLD_KMH && !AppState.isWazeForeground
        if (shouldBlock && !isOverlayAdded) addOverlay()
        else if (!shouldBlock && isOverlayAdded) removeOverlay()
    }

    private fun addOverlay() {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP
        try { windowManager.addView(overlayView, params); isOverlayAdded = true } catch (e: Exception) {}
    }

    private fun removeOverlay() {
        try { windowManager.removeView(overlayView); isOverlayAdded = false } catch (e: Exception) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        super.onDestroy()
        locationManager.removeUpdates(this)
        if (isOverlayAdded) removeOverlay()
    }
}

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(50, 50, 50, 50) }
        layout.addView(TextView(this).apply { text = "Car Speed Blocker"; textSize = 24f; setPadding(0, 0, 0, 50) })
        layout.addView(Button(this).apply { text = "1. Location Permission"; setOnClickListener { ActivityCompat.requestPermissions(this@MainActivity, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1001) } })
        layout.addView(Button(this).apply { text = "2. Overlay Permission"; setOnClickListener { 
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) 
            else Toast.makeText(context, "Granted!", Toast.LENGTH_SHORT).show() 
        } })
        layout.addView(Button(this).apply { text = "3. Waze Detector (Accessibility)"; setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } })
        layout.addView(Button(this).apply { text = "START BLOCKER"; setBackgroundColor(Color.GREEN); setOnClickListener { startBlockerService() } })
        setContentView(layout)
    }

    private fun startBlockerService() {
        val intent = Intent(this, SpeedBlockerService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        Toast.makeText(this, "Service Started!", Toast.LENGTH_SHORT).show()
    }
}
