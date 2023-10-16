package dev.supergooey.rewind

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsTopHeight
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
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.Calendar
import java.util.Date
import java.util.Locale

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
        val startOfDay = LocalDateTime.of(LocalDate.now(), LocalTime.MIDNIGHT)
        val startMillis = startOfDay.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val endOfDay = LocalDateTime.of(LocalDate.now(), LocalTime.MAX)
        val endMillis = endOfDay.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

        val userApps = getNonSystemAppsList()

        val usageMap = usageStatsManager.queryAndAggregateUsageStats(startMillis, endMillis)
          .filterKeys { userApps.containsKey(it) }
          .filterValues { it.totalTimeInForeground > 0L }

        val events = usageStatsManager.queryEvents(startMillis, endMillis)
        val event = UsageEvents.Event()
        val timeline = mutableListOf<TimelineEvent>()

        while (events.hasNextEvent()) {
          events.getNextEvent(event)
          val validEvents = event.eventType == ACTIVITY_RESUMED
          if (validEvents && usageMap.containsKey(event.packageName)) {
            when {
              timeline.isEmpty() -> {
                timeline.add(
                  TimelineEvent(
                    event = userApps[event.packageName]!!,
                    timestamp = Date(event.timeStamp)
                  )
                )
              }

              timeline.last().event.packageName != event.packageName -> {
                timeline.add(
                  TimelineEvent(
                    event = userApps[event.packageName]!!,
                    timestamp = Date(event.timeStamp)
                  )
                )
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
          Spacer(modifier = Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
          timeline.forEach { timelineEvent ->
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight()
                .padding(horizontal = 16.dp),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Image(
                modifier = Modifier.size(24.dp),
                bitmap = timelineEvent.event.icon,
                contentDescription = timelineEvent.event.label
              )
              Text(timelineEvent.event.label, color = Color.White, fontSize = 16.sp)
              Text(dateFormatter.format(timelineEvent.timestamp), color = Color.White, fontSize = 16.sp)
            }
          }
          Spacer(modifier = Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
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

data class TimelineEvent(
  val event: AppEvent,
  val timestamp: Date
)

val dateFormatter = SimpleDateFormat("hh:mm a", Locale.US)