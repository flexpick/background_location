package com.almoullim.background_location

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.location.*
import android.location.LocationListener
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.gms.common.*
import com.google.android.gms.location.*
import com.google.android.gms.location.LocationRequest
import io.flutter.FlutterInjector
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.dart.DartExecutor
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.view.FlutterCallbackInformation

class LocationUpdatesService : Service(), MethodChannel.MethodCallHandler {

    private var forceLocationManager: Boolean = false

    override fun onBind(intent: Intent?): IBinder {
        UPDATE_INTERVAL_IN_MILLISECONDS =
            intent?.getLongExtra("interval", UPDATE_INTERVAL_IN_MILLISECONDS)
                ?: UPDATE_INTERVAL_IN_MILLISECONDS
        FASTEST_UPDATE_INTERVAL_IN_MILLISECONDS =
            intent?.getLongExtra("fastest_interval", UPDATE_INTERVAL_IN_MILLISECONDS / 2)
                ?: (UPDATE_INTERVAL_IN_MILLISECONDS / 2)

        val priority = intent?.getIntExtra("priority", 0) ?: 0
        val distanceFilter = intent?.getDoubleExtra("distance_filter", 0.0) ?: 0.0
        forceLocationManager =
            intent?.getBooleanExtra("force_location_manager", forceLocationManager)
                ?: forceLocationManager

        createLocationRequest(priority, distanceFilter)
        return mBinder
    }

    private val mBinder = LocalBinder()
    private var mNotificationManager: NotificationManager? = null
    private var mLocationRequest: LocationRequest? = null
    private var mFusedLocationClient: FusedLocationProviderClient? = null
    private var mLocationManager: LocationManager? = null
    private var mFusedLocationCallback: LocationCallback? = null
    private var mLocationManagerCallback: LocationListener? = null
    private var mLocationCallbackHandle: Long = 0L
    private var mLocation: Location? = null
    private var backgroundEngine: FlutterEngine? = null
    private var isGoogleApiAvailable: Boolean = false
    private var isStarted: Boolean = false
    private val pref by lazy {
        getSharedPreferences("backgroundLocationPreferences", Context.MODE_PRIVATE)
    }
    private val actionReceiver by lazy {
        ActionRequestReceiver()
    }
    private var backgroundChannel: MethodChannel? = null

    companion object {
        var NOTIFICATION_CHANNEL_ID = "channel_01"
        var NOTIFICATION_TITLE = "Background service is running"
        var NOTIFICATION_MESSAGE = "Background service is running"
        var NOTIFICATION_ICON = "@mipmap/ic_launcher"
        var NOTIFICATION_COLOR: Int? = null

        val ACTION_START_FOREGROUND_SERVICE = "ACTION_START_FOREGROUND_SERVICE"
        val ACTION_STOP_FOREGROUND_SERVICE = "ACTION_STOP_FOREGROUND_SERVICE"
        val ACTION_UPDATE_NOTIFICATION = "ACTION_UPDATE_NOTIFICATION"
        val ACTION_NOTIFICATION_ACTIONED = "ACTION_NOTIFICATION_ACTIONED"
        val ACTION_ON_BOOT = "ACTION_ON_BOOT"

        private const val PACKAGE_NAME =
            "com.google.android.gms.location.sample.locationupdatesforegroundservice"
        private val TAG = LocationUpdatesService::class.java.simpleName
        internal val ACTION_SERVICE_REQUEST = "service_requests_action"
        internal const val ACTION_BROADCAST = "$PACKAGE_NAME.broadcast"
        internal const val ACTION_BROADCAST_TYPE = "$PACKAGE_NAME.broadcast_type"
        internal const val ACTION_BROADCAST_LOCATION = "ACTION_BROADCAST_LOCATION"
        internal const val EXTRA_LOCATION = "$PACKAGE_NAME.location"
        internal const val EXTRA_ACTION_CALLBACK = "$PACKAGE_NAME.action_callback"
        private const val EXTRA_STARTED_FROM_NOTIFICATION =
            "$PACKAGE_NAME.started_from_notification"

        var UPDATE_INTERVAL_IN_MILLISECONDS: Long = 1000
        var FASTEST_UPDATE_INTERVAL_IN_MILLISECONDS = UPDATE_INTERVAL_IN_MILLISECONDS / 2
        private const val NOTIFICATION_ID = 12345678

        private const val STOP_SERVICE = "stop_service"
    }

