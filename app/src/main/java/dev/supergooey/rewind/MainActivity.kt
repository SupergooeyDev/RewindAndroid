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
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.ColorInt
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.palette.graphics.Palette
import dev.supergooey.rewind.Dates.endMillis
import dev.supergooey.rewind.Dates.startMillis
import dev.supergooey.rewind.ui.theme.Purple40
import dev.supergooey.rewind.ui.theme.PurpleGrey40
import dev.supergooey.rewind.ui.theme.PurpleGrey80
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
import kotlin.time.Duration.Companion.milliseconds

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
    var screenWidth by remember { mutableFloatStateOf(0f) }
    val usageIntent = remember { Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS) }
    val permissionLauncher = rememberLauncherForActivityResult(
      contract = ActivityResultContracts.StartActivityForResult()
    ) {
      model.value.updatePermission(true)
    }

    Box(
      modifier = Modifier
        .fillMaxSize()
        .background(color = PurpleGrey40)
    ) {

      if (state.usagePermissionGranted) {
        val listState = rememberLazyListState()
        LazyRow(
          modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { screenWidth = it.width.toFloat() },
          state = listState,
          verticalAlignment = Alignment.CenterVertically
        ) {
          items(state.timeline.sessions) { session ->
            val seconds = session.duration.inWholeSeconds
            var focused by remember { mutableStateOf(false) }
            val barWidth = maxOf(50, seconds).toInt()
            val barHeight by animateDpAsState(
              targetValue = if (focused) 24.dp else 12.dp,
              animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium
              ),
              label = "Selected Bar Height"
            )
            Box(
              modifier = Modifier
                .wrapContentSize()
                .onGloballyPositioned { coords ->
                  val center = screenWidth / 2f
                  val bounds = coords.boundsInParent()
                  val right = bounds.right
                  val left = bounds.left
                  focused = right > center && left < center
                },
              contentAlignment = Alignment.Center
            ) {
              Box(
                modifier = Modifier
                  .width(barWidth.dp)
                  .height(barHeight)
                  .background(
                    color = Color(session.start.app.backgroundColor),
                    shape = CircleShape
                  )
              )
              Image(
                modifier = Modifier.size(24.dp),
                bitmap = session.start.app.icon,
                contentDescription = session.start.app.label
              )
            }
          }
          item {
            Box(modifier = Modifier.fillParentMaxWidth(0.5f))
          }
        }
        Box(
          modifier = Modifier
            .width(2.dp)
            .height(80.dp)
            .background(color = Color.White, shape = CircleShape)
            .align(Alignment.Center)
        )
        LaunchedEffect(key1 = state.timeline.sessions) {
          if (state.timeline.sessions.isNotEmpty()) {
            listState.scrollToItem(state.timeline.sessions.lastIndex)
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

  private val state = MutableStateFlow(AppState())

  init {
    val permissionState = checkUsageStatsPermission()
    state.value = AppState(
      usagePermissionGranted = permissionState
    )
    if (permissionState) {
      viewModelScope.launch(Dispatchers.IO) {
        val apps = appsByPackage()
        val timeline = loadEvents(apps)
        state.value = state.value.copy(timeline = timeline)
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

  private suspend fun appsByPackage() = suspendCoroutine { cont ->
    val apps = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
    val map = apps
      .filter {
        it.flags != ApplicationInfo.FLAG_SYSTEM
      }.associate { app ->
        val packageName = app.packageName
        val label = app.loadLabel(packageManager).toString()
        val bitmap = app.loadIcon(packageManager).toBitmap()
        val colors = Palette.from(bitmap).generate()
        val dominantColor = colors.getDominantColor(Color.Gray.toArgb())
        packageName to InstalledApp(
          packageName = packageName,
          label = label,
          icon = bitmap.asImageBitmap(),
          backgroundColor = dominantColor
        )
      }
    cont.resume(map)
  }

  private suspend fun loadEvents(appsByPackage: Map<String, InstalledApp>): AppTimeline {
    return suspendCoroutine { cont ->

      val events = usageStatsManager.queryEvents(startMillis, endMillis)
      val event = UsageEvents.Event()
      val appSessions = mutableListOf<AppSession>()
      val appEvents = mutableListOf<AppEvent>()
      var totalDuration = 0L

      while (events.hasNextEvent()) {
        events.getNextEvent(event)

        if (!appsByPackage.containsKey(event.packageName) || event.eventType != ACTIVITY_RESUMED) continue
        /*
        * Core Logic
        * If we have no events, add the App Event
        * If we get to an event that is different from the last event, construct a AppSession using those two events
        *
        */
        val appEvent = AppEvent(
          appsByPackage.getValue(event.packageName),
          Date(event.timeStamp),
          event.eventType
        )

        when {
          appEvents.isEmpty() -> {
            Log.d("Events", "Adding initial event: ${appEvent.app.packageName}")
            appEvents.add(appEvent)
          }

          appEvents.last().app.packageName != appEvent.app.packageName -> {
            val startEvent = appEvents.removeLast()
            val endEvent = startEvent.copy(timestamp = appEvent.timestamp)
            val session = AppSession(
              start = startEvent,
              end = endEvent
            )
            appSessions.add(session)
            appEvents.add(appEvent)
            totalDuration += session.duration.inWholeSeconds
          }
        }
      }

      cont.resume(
        AppTimeline(
          appSessions,
          totalDuration
        )
      )
    }
  }

  fun updatePermission(granted: Boolean) {
    state.value = state.value.copy(
      usagePermissionGranted = granted
    )
  }


  fun state(): StateFlow<AppState> {
    return state.asStateFlow()
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
  val timeline: AppTimeline = AppTimeline()
)

data class InstalledApp(
  val packageName: String,
  val label: String,
  val icon: ImageBitmap,
  @ColorInt val backgroundColor: Int
)

data class AppEvent(
  val app: InstalledApp,
  val timestamp: Date,
  val type: Int
)

data class AppSession(
  val start: AppEvent,
  val end: AppEvent
) {
  val duration = (end.timestamp.time - start.timestamp.time).milliseconds
}

data class AppTimeline(
  val sessions: List<AppSession> = emptyList(),
  val totalDuration: Long = 0L
)


object Dates {
  private val startOfDay = LocalDateTime.of(LocalDate.now(), LocalTime.MIDNIGHT)
  val startMillis = startOfDay.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

  private val endOfDay = LocalDateTime.of(LocalDate.now(), LocalTime.MAX)
  val endMillis = endOfDay.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

  val dateFormatter = SimpleDateFormat("hh:mm a", Locale.US)
}