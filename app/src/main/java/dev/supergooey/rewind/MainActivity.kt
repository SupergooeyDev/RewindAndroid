package dev.supergooey.rewind

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import dev.supergooey.rewind.ui.theme.RewindTheme
import java.time.Duration
import java.util.Calendar

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    WindowCompat.setDecorFitsSystemWindows(window, false)

    setContent {
      RewindTheme {
        App()
      }
    }
  }


  @Composable
  fun App() {
    Box(
      modifier = Modifier
        .fillMaxSize()
        .background(color = Color.Black)
        .windowInsetsPadding(WindowInsets.statusBars)
    ) {
      fun checkUsageStatsPermission(): Boolean {
        val appOpsManager = getSystemService(APP_OPS_SERVICE) as AppOpsManager
        // `AppOpsManager.checkOpNoThrow` is deprecated from Android Q
        val mode =
          appOpsManager.unsafeCheckOpNoThrow(
            "android:get_usage_stats",
            Process.myUid(), packageName
          )
        return mode == AppOpsManager.MODE_ALLOWED
      }

      var granted by remember { mutableStateOf(checkUsageStatsPermission()) }
      val usageIntent = remember { Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS) }
      val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
      ) {
        granted = true
      }

      if (granted) {
        val context = LocalContext.current
        val usageStatsManager = remember { context.getSystemService(UsageStatsManager::class.java) }
        val calendar = Calendar.getInstance().apply {
          set(Calendar.HOUR, 0)
          set(Calendar.MINUTE, 0)
          set(Calendar.SECOND, 0)
          set(Calendar.MILLISECOND, 0)
        }
        val startMillis = calendar.timeInMillis
        with(calendar) {
          set(Calendar.HOUR, 23)
          set(Calendar.MINUTE, 59)
          set(Calendar.SECOND, 59)
          set(Calendar.MILLISECOND, 999)
        }
        val endMillis = calendar.timeInMillis

        val usageMap = usageStatsManager.queryAndAggregateUsageStats(
          startMillis,
          endMillis
        )

        val scrollState = rememberScrollState()
        Column(modifier = Modifier.fillMaxSize().verticalScroll(scrollState)) {
          usageMap.filterValues { it.totalTimeInForeground > 0 }.forEach { (packageName, stats) ->
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .height(20.dp),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Text(packageName, color = Color.White)
              Text("${Duration.ofMillis(stats.totalTimeInForeground).seconds} s", color = Color.White)

            }
          }
        }
      } else {
        LaunchedEffect(Unit) {
          permissionLauncher.launch(usageIntent)
        }
      }
    }
  }
}
