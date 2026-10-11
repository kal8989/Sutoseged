package hu.kal8989.sutoseged

import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.reflect.KProperty

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Enélkül a billentyűzet inset-je nem jut el a Compose-hoz, és eltakarja a mezőket.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        Notifications.ensure(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) { App() }
        }
    }
}

/** Egy szöveges mező, ami minden változáskor azonnal a telefon tárhelyére íródik. */
class Persisted(private val store: Store, private val key: String, default: String) {
    private val state = mutableStateOf(store.getForm(key, default))
    operator fun getValue(thisRef: Any?, property: KProperty<*>): String = state.value
    operator fun setValue(thisRef: Any?, property: KProperty<*>, value: String) {
        state.value = value
        store.putForm(key, value)
    }
}

/**
 * A tervezőlap állapota. Az űrlap minden mezője, a legutóbbi terv és a tervezés
 * állapota a tárhelyen él, így az app kilövése után is minden visszajön.
 */
class PlannerState(private val store: Store) {
    var foodPreset by Persisted(store, "food", FoodPresets.all.first())
    var foodCustom by Persisted(store, "custom", "")
    var darab by Persisted(store, "darab", "")
    var suly by Persisted(store, "suly", "")
    var vastagsag by Persisted(store, "vastagsag", "")
    var strategia by Persisted(store, "strategia", Strategies.all[1])
    var megjegyzes by Persisted(store, "megjegyzes", "")

    var loading by mutableStateOf(store.planStatus == "running")
    var error by mutableStateOf(store.planError.ifBlank { null })
    var plan by mutableStateOf(store.loadLastPlan())
    var savedNote by mutableStateOf<String?>(null)

    fun etel(): String = if (foodPreset.startsWith("Egyéb")) foodCustom else foodPreset

    fun request() = PlanRequest(etel(), darab, suly, vastagsag, strategia, megjegyzes)

    /** Átveszi, amit a háttérfeladat a tárhelyre írt. */
    fun refreshFromStore() {
        val status = store.planStatus
        loading = status == "running"
        error = store.planError.ifBlank { null }
        if (status == "done") plan = store.loadLastPlan()
    }
}

@Composable
fun App() {
    val context = LocalContext.current
    val store = remember { Store(context) }
    val planner = remember { PlannerState(store) }
    var tab by remember { mutableIntStateOf(0) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    LaunchedEffect(Unit) {
        TimerStore.load(context)
        // Ha a sütő be van állítva, induláskor magától kapcsolódik, így a
        // tervező tetején azonnal látszik a hőfok és az állapot.
        if (store.ovenConfigured) LocalOven.connect(store)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0, onClick = { tab = 0 },
                    label = { Text("Tervező") }, icon = { Text("🔥", fontSize = 18.sp) }
                )
                NavigationBarItem(
                    selected = tab == 1, onClick = { tab = 1 },
                    label = { Text("Mentett") }, icon = { Text("📒", fontSize = 18.sp) }
                )
                NavigationBarItem(
                    selected = tab == 2, onClick = { tab = 2 },
                    label = { Text("Sütő") }, icon = { Text("🎛️", fontSize = 18.sp) }
                )
                NavigationBarItem(
                    selected = tab == 3, onClick = { tab = 3 },
                    label = { Text("Beállítás") }, icon = { Text("⚙️", fontSize = 18.sp) }
                )
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (tab) {
                0 -> PlannerScreen(store, planner)
                1 -> SavedScreen(store)
                2 -> OvenScreen(store)
                else -> SettingsScreen(store)
            }
        }
    }
}

