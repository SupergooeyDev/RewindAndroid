package dev.supergooey.rewind

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageEvents.Event.ACTIVITY_PAUSED
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
import androidx.activity.viewModels
import androidx.annotation.ColorInt
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.palette.graphics.Palette
import dev.supergooey.rewind.ui.theme.RewindTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

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
    val appContext = LocalContext.current.applicationContext
    val model = viewModels<AppViewModel> {
      AppViewModelFactory(
        appContext.getSystemService(AppOpsManager::class.java),
        appContext.packageManager,
        appContext.getSystemService(UsageStatsManager::class.java),
        packageName
      )
    }
    val state by model.value.state().collectAsState()

    val usageIntent = remember { Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS) }
    val permissionLauncher = rememberLauncherForActivityResult(
      contract = ActivityResultContracts.StartActivityForResult()
    ) {
      model.value.updatePermission(true)
    }

    Box(
      modifier = Modifier
        .fillMaxSize()
        .background(color = Color.Black)
    ) {

      if (state.usagePermissionGranted) {
        val scrollState = rememberScrollState()
        Row(
          modifier = Modifier
            .fillMaxSize()
            .horizontalScroll(scrollState),
          verticalAlignment = Alignment.CenterVertically
        ) {
          state.timeline.forEach { timelineEvent ->
//            Row(
//              modifier = Modifier
//                .fillMaxWidth()
//                .wrapContentHeight()
//                .padding(horizontal = 16.dp),
//              horizontalArrangement = Arrangement.SpaceBetween,
//              verticalAlignment = Alignment.CenterVertically
//            ) {
//              Image(
//                modifier = Modifier.size(24.dp),
//                bitmap = timelineEvent.event.icon,
//                contentDescription = timelineEvent.event.label
//              )
//              Text(timelineEvent.event.label, color = Color.White, fontSize = 16.sp)
//              Text(
//                dateFormatter.format(timelineEvent.startTimestamp),
//                color = Color.White,
//                fontSize = 16.sp
//              )
//            }
            Box(
              modifier = Modifier.wrapContentSize(),
              contentAlignment = Alignment.Center
            ) {
              Box(
                modifier = Modifier
                  .width(100.dp * (1..3).random())
                  .height(12.dp)
                  .background(
                    color = Color(timelineEvent.event.backgroundColor),
                    shape = CircleShape
                  )
              )
              Image(
                modifier = Modifier.size(24.dp),
                bitmap = timelineEvent.event.icon,
                contentDescription = timelineEvent.event.label
              )
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

class AppViewModel(
  private val appOpsManager: AppOpsManager,
  private val packageManager: PackageManager,
  private val usageStatsManager: UsageStatsManager,
  private val packageName: String
) : ViewModel() {

  private val internalState = MutableStateFlow(AppState())

  init {
    val permissionState = checkUsageStatsPermission()
    internalState.value = AppState(
      usagePermissionGranted = permissionState
    )
    if (permissionState) {
      viewModelScope.launch(Dispatchers.IO) {
        val installedApps = getNonSystemAppsList()
        val timeline = loadEvents(installedApps)
        internalState.value = internalState.value.copy(timeline = timeline)
      }
    }
  }

  private fun checkUsageStatsPermission(): Boolean {
    val mode = appOpsManager.unsafeCheckOpNoThrow(
      "android:get_usage_stats",
      Process.myUid(),
      packageName
    )
    return mode == AppOpsManager.MODE_ALLOWED
  }

  private suspend fun getNonSystemAppsList(): Map<String, AppEvent> = suspendCoroutine { cont ->
    val appInfos = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
    val map = mutableMapOf<String, AppEvent>()
    for (appInfo in appInfos) {
      if (appInfo.flags != ApplicationInfo.FLAG_SYSTEM) {
        val packageName = appInfo.packageName
        val label = appInfo.loadLabel(packageManager).toString()
        if (label.contains("launcher", ignoreCase = true)) {
          continue
        }
        val bitmap = appInfo.loadIcon(packageManager).toBitmap()
        val colors = Palette.from(bitmap).generate()
        val dominantColor = colors.getDominantColor(Color.Gray.toArgb())
        map[appInfo.packageName] =
          AppEvent(packageName, label, bitmap.asImageBitmap(), dominantColor)
      }
    }
    cont.resume(map)
  }

  fun updatePermission(granted: Boolean) {
    internalState.value = internalState.value.copy(
      usagePermissionGranted = granted
    )
  }

  private suspend fun loadEvents(installedApps: Map<String, AppEvent>): List<TimelineEvent> =
    suspendCoroutine { cont ->
      val startOfDay = LocalDateTime.of(LocalDate.now(), LocalTime.MIDNIGHT)
      val startMillis = startOfDay.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
      val endOfDay = LocalDateTime.of(LocalDate.now(), LocalTime.MAX)
      val endMillis = endOfDay.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

      val usageMap = usageStatsManager.queryAndAggregateUsageStats(startMillis, endMillis)
        .filterKeys { installedApps.containsKey(it) }
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
                  event = installedApps[event.packageName]!!,
                  startTimestamp = Date(event.timeStamp)
                )
              )
            }

            timeline.last().event.packageName != event.packageName -> {
              timeline.add(
                TimelineEvent(
                  event = installedApps[event.packageName]!!,
                  startTimestamp = Date(event.timeStamp)
                )
              )
            }
          }
        }
      }
      cont.resume(timeline)
    }

  fun state(): StateFlow<AppState> {
    return internalState.asStateFlow()
  }
}

@Suppress("UNCHECKED_CAST")
class AppViewModelFactory(
  private val appOpsManager: AppOpsManager,
  private val packageManager: PackageManager,
  private val usageStatsManager: UsageStatsManager,
  private val packageName: String
) : ViewModelProvider.NewInstanceFactory() {
  override fun <T : ViewModel> create(modelClass: Class<T>): T {
    return AppViewModel(
      appOpsManager,
      packageManager,
      usageStatsManager,
      packageName
    ) as T
  }
}

data class AppState(
  val usagePermissionGranted: Boolean = false,
  val timeline: List<TimelineEvent> = emptyList()
)

data class AppEvent(
  val packageName: String,
  val label: String,
  val icon: ImageBitmap,
  @ColorInt val backgroundColor: Int
)

data class TimelineEvent(
  val event: AppEvent,
  val startTimestamp: Date,
)

val dateFormatter = SimpleDateFormat("hh:mm a", Locale.US)