    private fun getDrawableResourceId(context: Context, bitmapReference: String): Int {
        val reference: Array<String> = bitmapReference.split("/").toTypedArray()
        try {
            var resId: Int
            val type = "drawable"
            val label = reference[1]

            // Resources protected from obfuscation
            // https://developer.android.com/studio/build/shrink-code#strict-reference-checks
            val name: String = String.format("res_%1s", label)
            resId = context.resources.getIdentifier(name, type, context.packageName)
            Log.w(TAG, "Getting resource: $name - $resId")
            if (resId == 0) {
                resId = context.resources.getIdentifier(label, type, context.packageName)
            }
            return resId
        } catch (e: Exception) {
            Log.w(TAG, "Error getting resource: $bitmapReference", e)
            e.printStackTrace()
        }
        return 0
    }

    private val notification: NotificationCompat.Builder
        get() {

            val intent = Intent(this, getMainActivityClass(this))
            intent.putExtra(EXTRA_STARTED_FROM_NOTIFICATION, true)
            intent.action = "Localisation"
            //intent.setClass(this, getMainActivityClass(this))
            val pendingIntent = PendingIntent.getActivity(
                this,
                1,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )

            val builder = NotificationCompat.Builder(this, "BackgroundLocation")
                .setContentTitle(NOTIFICATION_TITLE)
                .setContentText(NOTIFICATION_MESSAGE)
                .setOngoing(true)
                .setSound(null)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOnlyAlertOnce(true)
                .setAutoCancel(false)
                .setWhen(System.currentTimeMillis())
                .setContentIntent(pendingIntent)

            try {
                var resourceId: Int = 0
                if (NOTIFICATION_ICON.startsWith("@mipmap")) resourceId =
                    resources.getIdentifier(NOTIFICATION_ICON, "mipmap", packageName)
                if (NOTIFICATION_ICON.startsWith("@drawable")) {
                    resourceId = getDrawableResourceId(this, NOTIFICATION_ICON)
                }

                if (resourceId != 0) {
                    ContextCompat.getDrawable(this, resourceId)
                    builder.setSmallIcon(resourceId)
                } else {
                    Log.w(
                        TAG,
                        "Provided resource resolved to $resourceId ${getPackageName()} - $NOTIFICATION_ICON"
                    )
                    builder.setSmallIcon(
                        resources.getIdentifier(
                            "@mipmap/ic_launcher",
                            "mipmap",
                            packageName
                        )
                    )
                }

                NOTIFICATION_COLOR?.let {
                    if (it != 0) {
                        builder.setColor(it)
                    }
                }
            } catch (tr: Throwable) {
                Log.w(TAG, "Unable to set small notification icon", tr)
                builder.setSmallIcon(
                    resources.getIdentifier(
                        "@mipmap/ic_launcher",
                        "mipmap",
                        packageName
                    )
                )
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                builder.setChannelId(NOTIFICATION_CHANNEL_ID)
            }

            return builder
        }

