package dev.supergooey.rewind

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED
import android.app.usage.UsageEvents.Event.USER_INTERACTION
import android.app.usage.UsageStatsManager
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentHeight
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.core.view.WindowCompat
import dev.supergooey.rewind.ui.theme.RewindTheme
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
        val mode =
          appOpsManager.unsafeCheckOpNoThrow(
            "android:get_usage_stats",
            Process.myUid(), packageName
          )
        return mode == AppOpsManager.MODE_ALLOWED
      }

      fun getNonSystemAppsList(): Map<String, AppEvent> {
        val appInfos = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
        val appInfoMap = mutableMapOf<String, AppEvent>()
        for (appInfo in appInfos) {
          if (appInfo.flags != ApplicationInfo.FLAG_SYSTEM) {
            val packageName = appInfo.packageName
            val label = appInfo.loadLabel(packageManager).toString()
            if (label.contains("launcher", ignoreCase = true)) {
              continue
            }
            val bitmap = appInfo.loadIcon(packageManager).toBitmap().asImageBitmap()
            appInfoMap[appInfo.packageName] = AppEvent(packageName, label, bitmap)
          }
        }
        return appInfoMap
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

        val userApps = getNonSystemAppsList()

        val usageMap = usageStatsManager.queryAndAggregateUsageStats(startMillis, endMillis)
          .filterKeys { userApps.containsKey(it) }
          .filterValues { it.totalTimeInForeground > 0L }

        val events = usageStatsManager.queryEvents(startMillis, endMillis)
        val event = UsageEvents.Event()
        val timeline = mutableListOf<AppEvent>()

        while (events.hasNextEvent()) {
          events.getNextEvent(event)
          val validEvents = event.eventType == ACTIVITY_RESUMED
          if (validEvents && usageMap.containsKey(event.packageName)) {
            when {
              timeline.isEmpty() -> {
                timeline.add(userApps[event.packageName]!!)
              }

              timeline.last().packageName != event.packageName -> {
                timeline.add(userApps[event.packageName]!!)
              }
            }
          }
        }

        val scrollState = rememberScrollState()
        Column(
          modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState),
          verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          timeline.forEach { appEvent ->
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight()
                .padding(horizontal = 16.dp),
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              verticalAlignment = Alignment.CenterVertically
            ) {
              Image(
                modifier = Modifier.size(24.dp),
                bitmap = appEvent.icon,
                contentDescription = appEvent.label
              )
              Text(appEvent.label, color = Color.White, fontSize = 16.sp)
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

data class AppEvent(
  val packageName: String,
  val label: String,
  val icon: ImageBitmap,
)
