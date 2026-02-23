package com.ketan.slam

import android.content.Intent
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.FlutterEngineCache
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {

    private val CHANNEL = "com.ketan.slam/ar"

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        // Cache the Flutter engine so ArActivity can access it
        FlutterEngineCache
            .getInstance()
            .put("slam_engine", flutterEngine)

        // Set up method channel for opening AR
        MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            CHANNEL
        ).setMethodCallHandler { call, result ->

            if (call.method == "openAR") {
                startActivity(Intent(this, ArActivity::class.java))
                result.success(null)
            } else {
                result.notImplemented()
            }
        }
    }
}