    private inner class ActionRequestReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action: String? = intent.getStringExtra(ACTION_SERVICE_REQUEST)
            when (action) {
                ACTION_STOP_FOREGROUND_SERVICE -> triggerForegroundServiceStop()
                ACTION_UPDATE_NOTIFICATION -> updateNotification()
                ACTION_NOTIFICATION_ACTIONED -> onNotificationActionClick(intent)
                else -> {
                }
            }
        }
    }

    private var mServiceHandler: Handler? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        Log.i("LocationService", "onStartCommand ${intent?.action}")

        val action: String? = intent?.action
        when (action) {
            ACTION_START_FOREGROUND_SERVICE -> triggerForegroundServiceStart(intent)
            ACTION_STOP_FOREGROUND_SERVICE -> triggerForegroundServiceStop()
            ACTION_UPDATE_NOTIFICATION -> updateNotification()
            ACTION_NOTIFICATION_ACTIONED -> onNotificationActionClick(intent)
            ACTION_ON_BOOT -> {
                Log.i("LocationService", "Starting from boot: ${pref.getLong("callbackHandle", 0L)}")
                intent.putExtra(
                    "interval",
                    pref.getLong("interval", UPDATE_INTERVAL_IN_MILLISECONDS)
                )
                intent.putExtra(
                    "fastest_interval",
                    pref.getLong("fastestInterval", FASTEST_UPDATE_INTERVAL_IN_MILLISECONDS)
                )
                intent.putExtra("priority", pref.getInt("priority", 0))
                intent.putExtra(
                    "distance_filter",
                    pref.getFloat("distanceFilter", 300.toFloat()).toDouble()
                )
                intent.putExtra("locationCallback", pref.getLong("locationCallback", 0L))
                intent.putExtra("callbackHandle", pref.getLong("callbackHandle", 0L))

                NOTIFICATION_CHANNEL_ID =
                    pref.getString("NOTIFICATION_CHANNEL_ID", NOTIFICATION_CHANNEL_ID)
                        ?: NOTIFICATION_CHANNEL_ID
                NOTIFICATION_TITLE =
                    pref.getString("NOTIFICATION_TITLE", NOTIFICATION_TITLE) ?: NOTIFICATION_TITLE
                NOTIFICATION_MESSAGE = pref.getString("NOTIFICATION_MESSAGE", NOTIFICATION_MESSAGE)
                    ?: NOTIFICATION_MESSAGE
                NOTIFICATION_ICON =
                    pref.getString("NOTIFICATION_ICON", NOTIFICATION_ICON) ?: NOTIFICATION_ICON
                NOTIFICATION_COLOR = pref.getInt("NOTIFICATION_COLOR", NOTIFICATION_COLOR ?: 0)
                triggerForegroundServiceStart(intent)
            }

            else -> {
            }
        }
        return START_STICKY
    }

    private fun triggerForegroundServiceStart(intent: Intent) {
        FlutterInjector.instance().flutterLoader().ensureInitializationComplete(this, null)
        UPDATE_INTERVAL_IN_MILLISECONDS =
            intent.getLongExtra("interval", UPDATE_INTERVAL_IN_MILLISECONDS)
        FASTEST_UPDATE_INTERVAL_IN_MILLISECONDS =
            intent.getLongExtra("fastest_interval", UPDATE_INTERVAL_IN_MILLISECONDS / 2)

        val startOnBoot = intent.getBooleanExtra("startOnBoot", false)
        val priority = intent.getIntExtra("priority", 0)
        val distanceFilter = intent.getDoubleExtra("distance_filter", 0.0)
        createLocationRequest(priority, distanceFilter)
        setLocationCallback(intent.getLongExtra("locationCallback", 0L))

        val callbackHandle = intent.getLongExtra("callbackHandle", 0L)
        if (callbackHandle != 0L && backgroundEngine == null) {
            Log.i("LocationService", "creating background engine $callbackHandle")
            val callbackInfo = FlutterCallbackInformation.lookupCallbackInformation(callbackHandle)

            // We need flutter engine to handle callback, so if it is not available we have to create a
            // Flutter engine without any view
            backgroundEngine = FlutterEngine(this)

            val args = DartExecutor.DartCallback(
                this.assets,
                FlutterInjector.instance().flutterLoader().findAppBundlePath(),
                callbackInfo
            )
            backgroundEngine?.dartExecutor?.executeDartCallback(args)
        } else  {
            Log.i("LocationService", "Skipping background engine $callbackHandle - $backgroundEngine")
        }
        backgroundEngine?.dartExecutor?.let {
            backgroundChannel = MethodChannel(
                it.binaryMessenger,
                "com.almoullim.background_location/background"
            )
        }
        backgroundChannel?.setMethodCallHandler(this)

        val edit = pref.edit()
        edit.putBoolean("locationActive", true)
        edit.putBoolean("startOnBoot", startOnBoot)
        edit.putLong("interval", UPDATE_INTERVAL_IN_MILLISECONDS)
        edit.putLong("fastestInterval", FASTEST_UPDATE_INTERVAL_IN_MILLISECONDS)
        edit.putInt("priority", priority)
        edit.putFloat("distanceFilter", distanceFilter.toFloat())
        edit.putLong("locationCallback", mLocationCallbackHandle)
        edit.putLong("callbackHandle", callbackHandle)
        edit.putString("NOTIFICATION_CHANNEL_ID", NOTIFICATION_CHANNEL_ID)
        edit.putString("NOTIFICATION_TITLE", NOTIFICATION_TITLE)
        edit.putString("NOTIFICATION_MESSAGE", NOTIFICATION_MESSAGE)
        edit.putString("NOTIFICATION_ICON", NOTIFICATION_ICON)
        edit.putInt("NOTIFICATION_COLOR", NOTIFICATION_COLOR ?: 0)
        edit.apply()

        val googleAPIAvailability =
            GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(applicationContext)

        isGoogleApiAvailable = googleAPIAvailability == ConnectionResult.SUCCESS

        LocalBroadcastManager.getInstance(this)
            .registerReceiver(actionReceiver, IntentFilter("${packageName}.service_requests"))

        if (isGoogleApiAvailable && !this.forceLocationManager) {
            mFusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

            mFusedLocationCallback = object : LocationCallback() {
                override fun onLocationResult(locationResult: LocationResult) {
                    // Smart cast to 'Location' is impossible, because 'locationResult.lastLocation'
                    // is a property that has open or custom getter
                    val newLastLocation = locationResult.lastLocation
                    if (newLastLocation is Location) {
                        super.onLocationResult(locationResult)
                        onNewLocation(newLastLocation, locationResult.getLocations())
                    }
                }
            }
        } else {
            mLocationManager = getSystemService(LOCATION_SERVICE) as LocationManager?

            mLocationManagerCallback = LocationListener { location ->
                onNewLocation(location)
            }
        }

        getLastLocation()

        val handlerThread = HandlerThread(TAG)
        handlerThread.start()
        mServiceHandler = Handler(handlerThread.looper)

        mNotificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                NOTIFICATION_CHANNEL_ID,
                NotificationManager.IMPORTANCE_DEFAULT
            )
            mChannel.setSound(null, null)
            mNotificationManager?.createNotificationChannel(mChannel)
        }

        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification.build(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            } else {
                0
            })

        updateNotification() // to start the foreground service
    }

    fun requestLocationUpdates() {
        Utils.setRequestingLocationUpdates(this, true)
        try {
            if (isGoogleApiAvailable && !this.forceLocationManager) {
                mLocationRequest?.let { reqest->
                    mFusedLocationCallback?.let { callback ->
                        mFusedLocationClient?.requestLocationUpdates(
                            reqest, callback, Looper.myLooper()
                        )
                    }
                }
            } else {
                mLocationManagerCallback?.let {
                    mLocationManager?.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        0L,
                        0f,
                        it
                    )
                }
            }
        } catch (unlikely: SecurityException) {
            Utils.setRequestingLocationUpdates(this, false)
        }
    }

    fun updateNotification() {
        if (!isStarted) {
            isStarted = true
            if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                startForeground(NOTIFICATION_ID, notification.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(NOTIFICATION_ID, notification.build())
            }

        } else {
            val notificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(NOTIFICATION_ID, notification.build())
        }
    }

    fun triggerForegroundServiceStop() {
        Log.i("LocationService", "triggerForegroundServiceStop")
        val edit = pref.edit()
        edit.putBoolean("locationActive", false)
        edit.remove("interval")
        edit.remove("startOnBoot")
        edit.remove("fastestInterval")
        edit.remove("priority")
        edit.remove("distanceFilter")
        edit.remove("locationCallback")
        edit.remove("callbackHandle")
        edit.remove("NOTIFICATION_CHANNEL_ID")
        edit.remove("NOTIFICATION_TITLE")
        edit.remove("NOTIFICATION_MESSAGE")
        edit.remove("NOTIFICATION_ICON")
        edit.remove("NOTIFICATION_COLOR")
        edit.apply()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }
        stopSelf()
    }

    private fun getLastLocation() {
        try {
            if (isGoogleApiAvailable && !this.forceLocationManager) {
                mFusedLocationClient?.lastLocation?.addOnCompleteListener { task ->
                        if (task.isSuccessful && task.result != null) {
                            onNewLocation(task.result)
                        }
                    }
            } else {
                mLocation = mLocationManager?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            }
        } catch (unlikely: SecurityException) {
            Log.w(TAG, unlikely.toString())
        }
    }

    private fun onNewLocation(location: Location, locations: List<Location> = ArrayList()) {
        mLocation = location
        val intent = Intent(ACTION_BROADCAST)
        intent.putExtra(ACTION_BROADCAST_TYPE, ACTION_BROADCAST_LOCATION)
        intent.putExtra(EXTRA_LOCATION, location)
        LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(intent)

        val locationMap = HashMap<String, Any>()
        locationMap["latitude"] = location.latitude
        locationMap["longitude"] = location.longitude
        locationMap["altitude"] = location.altitude
        locationMap["accuracy"] = location.accuracy.toDouble()
        locationMap["bearing"] = location.bearing.toDouble()
        locationMap["speed"] = location.speed.toDouble()
        locationMap["time"] = location.time.toDouble()
        locationMap["is_mock"] = location.isFromMockProvider

        val locationsMap = ArrayList<HashMap<String, Any>>()
        for (loc in locations) {
            val locMap = HashMap<String, Any>()
            locMap["latitude"] = loc.latitude
            locMap["longitude"] = loc.longitude
            locMap["altitude"] = loc.altitude
            locMap["accuracy"] = loc.accuracy.toDouble()
            locMap["bearing"] = loc.bearing.toDouble()
            locMap["speed"] = loc.speed.toDouble()
            locMap["time"] = loc.time.toDouble()
            locMap["is_mock"] = loc.isFromMockProvider
            locationsMap.add(locMap)
        }

        val result: HashMap<Any, Any> = hashMapOf(
            "ARG_LOCATIONS" to locationsMap,
            "ARG_LOCATION" to locationMap,
            "ARG_CALLBACK" to mLocationCallbackHandle
        )

        Looper.getMainLooper()?.let {
            Handler(it).post {
                    backgroundChannel?.invokeMethod("BCM_LOCATION", result)
                }
        }
    }

    private fun onNotificationActionClick(intent: Intent) {
        getLastLocation();

        val callback = intent.getLongExtra("ARG_CALLBACK", 0L) ?: 0L

        val locationMap = HashMap<String, Any>()
        val location = mLocation;
        if (location != null) {
            locationMap["latitude"] = location.latitude
            locationMap["longitude"] = location.longitude
            locationMap["altitude"] = location.altitude
            locationMap["accuracy"] = location.accuracy.toDouble()
            locationMap["bearing"] = location.bearing.toDouble()
            locationMap["speed"] = location.speed.toDouble()
            locationMap["time"] = location.time.toDouble()
            locationMap["is_mock"] = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) location.isMock else location.isFromMockProvider
        }

        val result: HashMap<Any, Any> = hashMapOf(
            "ARG_LOCATION" to locationMap, "ARG_CALLBACK" to callback
        )

        val notificationIntent = Intent(ACTION_BROADCAST)
        notificationIntent.putExtra(ACTION_BROADCAST_TYPE, ACTION_NOTIFICATION_ACTIONED)
        notificationIntent.putExtra(EXTRA_LOCATION, location)
        notificationIntent.putExtra(EXTRA_ACTION_CALLBACK, callback)
        LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(notificationIntent)

        Looper.getMainLooper()?.let {
            Handler(it).post {
                    backgroundChannel?.invokeMethod("BCM_NOTIFICATION_ACTION", result)
                }
        }
    }

    private fun setLocationCallback(callback: Long) {
        mLocationCallbackHandle = callback;
    }


    private fun createLocationRequest(priority: Int, distanceFilter: Double) {
        val requestPriority = when(priority) {
            0 -> Priority.PRIORITY_HIGH_ACCURACY
            1 -> Priority.PRIORITY_BALANCED_POWER_ACCURACY
            2 -> Priority.PRIORITY_LOW_POWER
            else -> Priority.PRIORITY_PASSIVE
        }
        mLocationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, UPDATE_INTERVAL_IN_MILLISECONDS)
            .setPriority(requestPriority)
            .setMinUpdateIntervalMillis(FASTEST_UPDATE_INTERVAL_IN_MILLISECONDS)
            .setMinUpdateDistanceMeters(distanceFilter.toFloat())
            .setMaxUpdateDelayMillis(UPDATE_INTERVAL_IN_MILLISECONDS)
            .build()
    }


    inner class LocalBinder : Binder() {
        internal val service: LocationUpdatesService
            get() = this@LocationUpdatesService
    }


    override fun onDestroy() {
        super.onDestroy()
        isStarted = false
        LocalBroadcastManager.getInstance(applicationContext).unregisterReceiver(actionReceiver)
        try {
            if (isGoogleApiAvailable && !this.forceLocationManager) {
                mFusedLocationCallback?.let { mFusedLocationClient?.removeLocationUpdates(it) }
            } else {
                mLocationManagerCallback?.let { mLocationManager?.removeUpdates(it) }
            }

            Utils.setRequestingLocationUpdates(this, false)
            mNotificationManager?.cancel(NOTIFICATION_ID)
        } catch (unlikely: SecurityException) {
            Utils.setRequestingLocationUpdates(this, true)
        }
    }

    private fun getMainActivityClass(context: Context): Class<*>? {
        val packageName = context.packageName
        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
        val className = launchIntent?.component?.className ?: return null

        return try {
            Class.forName(className)
        } catch (e: ClassNotFoundException) {
            e.printStackTrace()
            null
        }
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "BackgroundLocationService.initialized" -> {
                result.success(0);
            }
            "set_android_notification" -> {
                val channelID: String? = call.argument("channelID")
                val notificationTitle: String? = call.argument("title")
                val notificationMessage: String? = call.argument("message")
                val notificationIcon: String? = call.argument("icon")
                val color: Int? = call.argument("color")

                if (channelID != null) NOTIFICATION_CHANNEL_ID = channelID
                if (notificationTitle != null) NOTIFICATION_TITLE = notificationTitle
                if (notificationMessage != null) NOTIFICATION_MESSAGE = notificationMessage
                if (notificationIcon != null) NOTIFICATION_ICON = notificationIcon
                if (color != null) {
                    NOTIFICATION_COLOR = color
                }

                updateNotification()

                result.success(null)
            }
            else -> {
                result.success(null)
            }
        }
    }
}
