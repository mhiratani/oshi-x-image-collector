package com.hilamalu.oshixcollector

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.hilamalu.oshixcollector.ui.navigation.OshiXImageCollectorNavGraph
import com.hilamalu.oshixcollector.ui.theme.OshiXImageCollectorTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OshiXImageCollectorTheme {
                OshiXImageCollectorNavGraph()
            }
        }
    }
}
