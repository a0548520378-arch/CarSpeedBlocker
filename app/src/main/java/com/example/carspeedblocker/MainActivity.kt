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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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
    
    // משתנה שמחזיק את מצב ההשהיה
    var isPaused: Boolean = false
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

            // החרגת מקלדות ומערכת כדי לא להפריע להקלדה
            val ignoredPackages = listOf(
                "com.android.systemui",                  
                "com.google.android.inputmethod.latin",  
                "com.sec.android.inputmethod",           
                "com.touchtype.swiftkey"                 
            )

            if (ignoredPackages.contains(packageName)) {
                return 
            }

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
            val channel = NotificationChannel(channelId, "מערכת נסיעה בטוחה", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notification = Notification.Builder(this, channelId)
            .setContentTitle("הגנת נסיעה בטוחה פעילה")
            .setContentText("עוקב אחר נתוני נסיעה...")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
        startForeground(1, notification)
    }

    private fun setupOverlayView() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        
        // מסך שקוף לחלוטין שממוקם למעלה
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL 
            setBackgroundColor(Color.TRANSPARENT) 
            
            val banner = TextView(this@SpeedBlockerService).apply {
                text = "המסך נעול בנסיעה"
                setTextColor(Color.WHITE)
                textSize = 14f
                setPadding(40, 10, 40, 10)
                setBackgroundColor(Color.parseColor("#99000000")) 
                gravity = Gravity.CENTER
            }
            
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
        // נוסיף תנאי: אם המערכת בהשהיה - אל תחסום
        val shouldBlock = AppState.currentSpeedKmh > AppState.SPEED_THRESHOLD_KMH && !AppState.isWazeForeground && !AppState.isPaused
        
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
    
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply { 
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(50, 50, 50, 50) 
            setBackgroundColor(Color.parseColor("#F0F0F0")) // רקע טיפה אפור נעים
        }
        
        layout.addView(TextView(this).apply { 
            text = "נסיעה בטוחה"
            textSize = 28f
            setTextColor(Color.parseColor("#1565C0")) // כחול
            setPadding(0, 0, 0, 10)
        })

        layout.addView(TextView(this).apply { 
            text = "הגדרות ראשוניות:"
            textSize = 18f
            setPadding(0, 0, 0, 30)
        })

        layout.addView(Button(this).apply { 
            text = "1. אשר גישה למיקום"
            setOnClickListener { ActivityCompat.requestPermissions(this@MainActivity, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1001) } 
        })
        
        layout.addView(Button(this).apply { 
            text = "2. אשר הצגה מעל אפליקציות"
            setOnClickListener { 
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) 
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) 
                else Toast.makeText(context, "ההרשאה כבר אושרה!", Toast.LENGTH_SHORT).show() 
            } 
        })
        
        layout.addView(Button(this).apply { 
            text = "3. הפעל שירות זיהוי ווייז (נגישות)"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } 
        })

        // שטח הפרדה
        layout.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 40)
        })

        layout.addView(Button(this).apply { 
            text = "הפעל חסימת נסיעה"
            setBackgroundColor(Color.parseColor("#4CAF50")) // ירוק
            setTextColor(Color.WHITE)
            textSize = 20f
            setPadding(20, 20, 20, 20)
            setOnClickListener { startBlockerService() } 
        })

        // שטח הפרדה
        layout.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 60)
        })

        layout.addView(TextView(this).apply { 
            text = "אפשרויות השהיה זמנית:"
            textSize = 18f
            setPadding(0, 0, 0, 20)
        })

        // שורת כפתורי השהיה
        val pauseLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        pauseLayout.addView(Button(this).apply {
            text = "השהה ל-5 דק'"
            setBackgroundColor(Color.parseColor("#FF9800")) // כתום
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(10, 0, 10, 0) }
            setOnClickListener { pauseBlocker(5) }
        })

        pauseLayout.addView(Button(this).apply {
            text = "השהה ל-15 דק'"
            setBackgroundColor(Color.parseColor("#FF9800"))
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(10, 0, 10, 0) }
            setOnClickListener { pauseBlocker(15) }
        })

        layout.addView(pauseLayout)

        // כפתור ביטול השהיה (יוסתר בהתחלה)
        val cancelPauseBtn = Button(this).apply {
            text = "בטל השהיה עכשיו"
            setBackgroundColor(Color.parseColor("#F44336")) // אדום
            setTextColor(Color.WHITE)
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(10, 20, 10, 0) }
            setOnClickListener { 
                AppState.isPaused = false
                visibility = View.GONE
                Toast.makeText(this@MainActivity, "החסימה חזרה לפעולה", Toast.LENGTH_SHORT).show()
            }
        }
        
        // שומרים הפניה לכפתור כדי שנוכל להראות אותו מהפונקציה
        layout.tag = cancelPauseBtn 
        layout.addView(cancelPauseBtn)

        setContentView(layout)
    }

    private fun startBlockerService() {
        val intent = Intent(this, SpeedBlockerService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        Toast.makeText(this, "השירות הופעל בהצלחה!", Toast.LENGTH_SHORT).show()
    }

    private fun pauseBlocker(minutes: Int) {
        AppState.isPaused = true
        Toast.makeText(this, "החסימה הושהתה ל-$minutes דקות", Toast.LENGTH_LONG).show()
        
        // מציג את כפתור הביטול
        val layout = findViewById<LinearLayout>(android.R.id.content).getChildAt(0) as LinearLayout
        val cancelBtn = layout.tag as Button
        cancelBtn.visibility = View.VISIBLE

        // מבטל את ההשהיה אחרי הזמן שנקבע
        handler.postDelayed({
            AppState.isPaused = false
            cancelBtn.visibility = View.GONE
            Toast.makeText(this, "זמן ההשהיה נגמר, החסימה חזרה", Toast.LENGTH_LONG).show()
        }, minutes * 60 * 1000L)
    }
}
