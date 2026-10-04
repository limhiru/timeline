package com.example.timeline.wear

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.example.timeline.*

class WatchActivity : ComponentActivity() {
    private val repository get() = (application as TimelineApplication).repository
    private var pendingAction: String? = null
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val action = pendingAction; pendingAction = null
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) action?.let(::runService)
        else repository.message("설정에서 Timeline의 정확한 위치 권한을 허용해 주세요.")
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val state by repository.state.collectAsState()
            WatchScreen(state, ::command, { repository.engine.select(it); repository.publish() }, { repository.message(null) })
        }
    }
    override fun onStart() { super.onStart(); repository.uiVisible = true }
    override fun onStop() { repository.uiVisible = false; super.onStop() }
    private fun command(action: String) {
        if (action == TrackingService.STOP || action == TrackingService.PAUSE) { runService(action); return }
        val missing = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            missing += Manifest.permission.ACCESS_FINE_LOCATION; missing += Manifest.permission.ACCESS_COARSE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) missing += Manifest.permission.POST_NOTIFICATIONS
        if (missing.isNotEmpty()) { pendingAction = action; permissions.launch(missing.toTypedArray()) }
        else runService(action)
    }
    private fun runService(action: String) {
        try {
            val intent = Intent(this, TrackingService::class.java).setAction(action)
            if (action == TrackingService.START || action == TrackingService.RETURN) startForegroundService(intent) else startService(intent)
        } catch (error: Exception) { repository.message("기록 서비스를 시작하지 못했습니다: ${error.message}") }
    }
}