// ---------------------------------------------------------------- Tervező

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlannerScreen(store: Store, st: PlannerState) {
    val context = LocalContext.current
    var foodMenu by remember { mutableStateOf(false) }
    var stratMenu by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Sütési terv", fontWeight = FontWeight.Bold, fontSize = 22.sp)

        if (LocalOven.connected) {
            Text(
                "🔌 Sütő: ${LocalOven.summary()}",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.primary
            )
        }

        ExposedDropdownMenuBox(
            expanded = foodMenu,
            onExpandedChange = { foodMenu = !foodMenu }
        ) {
            OutlinedTextField(
                value = st.foodPreset,
                onValueChange = {},
                readOnly = true,
                label = { Text("Nyersanyag") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = foodMenu) },
                modifier = Modifier
                    .menuAnchor()
                    .fillMaxWidth()
            )
            ExposedDropdownMenu(expanded = foodMenu, onDismissRequest = { foodMenu = false }) {
                FoodPresets.all.forEach { item ->
                    DropdownMenuItem(
                        text = { Text(item) },
                        onClick = { st.foodPreset = item; foodMenu = false }
                    )
                }
            }
        }

        if (st.foodPreset.startsWith("Egyéb")) {
            OutlinedTextField(
                value = st.foodCustom,
                onValueChange = { st.foodCustom = it },
                label = { Text("Mi az?") },
                modifier = Modifier.fillMaxWidth()
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = st.darab,
                onValueChange = { v -> st.darab = v.filter { it.isDigit() } },
                label = { Text("Darab") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = st.suly,
                onValueChange = { v -> st.suly = v.filter { it.isDigit() } },
                label = { Text("Összsúly (g)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1.3f)
            )
        }

        OutlinedTextField(
            value = st.vastagsag,
            onValueChange = { v -> st.vastagsag = v.filter { it.isDigit() || it == '.' || it == ',' } },
            label = { Text("Legvastagabb rész (cm) – nem kötelező") },
            modifier = Modifier.fillMaxWidth()
        )

        ExposedDropdownMenuBox(
            expanded = stratMenu,
            onExpandedChange = { stratMenu = !stratMenu }
        ) {
            OutlinedTextField(
                value = st.strategia,
                onValueChange = {},
                readOnly = true,
                label = { Text("Mit szeretnél?") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = stratMenu) },
                modifier = Modifier
                    .menuAnchor()
                    .fillMaxWidth()
            )
            ExposedDropdownMenu(expanded = stratMenu, onDismissRequest = { stratMenu = false }) {
                Strategies.all.forEach { item ->
                    DropdownMenuItem(
                        text = { Text(item) },
                        onClick = { st.strategia = item; stratMenu = false }
                    )
                }
            }
        }

        OutlinedTextField(
            value = st.megjegyzes,
            onValueChange = { st.megjegyzes = it },
            label = { Text("Recept, hozzávalók, megjegyzés") },
            placeholder = { Text("pl. 250 g zabpehely, 700 g meggybefőtt, dió…") },
            minLines = 3,
            modifier = Modifier.fillMaxWidth()
        )

        Button(
            onClick = {
                st.error = null
                st.savedNote = null
                if (st.etel().isBlank()) {
                    st.error = "Add meg, mit sütsz."
                    return@Button
                }
                val prompt = st.request().toUserPrompt()
                PlanWorker.enqueue(context.applicationContext, prompt)
                st.refreshFromStore()
            },
            enabled = !st.loading,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (st.loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(Modifier.width(10.dp))
                Text("Tervezés…")
            } else {
                Text("Terv készítése")
            }
        }

        LaunchedEffect(st.loading) {
            while (st.loading) {
                delay(1000)
                st.refreshFromStore()
            }
        }

        if (st.loading) {
            Text(
                "A tervezés a háttérben fut – nyugodtan válts másik appra, értesítést kapsz.",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        st.error?.let {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) { Text(it, Modifier.padding(12.dp)) }
        }

        st.plan?.let { p ->
            HorizontalDivider()
            PlanView(p)
            st.savedNote?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            OutlinedButton(
                onClick = {
                    store.addPlan(st.request().summary(), p)
                    st.savedNote = "Mentve a Mentett fülre."
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Terv mentése") }
        }

        Spacer(Modifier.height(48.dp))
    }
}

// ---------------------------------------------------------------- Terv

@Composable
fun PlanView(plan: Plan) {
    var showWhy by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(plan.cim, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        if (plan.osszefoglalo.isNotBlank()) Text(plan.osszefoglalo, fontSize = 14.sp)

        if (plan.figyelmeztetes.isNotBlank()) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer
                )
            ) { Text("⚠️ ${plan.figyelmeztetes}", Modifier.padding(12.dp), fontSize = 14.sp) }
        }

        if (plan.hozzavalok.isNotEmpty()) {
            Card {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Hozzávalók", fontWeight = FontWeight.SemiBold)
                    plan.hozzavalok.forEach { ing ->
                        Row(Modifier.fillMaxWidth()) {
                            Text(ing.nev, fontSize = 14.sp, modifier = Modifier.weight(1f))
                            Text(
                                ing.mennyiseg,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(start = 12.dp)
                            )
                        }
                    }
                }
            }
        }

        if (plan.receptJavitasok.isNotEmpty()) {
            Text("Amit módosítottam a leírásodon", fontWeight = FontWeight.SemiBold)
            plan.receptJavitasok.forEach { Text("• $it", fontSize = 14.sp) }
        }

        if (plan.elokeszites.isNotEmpty()) {
            Text("Előkészítés", fontWeight = FontWeight.SemiBold)
            plan.elokeszites.forEach { Text("• $it", fontSize = 14.sp) }
        }

        Text("Fázisok", fontWeight = FontWeight.SemiBold)
        plan.fazisok.forEachIndexed { i, ph ->
            PhaseCard("${plan.cim}#$i", i + 1, ph)
        }

        if (plan.maghomerseklet.isNotBlank()) {
            Card {
                Column(Modifier.padding(12.dp)) {
                    Text("Maghőmérséklet", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text(plan.maghomerseklet, fontSize = 14.sp)
                }
            }
        }

        if (plan.pihentetes.isNotBlank()) {
            Text("Pihentetés: ${plan.pihentetes}", fontSize = 14.sp)
        }

        if (plan.magyarazat.isNotBlank()) {
            TextButton(onClick = { showWhy = !showWhy }) {
                Text(if (showWhy) "Magyarázat elrejtése" else "Miért így? – magyarázat")
            }
            if (showWhy) {
                Card { Text(plan.magyarazat, Modifier.padding(12.dp), fontSize = 14.sp) }
            }
        }
    }
}

