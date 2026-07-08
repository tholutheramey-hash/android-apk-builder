package com.store.broadcast

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private val TAG = "StoreBroadcast"
    private val PREFS_NAME = "StoreBroadcastPrefs"
    private val KEY_FIREBASE_URL = "firebase_url"

    private lateinit var firebaseUrlInput: EditText
    private lateinit var saveButton: Button
    private lateinit var setupScreen: LinearLayout
    private lateinit var listeningScreen: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var lastCommandText: TextView

    private lateinit var executor: ExecutorService
    private lateinit var mainHandler: Handler
    private var firebaseUrl: String = ""
    private var lastSeenTs: Long = 0
    private var isListening = false
    private var pollRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setupScreen = findViewById(R.id.setupScreen)
        listeningScreen = findViewById(R.id.listeningScreen)
        firebaseUrlInput = findViewById(R.id.firebaseUrlInput)
        saveButton = findViewById(R.id.saveButton)
        statusText = findViewById(R.id.statusText)
        lastCommandText = findViewById(R.id.lastCommandText)

        executor = Executors.newSingleThreadExecutor()
        mainHandler = Handler(Looper.getMainLooper())

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val savedUrl = prefs.getString(KEY_FIREBASE_URL, "") ?: ""

        if (savedUrl.isNotEmpty()) {
            firebaseUrl = savedUrl
            showListeningScreen()
            startPolling()
        }

        saveButton.setOnClickListener {
            var url = firebaseUrlInput.text.toString().trim()
            if (url.isEmpty()) {
                Toast.makeText(this, "Enter Firebase URL", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!url.startsWith("https://")) {
                url = "https://$url"
            }
            firebaseUrl = url
            prefs.edit().putString(KEY_FIREBASE_URL, firebaseUrl).apply()
            showListeningScreen()
            startPolling()
        }

        startService(Intent(this, BroadcastService::class.java))
    }

    private fun showListeningScreen() {
        setupScreen.visibility = android.view.View.GONE
        listeningScreen.visibility = android.view.View.VISIBLE
    }

    private fun startPolling() {
        if (isListening) return
        isListening = true

        pollRunnable = object : Runnable {
            override fun run() {
                pollFirebase()
                mainHandler.postDelayed(this, 2000)
            }
        }
        mainHandler.post(pollRunnable!!)
    }

    private fun pollFirebase() {
        executor.execute {
            try {
                val urlStr = firebaseUrl.replace(Regex("/$"), "") + "/broadcast.json"
                val url = URL(urlStr)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 5000
                conn.readTimeout = 5000

                val responseCode = conn.responseCode
                if (responseCode == 200) {
                    val reader = BufferedReader(InputStreamReader(conn.inputStream))
                    val response = StringBuilder()
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        response.append(line)
                    }
                    reader.close()

                    val json = response.toString()
                    if (json == "null") return@execute

                    val data = JSONObject(json)
                    if (!data.has("ts")) return@execute

                    val ts = data.getLong("ts")
                    if (ts != lastSeenTs) {
                        lastSeenTs = ts
                        val cmd = data.optString("cmd", "")
                        val videoUrl = data.optString("url", "")

                        mainHandler.post {
                            lastCommandText.text = "Last: $cmd at " + 
                                java.util.Date(ts).toLocaleString()
                        }

                        when (cmd) {
                            "play" -> if (videoUrl.isNotEmpty()) openYouTubeApp(videoUrl)
                            "back" -> {
                                mainHandler.post {
                                    val intent = Intent(this@MainActivity, MainActivity::class.java)
                                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                                    startActivity(intent)
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Poll error: ${e.message}")
            }
        }
    }

    private fun openYouTubeApp(videoUrl: String) {
        val videoId = extractVideoId(videoUrl) ?: run {
            Log.e(TAG, "Could not extract video ID from: $videoUrl")
            return
        }

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:$videoId"))
        intent.putExtra("VIDEO_ID", videoId)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

        if (intent.resolveActivity(packageManager) != null) {
            startActivity(intent)
            Log.d(TAG, "Opened YouTube app with video: $videoId")
        } else {
            val browserIntent = Intent(Intent.ACTION_VIEW,
                Uri.parse("https://www.youtube.com/watch?v=$videoId"))
            browserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(browserIntent)
        }
    }

    private fun extractVideoId(url: String): String? {
        return try {
            val u = java.net.URL(url)
            val host = u.host.replaceFirst("^www\\.".toRegex(), "")

            when {
                host == "youtu.be" -> u.path.substring(1).takeIf { it.isNotEmpty() }
                host == "youtube.com" || host == "m.youtube.com" -> {
                    val query = u.query
                    if (query != null) {
                        val pairs = query.split("&")
                        for (pair in pairs) {
                            if (pair.startsWith("v=")) return pair.substring(2)
                        }
                    }
                    val path = u.path
                    if (path.startsWith("/shorts/")) {
                        return path.substring(8).split("/")[0]
                    }
                    null
                }
                else -> null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing URL: ${e.message}")
            null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        pollRunnable?.let { mainHandler.removeCallbacks(it) }
        executor.shutdown()
    }
}
