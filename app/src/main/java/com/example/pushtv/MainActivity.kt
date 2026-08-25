package com.example.pushtv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.tv.material3.Text
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.example.pushtv.ui.theme.PushTVTheme
import com.example.pushtv.ui.theme.Background
import com.example.pushtv.ui.home.HomeScreen

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalTvMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 启动后台局域网服务器
        startService(android.content.Intent(this, com.example.pushtv.network.NetworkService::class.java))
        
        setContent {
            PushTVTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Background),
                    contentAlignment = Alignment.Center
                ) {
                    HomeScreen()
                }
            }
        }
    }
}
