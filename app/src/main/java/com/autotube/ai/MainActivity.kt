package com.autotube.ai

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.autotube.ai.ui.StartupPermissions
import com.autotube.ai.ui.navigation.AppNavHost
import com.autotube.ai.ui.navigation.DeepLink
import com.autotube.ai.ui.theme.AutoTubeTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Single activity hosting the Compose UI. Handles notification deep links
 * ([com.autotube.ai.core.common.IntentKeys]) in onCreate and onNewIntent.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val pendingDeepLink = MutableStateFlow<DeepLink?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        if (savedInstanceState == null) {
            pendingDeepLink.value = DeepLink.consume(intent)
        }
        setContent {
            AutoTubeTheme {
                val deepLink by pendingDeepLink.collectAsStateWithLifecycle()
                StartupPermissions()
                AppNavHost(
                    deepLink = deepLink,
                    onDeepLinkHandled = { pendingDeepLink.value = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        DeepLink.consume(intent)?.let { pendingDeepLink.value = it }
    }
}