@Composable
fun PhaseCard(timerKey: String, index: Int, phase: Phase) {
    val context = LocalContext.current
    val appCtx = remember { context.applicationContext }
    val store = remember { Store(appCtx) }
    val scope = rememberCoroutineScope()
    var sendMsg by remember { mutableStateOf<String?>(null) }
    val endAt = TimerStore.endAt[timerKey]
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    val reszlet = "${phase.futesiMod} · ${phase.homerseklet} · ${phase.szint}. szint"

    LaunchedEffect(endAt) {
        while (endAt != null) {
            now = System.currentTimeMillis()
            if (now >= endAt) {
                TimerStore.stop(appCtx, timerKey)
                break
            }
            delay(500)
        }
    }

    val remaining = if (endAt != null) {
        ((endAt - now) / 1000).toInt().coerceAtLeast(0)
    } else {
        phase.idoPerc * 60
    }
    val running = endAt != null

    Card(shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("$index. ${phase.nev}", fontWeight = FontWeight.Bold)
            Text("${phase.futesiMod} · ${phase.homerseklet}", fontSize = 15.sp)
            Text(
                "${phase.szint}. szint   ·   ${phase.tartozek}",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (phase.teendo.isNotBlank()) Text(phase.teendo, fontSize = 13.sp)

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 4.dp)
            ) {
                Text(
                    formatTime(remaining),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                if (phase.idoPerc > 0) {
                    Button(onClick = {
                        if (running) {
                            TimerStore.stop(appCtx, timerKey)
                            Alarms.cancel(appCtx, timerKey, phase.nev, reszlet)
                        } else {
                            val end = System.currentTimeMillis() + phase.idoPerc * 60_000L
                            TimerStore.start(appCtx, timerKey, end)
                            now = System.currentTimeMillis()
                            Alarms.schedule(appCtx, timerKey, phase.nev, reszlet, end)
                        }
                    }) { Text(if (running) "Állj" else "Indít") }

                    OutlinedButton(onClick = {
                        TimerStore.stop(appCtx, timerKey)
                        Alarms.cancel(appCtx, timerKey, phase.nev, reszlet)
                    }) { Text("Nulláz") }
                }
            }

            if (running) {
                Text(
                    "Fut – az app bezárva is jelezni fog.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            if (store.ovenConfigured) {
                OutlinedButton(
                    onClick = {
                        sendMsg = "Küldés…"
                        scope.launch {
                            val r = withContext(Dispatchers.IO) { LocalOven.sendPhase(store, phase) }
                            sendMsg = r.fold({ it }, { it.message ?: "Nem sikerült." })
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                ) { Text("Küldés a sütőnek") }

                sendMsg?.let {
                    Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

private fun formatTime(seconds: Int): String {
    val s = seconds.coerceAtLeast(0)
    return String.format(Locale.getDefault(), "%02d:%02d", s / 60, s % 60)
}

// ---------------------------------------------------------------- Mentett

@Composable
fun SavedScreen(store: Store) {
    val context = LocalContext.current
    var plans by remember { mutableStateOf(store.loadPlans()) }
    var openId by remember { mutableStateOf<Long?>(null) }
    val fmt = remember { SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.getDefault()) }

    val current = plans.firstOrNull { it.id == openId }

    if (current != null) {
        var item by remember(current.id) { mutableStateOf(current) }
        var noteText by remember(current.id) { mutableStateOf("") }
        var pendingPhoto by remember(current.id) { mutableStateOf<File?>(null) }

        val cameraLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.TakePicture()
        ) { ok ->
            if (ok) {
                pendingPhoto?.let { f ->
                    item = item.copy(photoPath = f.absolutePath)
                    store.updatePlan(item)
                    plans = store.loadPlans()
                }
            }
        }

        Column(
            Modifier
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            TextButton(onClick = { openId = null }) { Text("‹ Vissza a listához") }
            Text(
                item.inputSummary,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            PlanView(item.plan)

            item.photoPath?.let { path ->
                val bmp = remember(path) { BitmapFactory.decodeFile(path) }
                bmp?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = "A kész étel",
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 260.dp)
                    )
                }
            }

            OutlinedButton(
                onClick = {
                    val f = store.newPhotoFile()
                    pendingPhoto = f
                    val uri: Uri = FileProvider.getUriForFile(
                        context, "${context.packageName}.fileprovider", f
                    )
                    cameraLauncher.launch(uri)
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (item.photoPath == null) "Fénykép a kész ételről" else "Új fénykép") }

            HorizontalDivider()
            Text("Jegyzetek", fontWeight = FontWeight.SemiBold)
            if (item.notes.isEmpty()) {
                Text(
                    "Még nincs jegyzet. Írd ide, ha máskor másképp csinálnád.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            item.notes.forEach { Text("• $it", fontSize = 14.sp) }

            OutlinedTextField(
                value = noteText,
                onValueChange = { noteText = it },
                label = { Text("Új jegyzet") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = {
                    if (noteText.isNotBlank()) {
                        item = item.copy(notes = item.notes + noteText.trim())
                        store.updatePlan(item)
                        plans = store.loadPlans()
                        noteText = ""
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Jegyzet hozzáadása") }

            OutlinedButton(
                onClick = {
                    store.deletePlan(item.id)
                    plans = store.loadPlans()
                    openId = null
                },
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                ),
                modifier = Modifier.fillMaxWidth()
            ) { Text("Terv törlése") }

            Spacer(Modifier.height(48.dp))
        }
        return
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Mentett tervek", fontWeight = FontWeight.Bold, fontSize = 22.sp)
        if (plans.isEmpty()) {
            Text(
                "Még nincs mentett terved.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        plans.forEach { sp ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { openId = sp.id }
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(sp.plan.cim, fontWeight = FontWeight.SemiBold)
                    Text(
                        sp.inputSummary,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        fmt.format(Date(sp.createdAt)) +
                            if (sp.notes.isNotEmpty()) "  ·  ${sp.notes.size} jegyzet" else "",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        Spacer(Modifier.height(48.dp))
    }
}

// ---------------------------------------------------------------- Sütő vezérlése

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OvenScreen(store: Store) {
    val scope = rememberCoroutineScope()

    var modeName by remember { mutableStateOf(store.getForm("mMode", "Felső/alsó sütés")) }
    var modeMenu by remember { mutableStateOf(false) }
    var temp by remember { mutableStateOf(store.getForm("mTemp", "180")) }
    var mins by remember { mutableStateOf(store.getForm("mMin", "20")) }
    var level by remember { mutableStateOf(store.getForm("mLevel", "2")) }
    var alarm by remember { mutableStateOf(store.getForm("mAlarm", "10")) }
    var fast by remember { mutableStateOf(false) }
    var blinkEnd by remember { mutableStateOf(store.blinkAtEnd) }
    var msg by remember { mutableStateOf<String?>(null) }
    var tempMsg by remember { mutableStateOf<String?>(null) }
    var timeMsg by remember { mutableStateOf<String?>(null) }
    var alarmMsg by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }

    val modeUid = OvenPrograms.manualModes.firstOrNull { it.first == modeName }?.second ?: 8209
    val isGrill = OvenPrograms.isGrill(modeUid)
    val range = OvenPrograms.tempRange(modeUid)

    /** A visszajelzés ott jelenik meg, ahol a gombot megnyomtad, nem a lap alján. */
    fun send(set: (String?) -> Unit, block: () -> Result<String>) {
        working = true
        set("Küldés…")
        scope.launch {
            val r = withContext(Dispatchers.IO) { block() }
            working = false
            set(r.fold({ "✓ $it" }, { it.message ?: "Nem sikerült." }))
        }
    }

    fun cmd(block: () -> Result<String>) = send({ msg = it }, block)

    // A nyugtázás pár másodperc után magától eltűnik.
    LaunchedEffect(tempMsg) {
        if (tempMsg?.startsWith("✓") == true) { delay(5000); tempMsg = null }
    }
    LaunchedEffect(timeMsg) {
        if (timeMsg?.startsWith("✓") == true) { delay(5000); timeMsg = null }
    }
    LaunchedEffect(alarmMsg) {
        if (alarmMsg?.startsWith("✓") == true) { delay(5000); alarmMsg = null }
    }

    Column(
        Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Sütő vezérlése", fontWeight = FontWeight.Bold, fontSize = 22.sp)

        if (!store.ovenConfigured) {
            Text(
                "Előbb add meg a sütő IP-címét és kulcsát a Beállítás fülön.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@Column
        }

        // ---------------- állapot

        Card {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                if (LocalOven.connected) {
                    Text(
                        "${LocalOven.currentTemp() ?: "–"} °C" +
                            (LocalOven.setpoint()?.let { "   ·   cél: $it °C" } ?: ""),
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    )
                    Text(
                        LocalOven.operationText() +
                            (LocalOven.activeProgram()?.let { " · ${OvenPrograms.nameOf(it)}" } ?: ""),
                        fontSize = 14.sp
                    )
                    LocalOven.remainingMin()?.let {
                        Text("Hátralévő idő: $it perc", fontSize = 14.sp)
                    }
                    Text(
                        if (LocalOven.doorOpen()) "Az ajtó nyitva" else "Az ajtó csukva",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(LocalOven.status, fontWeight = FontWeight.SemiBold)
                    LocalOven.lastError?.let {
                        Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                    }
                    Spacer(Modifier.height(4.dp))
                    Button(
                        onClick = { LocalOven.connect(store) },
                        enabled = !LocalOven.busy,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (LocalOven.busy) "Kapcsolódás…" else "Kapcsolódás") }
                }
            }
        }

        // ---------------- fűtési mód

        ExposedDropdownMenuBox(
            expanded = modeMenu,
            onExpandedChange = { modeMenu = !modeMenu }
        ) {
            OutlinedTextField(
                value = modeName,
                onValueChange = {},
                readOnly = true,
                label = { Text("Fűtési mód") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modeMenu) },
                modifier = Modifier
                    .menuAnchor()
                    .fillMaxWidth()
            )
            ExposedDropdownMenu(expanded = modeMenu, onDismissRequest = { modeMenu = false }) {
                OvenPrograms.manualModes.forEach { (name, _) ->
                    DropdownMenuItem(
                        text = { Text(name) },
                        onClick = {
                            modeName = name
                            store.putForm("mMode", name)
                            modeMenu = false
                        }
                    )
                }
            }
        }

        // ---------------- hőfok vagy grillfokozat

        if (isGrill) {
            Text("Grillfokozat", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("1", "2", "3").forEach { n ->
                    FilterChip(
                        selected = level == n,
                        onClick = { level = n; store.putForm("mLevel", n) },
                        label = { Text("$n. fokozat") }
                    )
                }
                OutlinedButton(
                    onClick = {
                        send({ tempMsg = it }) { LocalOven.setLevel(store, level.toIntOrNull() ?: 2) }
                    },
                    enabled = !working
                ) { Text("Beállít") }
            }
            tempMsg?.let {
                Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
            }
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = temp,
                    onValueChange = { v ->
                        val d = v.filter { it.isDigit() }.take(3)
                        temp = d
                        store.putForm("mTemp", d)
                    },
                    label = { Text("Hőfok (°C)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(
                    onClick = {
                        val c = temp.toIntOrNull()
                        if (c == null || c !in range) {
                            tempMsg = "A hőfok ${range.first} és ${range.last} °C között lehet."
                        } else {
                            send({ tempMsg = it }) { LocalOven.setTemp(store, c) }
                        }
                    },
                    enabled = !working
                ) { Text("Beállít") }
            }
            Text(
                tempMsg ?: ("Megengedett: ${range.first}–${range.last} °C" +
                    (LocalOven.currentTemp()?.let { "   ·   jelenleg $it °C" } ?: "")),
                fontSize = 13.sp,
                color = if (tempMsg != null) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // ---------------- idő

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = mins,
                onValueChange = { v ->
                    val d = v.filter { it.isDigit() }.take(4)
                    mins = d
                    store.putForm("mMin", d)
                },
                label = { Text("Idő (perc)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f)
            )
            OutlinedButton(
                onClick = {
                    val m = mins.toIntOrNull()
                    if (m == null || m < 1) timeMsg = "Az időt percben add meg."
                    else send({ timeMsg = it }) { LocalOven.setDurationMin(store, m) }
                },
                enabled = !working
            ) { Text("Beállít") }
        }
        Text(
            timeMsg ?: (LocalOven.remainingMin()?.let { "Hátralévő: $it perc" }
                ?: "A hőfok és az idő sütés közben is átállítható."),
            fontSize = 13.sp,
            color = if (timeMsg != null) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Gyors felfűtés", Modifier.weight(1f), fontSize = 15.sp)
            Switch(checked = fast, onCheckedChange = { fast = it })
        }

        // ---------------- vezérlés

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    cmd {
                        LocalOven.startManual(
                            store, modeUid,
                            temp.toIntOrNull(), level.toIntOrNull(),
                            mins.toIntOrNull(), fast
                        )
                    }
                },
                enabled = !working,
                modifier = Modifier.weight(1f)
            ) { Text("Indítás") }

            OutlinedButton(
                onClick = { cmd { LocalOven.abort(store) } },
                enabled = !working,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                ),
                modifier = Modifier.weight(1f)
            ) { Text("Stop") }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    if (LocalOven.paused()) cmd { LocalOven.resume(store) }
                    else cmd { LocalOven.pause(store) }
                },
                enabled = !working,
                modifier = Modifier.weight(1f)
            ) { Text(if (LocalOven.paused()) "Folytatás" else "Szünet") }

            OutlinedButton(
                onClick = {
                    if (LocalOven.powerOn()) cmd { LocalOven.switchOff(store) }
                    else cmd { LocalOven.switchOn(store) }
                },
                enabled = !working,
                modifier = Modifier.weight(1f)
            ) { Text(if (LocalOven.powerOn()) "Kikapcsolás" else "Bekapcsolás") }
        }

        HorizontalDivider()

        // ---------------- világítás

        Text("Világítás", fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { cmd { LocalOven.setLight(store, true) } },
                enabled = !working,
                modifier = Modifier.weight(1f)
            ) { Text("Be") }
            OutlinedButton(
                onClick = { cmd { LocalOven.setLight(store, false) } },
                enabled = !working,
                modifier = Modifier.weight(1f)
            ) { Text("Ki") }
            OutlinedButton(
                onClick = { cmd { LocalOven.blink(store, 5) } },
                enabled = !working,
                modifier = Modifier.weight(1.3f)
            ) { Text("Villogtatás") }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Villogjon a sütés végén", Modifier.weight(1f), fontSize = 15.sp)
            Switch(
                checked = blinkEnd,
                onCheckedChange = { blinkEnd = it; store.blinkAtEnd = it }
            )
        }

        HorizontalDivider()

        // ---------------- egyéb

        Text("Egyéb", fontWeight = FontWeight.SemiBold)

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Gyerekzár", Modifier.weight(1f), fontSize = 15.sp)
            Switch(
                checked = LocalOven.childLockOn(),
                onCheckedChange = { on -> cmd { LocalOven.setChildLock(store, on) } }
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = alarm,
                onValueChange = { v ->
                    val d = v.filter { it.isDigit() }.take(3)
                    alarm = d
                    store.putForm("mAlarm", d)
                },
                label = { Text("Konyhai időzítő (perc)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f)
            )
            OutlinedButton(
                onClick = {
                    val m = alarm.toIntOrNull()
                    if (m == null || m < 1) alarmMsg = "Az időzítőt percben add meg."
                    else send({ alarmMsg = it }) { LocalOven.setAlarmMin(store, m) }
                },
                enabled = !working
            ) { Text("Indít") }
        }
        alarmMsg?.let { Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary) }

        msg?.let { Text(it, fontSize = 14.sp, color = MaterialTheme.colorScheme.primary) }

        Spacer(Modifier.height(48.dp))
    }
}

// ---------------------------------------------------------------- Beállítások

@Composable
fun SettingsScreen(store: Store) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var provider by remember { mutableStateOf(store.provider) }
    var geminiKey by remember { mutableStateOf(store.geminiKey) }
    var geminiModel by remember { mutableStateOf(store.geminiModel) }
    var claudeKey by remember { mutableStateOf(store.claudeKey) }
    var claudeModel by remember { mutableStateOf(store.claudeModel) }
    var saved by remember { mutableStateOf(false) }

    var ovenIp by remember { mutableStateOf(store.ovenIp) }
    var ovenPsk by remember { mutableStateOf(store.ovenPsk) }
    var ovenMsg by remember { mutableStateOf<String?>(null) }

    var checking by remember { mutableStateOf(false) }
    var updateMsg by remember { mutableStateOf<String?>(null) }
    var updateUrl by remember { mutableStateOf<String?>(null) }

    fun persist() {
        store.provider = provider
        store.geminiKey = geminiKey.trim()
        store.geminiModel = geminiModel.trim().ifBlank { Store.DEFAULT_GEMINI }
        store.claudeKey = claudeKey.trim()
        store.claudeModel = claudeModel.trim().ifBlank { Store.DEFAULT_CLAUDE }
        saved = true
    }

    Column(
        Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Beállítások", fontWeight = FontWeight.Bold, fontSize = 22.sp)

        Text("Melyik AI tervezzen?", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Provider.entries.forEach { p ->
                FilterChip(
                    selected = provider == p,
                    onClick = { provider = p; saved = false },
                    label = { Text(p.label) }
                )
            }
        }

        HorizontalDivider()

        Text("Gemini", fontWeight = FontWeight.SemiBold)
        OutlinedTextField(
            value = geminiKey,
            onValueChange = { geminiKey = it; saved = false },
            label = { Text("Gemini API-kulcs") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = geminiModel,
            onValueChange = { geminiModel = it; saved = false },
            label = { Text("Gemini modell") },
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Store.GEMINI_MODELS.forEach { m ->
                AssistChip(
                    onClick = { geminiModel = m; saved = false },
                    label = { Text(m.removePrefix("gemini-"), fontSize = 12.sp) }
                )
            }
        }

        HorizontalDivider()

        Text("Claude", fontWeight = FontWeight.SemiBold)
        OutlinedTextField(
            value = claudeKey,
            onValueChange = { claudeKey = it; saved = false },
            label = { Text("Claude API-kulcs") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = claudeModel,
            onValueChange = { claudeModel = it; saved = false },
            label = { Text("Claude modell") },
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Store.CLAUDE_MODELS.forEach { m ->
                AssistChip(
                    onClick = { claudeModel = m; saved = false },
                    label = { Text(m.removePrefix("claude-"), fontSize = 12.sp) }
                )
            }
        }

        Button(onClick = { persist() }, modifier = Modifier.fillMaxWidth()) { Text("Mentés") }
        if (saved) Text("Elmentve.", color = MaterialTheme.colorScheme.primary)

        HorizontalDivider()

        Text("Sütő a helyi hálózaton", fontWeight = FontWeight.SemiBold)
        Text(
            "Az app közvetlenül a sütővel beszél a wifin, felhő nélkül. " +
                "A telefonnak ugyanazon a hálózaton kell lennie.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        OutlinedTextField(
            value = ovenIp,
            onValueChange = { ovenIp = it; store.ovenIp = it.trim() },
            label = { Text("A sütő IP-címe") },
            placeholder = { Text("192.168.1.100") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = ovenPsk,
            onValueChange = {
                val cleaned = LocalOven.cleanPsk(it)
                ovenPsk = cleaned
                store.ovenPsk = cleaned
            },
            label = { Text("A sütő kulcsa (PSK)") },
            modifier = Modifier.fillMaxWidth()
        )

        Card {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    if (LocalOven.connected) "Kapcsolódva · ${LocalOven.summary()}"
                    else LocalOven.status,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                )
                if (LocalOven.connected) {
                    Text(
                        "Távoli indítás: " +
                            if (LocalOven.remoteStartAllowed()) "engedélyezve" else "NINCS engedélyezve",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                LocalOven.lastError?.let {
                    Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    ovenMsg = null
                    if (LocalOven.connected) LocalOven.disconnect() else LocalOven.connect(store)
                },
                enabled = !LocalOven.busy,
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    when {
                        LocalOven.busy -> "Kapcsolódás…"
                        LocalOven.connected -> "Bontás"
                        else -> "Kapcsolódás"
                    }
                )
            }
            OutlinedButton(
                onClick = {
                    ovenMsg = "Küldés…"
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { LocalOven.enableRemoteStart(store) }
                        ovenMsg = r.fold(
                            { "$it – ha a sütő rákérdez, hagyd jóvá a kijelzőjén." },
                            { it.message ?: "Nem sikerült." }
                        )
                    }
                },
                enabled = store.ovenConfigured,
                modifier = Modifier.weight(1.4f)
            ) { Text("Távoli indítás be") }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    ovenMsg = "Küldés…"
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { LocalOven.switchOn(store) }
                        ovenMsg = r.fold({ it }, { it.message ?: "Nem sikerült." })
                    }
                },
                enabled = store.ovenConfigured,
                modifier = Modifier.weight(1f)
            ) { Text("Bekapcsolás") }

            OutlinedButton(
                onClick = {
                    ovenMsg = "Küldés…"
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { LocalOven.abort(store) }
                        ovenMsg = r.fold({ it }, { it.message ?: "Nem sikerült." })
                    }
                },
                enabled = store.ovenConfigured,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                ),
                modifier = Modifier.weight(1f)
            ) { Text("Leállítás") }
        }

        ovenMsg?.let { Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary) }

        HorizontalDivider()

        Text("Alkalmazás", fontWeight = FontWeight.SemiBold)
        Text(
            "Telepített verzió: ${Updater.currentVersion(context)}",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        OutlinedButton(
            onClick = {
                checking = true
                updateMsg = null
                updateUrl = null
                scope.launch {
                    val r = Updater.check(context)
                    checking = false
                    r.fold(
                        onSuccess = { info ->
                            if (info.isNewer) {
                                updateMsg = "Új verzió elérhető: ${info.latestVersion}"
                                updateUrl = info.apkUrl ?: info.releaseUrl
                            } else {
                                updateMsg = "A legfrissebb verziót használod."
                            }
                        },
                        onFailure = { updateMsg = it.message ?: "Nem sikerült ellenőrizni." }
                    )
                }
            },
            enabled = !checking,
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (checking) "Ellenőrzés…" else "Frissítés keresése") }

        updateMsg?.let { Text(it, fontSize = 14.sp) }

        updateUrl?.let { url ->
            Button(
                onClick = {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Letöltés és telepítés") }
        }

        HorizontalDivider()
        Text(
            "A kulcsok csak ezen a telefonon tárolódnak. A sütő adatai (fűtési módok, " +
                "betolási magasságok, gyári beállítási táblázat) az appba vannak építve.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(48.dp))
    }
}
