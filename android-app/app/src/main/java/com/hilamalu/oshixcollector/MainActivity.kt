package com.hilamalu.oshixcollector

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.hilamalu.oshixcollector.data.notification.NotificationScheduler
import com.hilamalu.oshixcollector.ui.navigation.OshiXImageCollectorNavGraph
import com.hilamalu.oshixcollector.ui.theme.OshiXImageCollectorTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 予約は WorkManager 側に永続化されているが、アプリのアンインストール以外でも
        // 予約が失われうる（端末のバックアップ復元など）ため、起動のたびに存在を確認する。
        // 既存の予約があればそのまま残す
        lifecycleScope.launch { NotificationScheduler.ensureScheduled(applicationContext) }
        enableEdgeToEdge()
        setContent {
            OshiXImageCollectorTheme {
                OshiXImageCollectorNavGraph()
            }
        }
    }
}
