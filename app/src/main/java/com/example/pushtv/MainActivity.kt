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
import com.example.pushtv.ui.theme.PushTVColors
import com.example.pushtv.ui.home.HomeScreen
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.example.pushtv.utils.Events
import com.example.pushtv.logging.AppLog

class MainActivity : ComponentActivity() {

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Events.triggerRefreshApps()
            
            // 根据不同动作给出提示
            val action = intent?.action
            val packageName = intent?.data?.schemeSpecificPart
            
            when (action) {
                Intent.ACTION_PACKAGE_REMOVED -> {
                    // isReplacing 为 true 表示是覆盖安装前的卸载，不提示
                    val isReplacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
                    if (!isReplacing) {
                        AppLog.info("Package", "Application uninstalled: ${packageName.orEmpty()}")
                        android.widget.Toast.makeText(applicationContext, "应用已卸载", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
                Intent.ACTION_PACKAGE_ADDED -> {
                    val isReplacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
                    if (!isReplacing) {
                        AppLog.info("Package", "Application installed: ${packageName.orEmpty()}")
                        android.widget.Toast.makeText(applicationContext, "应用安装成功", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
                Intent.ACTION_PACKAGE_REPLACED -> {
                    AppLog.info("Package", "Application updated: ${packageName.orEmpty()}")
                }
            }
        }
    }

    @OptIn(ExperimentalTvMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.info("MainActivity", "Activity created")
        
        // 注册应用安装/卸载监听
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        registerReceiver(packageReceiver, filter)
        
        // 启动后台局域网服务器
        startService(android.content.Intent(this, com.example.pushtv.network.NetworkService::class.java))
        AppLog.info("MainActivity", "Network service start requested")
        
        setContent {
            PushTVTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(PushTVColors.Background),
                    contentAlignment = Alignment.Center
                ) {
                    HomeScreen()
                }
            }
        }
    }

    override fun onDestroy() {
        AppLog.info("MainActivity", "Activity destroyed; finishing=$isFinishing")
        super.onDestroy()
        unregisterReceiver(packageReceiver)
    }
}
