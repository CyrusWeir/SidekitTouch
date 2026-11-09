package hrln.interactive.sidekit.apps.health

import android.Manifest
import android.hardware.Sensor
import android.hardware.SensorManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import hrln.interactive.sidekit.apps.maps.OutdoorInfo
import hrln.interactive.sidekit.apps.maps.WhileVisible
import hrln.interactive.sidekit.design.action.SideAction
import hrln.interactive.sidekit.design.components.*
import hrln.interactive.sidekit.design.theme.SidekitTheme
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import java.time.LocalDate

class HealthViewModel(val repository: HealthRepository) : ViewModel() {
    fun saveGoal(text: String): Boolean {
        val value = text.toIntOrNull()?.takeIf { it in 1..100_000 } ?: return false
        repository.goal(value)
        return true
    }
}

@Composable
fun LiveHealth(page: String, onHistory: () -> Unit, onGoals: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val vm: HealthViewModel = viewModel(factory = viewModelFactory { initializer { HealthViewModel(HealthRepository.get(context)) } })
    val ledger by vm.repository.store.state.collectAsStateWithLifecycle()
    val error by vm.repository.store.error.collectAsStateWithLifecycle()
    var allowed by remember { mutableStateOf(canCount(context)) }
    var message by remember { mutableStateOf("Allow activity recognition to count steps. No always-on service.") }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed = it; if (!it) message = "Activity access denied. Saved history and goals still work." }
    val available = remember { context.getSystemService(SensorManager::class.java).getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null }
    WhileVisible(allowed) {
        allowed = canCount(context)
        if (allowed && available) {
            vm.repository.store.state.filterNotNull().first()
            scheduleRollover(context)
            stepReadings(context).collect { recordSteps(context, vm.repository, it) }
        }
    }
    val list = rememberLazyListState()
    var goal by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(ledger?.goal) { if (goal.isEmpty()) goal = ledger?.goal?.toString().orEmpty() }
    val actions = when (page) {
        "goals" -> listOf(SideAction.back("back", "Cancel", onBack), SideAction("save", "Save") { if (ledger != null && vm.saveGoal(goal)) onBack() else message = "Enter a goal from 1 to 100000" })
        "history" -> listOf(SideAction.back("back", "Back", onBack))
        else -> listOfNotNull(SideAction.back("history", "History", onHistory), SideAction.forward("goals", "Goals", onGoals), if (!allowed && available) SideAction("allow", "Allow") { launcher.launch(Manifest.permission.ACTIVITY_RECOGNITION) } else null)
    }
    SidekitScaffold(if (page == "home") "Health" else if (page == "goals") "Daily goal" else "Step history", actions = actions) { padding ->
        SidekitList(padding, state = list) {
            when (page) {
                "goals" -> {
                    item { SidekitTile(Modifier.listFade(list, 0)) { SidekitTextField(goal, { goal = it }, Modifier.fillMaxWidth().padding(SidekitTheme.spacing.itemPadding), placeholder = "Steps per day", singleLine = true, dictation = false, focusOnKeypad = true, autoFocus = true, onDone = { if (vm.saveGoal(goal)) onBack() else message = "Enter a goal from 1 to 100000" }) } }
                    item { OutdoorInfo(message, modifier = Modifier.listFade(list, 1)) }
                }
                "history" -> {
                    val days = ledger?.let { (it.history + if (it.day.isNotEmpty()) listOf(StepDay(it.day, it.today, it.approximate)) else emptyList()).sortedByDescending { d -> d.date } }.orEmpty()
                    if (days.isEmpty()) item { OutdoorInfo("No samples yet", modifier = Modifier.listFade(list, 0)) }
                    items(days.size) { i -> OutdoorInfo(days[i].date, secondary = "${days[i].steps} steps${if (days[i].approximate) " · approx." else ""}", modifier = Modifier.listFade(list, i)) }
                }
                else -> {
                    item { Box(Modifier.listFade(list, 0)) { StepsCard(StepsToday(if (ledger?.day == LocalDate.now().toString()) ledger!!.today.coerceAtMost(Int.MAX_VALUE.toLong()).toInt() else 0, ledger?.goal ?: 8000)) } }
                    item { OutdoorInfo(if (!available) "No step counter on this device" else if (!allowed) message else "Steps are approximate", secondary = if (allowed && available) "Sampled while open and near midnight. Missed rollovers and reboots can lose or shift steps." else null, modifier = Modifier.listFade(list, 1)) }
                    item { OutdoorInfo("Heart rate and sleep", secondary = "No connected source", modifier = Modifier.listFade(list, 2)) }
                }
            }
            error?.let { item { OutdoorInfo(it) } }
        }
    }
}
