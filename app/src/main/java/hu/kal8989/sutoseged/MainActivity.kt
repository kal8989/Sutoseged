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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
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

    // Előtérbe kerüléskor újra kapcsolódik: elalvás vagy wifi-váltás után
    // ne maradjon "Kapcsolódva" felirat egy halott kapcsolaton.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                LocalOven.foreground = true
                if (store.ovenConfigured) LocalOven.connect(store)
            } else if (event == Lifecycle.Event.ON_STOP) {
                LocalOven.foreground = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
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
                    label = { Text("Sütő") }, icon = { OvenTabIcon() }
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
                    // Külön sorban a név és a mennyiség: a hosszú mennyiségek
                    // ("kb. 15 karika, 1 cm vastag") egy oszlopban szétnyomnák a nevet.
                    plan.hozzavalok.forEach { ing ->
                        Column(Modifier.padding(top = 4.dp)) {
                            Text(ing.nev, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                ing.mennyiseg,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
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
        var deletePhoto by remember(current.id) { mutableStateOf<String?>(null) }

        val cameraLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.TakePicture()
        ) { ok ->
            val f = pendingPhoto
            pendingPhoto = null
            if (f != null) {
                if (ok && f.length() > 0) {
                    // Az új kép a korábbiak mellé kerül, nem a helyükre.
                    item = item.copy(photos = item.photos + f.absolutePath)
                    store.updatePlan(item)
                    plans = store.loadPlans()
                } else {
                    runCatching { f.delete() }
                }
            }
        }

        deletePhoto?.let { path ->
            AlertDialog(
                onDismissRequest = { deletePhoto = null },
                title = { Text("Törlöd ezt a képet?") },
                text = { Text("A többi kép és a jegyzetek megmaradnak.") },
                confirmButton = {
                    TextButton(onClick = {
                        item = item.copy(photos = item.photos - path)
                        store.updatePlan(item)
                        plans = store.loadPlans()
                        runCatching { File(path).delete() }
                        deletePhoto = null
                    }) { Text("Törlés") }
                },
                dismissButton = { TextButton(onClick = { deletePhoto = null }) { Text("Mégsem") } }
            )
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

            if (item.photos.isNotEmpty()) {
                HorizontalDivider()
                Text("Fényképek (${item.photos.size})", fontWeight = FontWeight.SemiBold)
                // A legújabb kép van felül.
                item.photos.asReversed().forEach { path ->
                    PhotoItem(path, fmt) { deletePhoto = path }
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
            ) { Text(if (item.photos.isEmpty()) "Fénykép a kész ételről" else "Még egy fénykép") }

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
                            (if (sp.notes.isNotEmpty()) "  ·  ${sp.notes.size} jegyzet" else "") +
                            (if (sp.photos.isNotEmpty()) "  ·  ${sp.photos.size} kép" else ""),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        Spacer(Modifier.height(48.dp))
    }
}

/** Egy mentett fénykép: kicsinyítve töltjük be, hogy sok kép se fogyassza el a memóriát. */
@Composable
private fun PhotoItem(path: String, fmt: SimpleDateFormat, onDelete: () -> Unit) {
    val bmp = remember(path) { loadPreview(path, 1280) }
    val taken = remember(path) { File(path).lastModified() }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "A kész étel",
                contentScale = ContentScale.FillWidth,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 300.dp)
                    .clip(RoundedCornerShape(10.dp))
            )
        } else {
            Text(
                "A kép nem tölthető be.",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (taken > 0) fmt.format(Date(taken)) else "",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onDelete) {
                Text("Kép törlése", color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            }
        }
    }
}

/** Kicsinyített, a fényképező tájolása szerint elforgatott kép, vagy null. */
private fun loadPreview(path: String, maxSide: Int): android.graphics.Bitmap? = try {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= maxSide || bounds.outHeight / (sample * 2) >= maxSide) {
        sample *= 2
    }
    val bmp = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    val rotation = when (
        android.media.ExifInterface(path).getAttributeInt(
            android.media.ExifInterface.TAG_ORIENTATION,
            android.media.ExifInterface.ORIENTATION_NORMAL
        )
    ) {
        android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
        android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
        android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
        else -> 0f
    }
    if (bmp != null && rotation != 0f) {
        val m = android.graphics.Matrix().apply { postRotate(rotation) }
        android.graphics.Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    } else {
        bmp
    }
} catch (e: Exception) {
    null
}

// ---------------------------------------------------------------- Sütő vezérlése

// Piros: pontosan ez van most a sütőn. Sárga: átírtad, de még nincs kiküldve.
private val SyncedColor = Color(0xFFE2563C)
private val PendingColor = Color(0xFFE9B429)

/** A fülsáv ikonja: sütő ajtóval és kezelőpanellel, nem főzőlap. */
@Composable
fun OvenTabIcon() {
    val tint = LocalContentColor.current
    Canvas(Modifier.size(22.dp)) {
        val w = size.width
        val h = size.height
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.10f, h * 0.08f),
            size = Size(w * 0.80f, h * 0.84f),
            cornerRadius = CornerRadius(w * 0.10f),
            style = Stroke(width = w * 0.09f)
        )
        // kezelőpanel elválasztó vonala
        drawLine(
            color = tint,
            start = Offset(w * 0.10f, h * 0.31f),
            end = Offset(w * 0.90f, h * 0.31f),
            strokeWidth = w * 0.07f
        )
        // két gomb a panelen
        drawCircle(color = tint, radius = w * 0.05f, center = Offset(w * 0.27f, h * 0.19f))
        drawCircle(color = tint, radius = w * 0.05f, center = Offset(w * 0.45f, h * 0.19f))
        // ajtóüveg
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.23f, h * 0.44f),
            size = Size(w * 0.54f, h * 0.36f),
            cornerRadius = CornerRadius(w * 0.06f),
            style = Stroke(width = w * 0.07f)
        )
    }
}

/**
 * A kör alakú kijelző. A külső ív a beállított érték (piros vagy sárga),
 * a belső vékony ív a sütőtér tényleges hőmérséklete.
 */
@Composable
private fun TempGauge(
    targetFraction: Float,
    currentFraction: Float,
    valueColor: Color,
    size: Dp = 220.dp,
    content: @Composable () -> Unit
) {
    val track = Color.White.copy(alpha = 0.16f)
    val live = Color(0xFFFFD9A8)
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val stroke = this.size.minDimension * 0.075f
            val inset = stroke * 0.7f
            val tl = Offset(inset, inset)
            val arcSize = Size(this.size.width - inset * 2, this.size.height - inset * 2)
            drawArc(
                color = track,
                startAngle = 135f, sweepAngle = 270f, useCenter = false,
                topLeft = tl, size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
            if (targetFraction > 0f) {
                drawArc(
                    color = valueColor,
                    startAngle = 135f, sweepAngle = 270f * targetFraction, useCenter = false,
                    topLeft = tl, size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
            }
            if (currentFraction > 0f) {
                val inner = inset + stroke * 1.5f
                drawArc(
                    color = live,
                    startAngle = 135f, sweepAngle = 270f * currentFraction, useCenter = false,
                    topLeft = Offset(inner, inner),
                    size = Size(this.size.width - inner * 2, this.size.height - inner * 2),
                    style = Stroke(width = stroke * 0.4f, cap = StrokeCap.Round)
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) { content() }
    }
}

// ---------------------------------------------------------------- a sütőtér rajza

private val HeroMuted = Color(0xFFD9CFC6)
private val HeroShadow = Shadow(Color.Black.copy(alpha = 0.7f), Offset(0f, 1.5f), 6f)

/** Szöveg a sütőtér képén: világos, enyhe árnyékkal, hogy a háttéren is olvasható legyen. */
@Composable
private fun HeroText(
    text: String,
    fontSize: TextUnit,
    fontWeight: FontWeight = FontWeight.Normal,
    color: Color = Color.White
) {
    Text(
        text,
        fontSize = fontSize,
        fontWeight = fontWeight,
        color = color,
        maxLines = 1,
        style = LocalTextStyle.current.copy(shadow = HeroShadow)
    )
}

/**
 * A sütőtér fényképe a vezérlőlap fejlécéhez, rajta a sütő állapota szerinti fény:
 * kikapcsolt, sötét sütőnél elsötétül, felkapcsolt lámpánál kivilágosodik, a hőfokkal
 * pedig meleg narancs fényt kap ([heat] 0..1). Alul a lap hátterébe olvad.
 */
@Composable
private fun OvenBackdrop(heat: Float, lampOn: Boolean, fade: Color, modifier: Modifier = Modifier) {
    val glow by animateFloatAsState(heat.coerceIn(0f, 1f), animationSpec = tween(1500), label = "glow")
    val lamp by animateFloatAsState(if (lampOn) 1f else 0f, animationSpec = tween(600), label = "lamp")
    Box(modifier) {
        Image(
            painter = painterResource(R.drawable.oven_bg),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.matchParentSize()
        )
        Canvas(Modifier.matchParentSize()) { drawOvenLight(glow, lamp, fade) }
    }
}

private fun DrawScope.drawOvenLight(glow: Float, lamp: Float, fade: Color) {
    val w = size.width
    val h = size.height
    // sötét sütő: elsötétített kép; lámpa és hő: egyre világosabb
    val dark = (0.58f - 0.30f * lamp - 0.18f * glow).coerceIn(0.08f, 0.58f)
    drawRect(Color.Black.copy(alpha = dark))
    // meleg fény felülről, a hőfokkal arányosan
    if (glow > 0f) {
        drawRect(
            Brush.radialGradient(
                listOf(Color(0xFFFF9A40).copy(alpha = 0.32f * glow), Color(0x00FF9A40)),
                Offset(w / 2, h * 0.15f), w * 0.8f
            )
        )
    }
    // sötétítés a szövegek mögött: fent a fejléc, balra a kijelző, jobbra az állapot
    drawRect(
        Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent), 0f, h * 0.3f),
        size = Size(w, h * 0.3f)
    )
    drawRect(
        Brush.radialGradient(
            listOf(Color.Black.copy(alpha = 0.5f), Color.Transparent),
            Offset(w * 0.25f, h * 0.62f), w * 0.34f
        )
    )
    drawRect(
        Brush.radialGradient(
            listOf(Color.Black.copy(alpha = 0.45f), Color.Transparent),
            Offset(w * 0.74f, h * 0.64f), w * 0.38f
        )
    )
    // lent átmenet a lap hátterébe
    drawRect(
        Brush.verticalGradient(listOf(fade.copy(alpha = 0f), fade.copy(alpha = 0.85f)), h * 0.6f, h),
        topLeft = Offset(0f, h * 0.6f),
        size = Size(w, h * 0.4f)
    )
}

/** Szám beírása a kijelzőre bökve. */
@Composable
private fun NumberDialog(
    title: String,
    initial: Int,
    range: IntRange,
    unit: String,
    onDismiss: () -> Unit,
    onOk: (Int) -> Unit
) {
    var text by remember { mutableStateOf(initial.toString()) }
    val n = text.toIntOrNull()
    val valid = n != null && n >= range.first && n <= range.last
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { v -> text = v.filter { it.isDigit() }.take(4) },
                    label = { Text(unit) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                Text(
                    "${range.first}–${range.last} $unit",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { n?.let(onOk) }, enabled = valid) { Text("Kész") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Mégsem") } }
    )
}

/** Egy beállítókártya: −, a számra bökve beírható érték, +, csúszka, Beállít. */
@Composable
private fun ValueCard(
    title: String,
    value: Int,
    unit: String,
    actual: String?,
    color: Color,
    sliderRange: ClosedFloatingPointRange<Float>,
    sliderSteps: Int,
    enabled: Boolean,
    message: String?,
    hint: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    onSlider: (Int) -> Unit,
    onTap: () -> Unit,
    onApply: () -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                actual?.let {
                    Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                FilledTonalButton(
                    onClick = onMinus,
                    modifier = Modifier.size(52.dp),
                    contentPadding = PaddingValues(0.dp)
                ) { Text("−", fontSize = 24.sp) }

                Box(
                    Modifier
                        .weight(1f)
                        .clickable { onTap() },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "$value $unit",
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Bold,
                        color = color,
                        textAlign = TextAlign.Center
                    )
                }

                FilledTonalButton(
                    onClick = onPlus,
                    modifier = Modifier.size(52.dp),
                    contentPadding = PaddingValues(0.dp)
                ) { Text("+", fontSize = 24.sp) }
            }

            Slider(
                value = value.toFloat().coerceIn(sliderRange.start, sliderRange.endInclusive),
                onValueChange = { onSlider(it.roundToInt()) },
                valueRange = sliderRange,
                steps = sliderSteps
            )

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    message ?: hint,
                    fontSize = 12.sp,
                    color = if (message != null) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(onClick = onApply, enabled = enabled) { Text("Beállít") }
            }
        }
    }
}

@Composable
fun OvenScreen(store: Store) {
    val scope = rememberCoroutineScope()

    var modeUid by remember {
        mutableIntStateOf(store.getForm("mModeUid", "8209").toIntOrNull() ?: 8209)
    }
    var tempC by remember { mutableIntStateOf(store.getForm("mTemp", "180").toIntOrNull() ?: 180) }
    var durMin by remember { mutableIntStateOf(store.getForm("mMin", "20").toIntOrNull() ?: 20) }
    var levelN by remember { mutableIntStateOf(store.getForm("mLevel", "2").toIntOrNull() ?: 2) }
    var alarm by remember { mutableStateOf(store.getForm("mAlarm", "10")) }

    // Sárga, amíg a helyben átírt érték nincs kiküldve; utána ismét piros.
    var modeDirty by remember { mutableStateOf(false) }
    var tempDirty by remember { mutableStateOf(false) }
    var timeDirty by remember { mutableStateOf(false) }
    var levelDirty by remember { mutableStateOf(false) }

    var fast by remember { mutableStateOf(false) }
    var blinkEnd by remember { mutableStateOf(store.blinkAtEnd) }
    var working by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var tempMsg by remember { mutableStateOf<String?>(null) }
    var timeMsg by remember { mutableStateOf<String?>(null) }
    var alarmMsg by remember { mutableStateOf<String?>(null) }
    var allModes by remember { mutableStateOf(false) }
    var editTemp by remember { mutableStateOf(false) }
    var editTime by remember { mutableStateOf(false) }

    val isGrill = OvenPrograms.isGrill(modeUid)
    val range = OvenPrograms.tempRange(modeUid)

    // A lap megnyitásakor magától kapcsolódik, és behúzza a sütő aktuális beállításait.
    LaunchedEffect(Unit) {
        if (store.ovenConfigured && !LocalOven.connected) LocalOven.connect(store)
    }

    // Másodpercenkénti ütés: a hátralévő idő a két sütőjelentés között is fogy.
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            tick++
        }
    }

    // A sütő értékei töltik a mezőket, amíg nem írod át őket.
    val ovenProgram = LocalOven.activeProgram()
    val ovenTemp = LocalOven.setpoint()
    val ovenDur = LocalOven.totalSec()
    val ovenLevel = LocalOven.level()

    LaunchedEffect(ovenProgram, modeDirty) {
        if (!modeDirty && ovenProgram != null &&
            OvenPrograms.manualModes.any { it.second == ovenProgram }
        ) {
            modeUid = ovenProgram
            store.putForm("mModeUid", ovenProgram.toString())
        }
    }
    LaunchedEffect(ovenTemp, tempDirty) {
        if (!tempDirty && ovenTemp != null && ovenTemp > 0) {
            tempC = ovenTemp
            store.putForm("mTemp", ovenTemp.toString())
        }
    }
    LaunchedEffect(ovenDur, timeDirty) {
        if (!timeDirty && ovenDur != null && ovenDur > 0) {
            durMin = ovenDur / 60
            store.putForm("mMin", (ovenDur / 60).toString())
        }
    }
    LaunchedEffect(ovenLevel, levelDirty) {
        if (!levelDirty && ovenLevel != null && ovenLevel in 1..3) {
            levelN = ovenLevel
            store.putForm("mLevel", ovenLevel.toString())
        }
    }
    // Módváltáskor a hőfok beugrik az új mód tartományába.
    LaunchedEffect(modeUid) {
        val fixed = tempC.coerceIn(range.first, range.last)
        if (fixed != tempC) {
            tempC = fixed
            store.putForm("mTemp", fixed.toString())
        }
    }

    /**
     * Elküld egy parancsot, és a válasz ott jelenik meg, ahol megnyomtad.
     * Ha van [confirmed] feltétel, a sárga csak akkor vált vissza pirosra
     * ([onConfirmed]), amikor a sütő már vissza is jelentette az új értéket.
     */
    fun send(
        set: (String?) -> Unit,
        confirmed: (() -> Boolean)? = null,
        onConfirmed: (() -> Unit)? = null,
        block: () -> Result<String>
    ) {
        working = true
        set("Küldés…")
        scope.launch {
            val r = withContext(Dispatchers.IO) { block() }
            working = false
            set(r.fold({ "✓ $it" }, { it.message ?: "Nem sikerült." }))
            if (r.isSuccess && onConfirmed != null) {
                var ok = confirmed?.invoke() ?: true
                val until = System.currentTimeMillis() + 6000
                while (!ok && System.currentTimeMillis() < until) {
                    delay(250)
                    ok = confirmed?.invoke() ?: true
                }
                if (ok) onConfirmed()
                else set("Elküldve, de a sütő nem jelezte vissza az új értéket – ezért maradt sárga.")
            }
        }
    }

    fun cmd(block: () -> Result<String>) = send({ msg = it }, null, null, block)

    // Átállítani csak futó programon lehet; üresjáratban az érték az indításkor kerül ki.
    val canAdjust = LocalOven.running() || LocalOven.paused()

    LaunchedEffect(tempMsg) {
        if (tempMsg?.startsWith("✓") == true) { delay(5000); tempMsg = null }
    }
    LaunchedEffect(timeMsg) {
        if (timeMsg?.startsWith("✓") == true) { delay(5000); timeMsg = null }
    }
    LaunchedEffect(alarmMsg) {
        if (alarmMsg?.startsWith("✓") == true) { delay(5000); alarmMsg = null }
    }
    LaunchedEffect(msg) {
        if (msg?.startsWith("✓") == true) { delay(6000); msg = null }
    }

    Column(
        Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (!store.ovenConfigured) {
            Text("Sütő vezérlése", fontWeight = FontWeight.Bold, fontSize = 22.sp)
            Text(
                "Előbb add meg a sütő IP-címét és kulcsát a Beállítás fülön.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@Column
        }

        // ---------------- fejléc: a sütőtér képe, rajta a kijelző és az állapot

        val current = LocalOven.currentTemp()
        val valueColor = if (isGrill) {
            if (levelDirty) PendingColor else SyncedColor
        } else {
            if (tempDirty) PendingColor else SyncedColor
        }
        val targetFraction = if (isGrill) {
            levelN.coerceIn(1, 3) / 3f
        } else {
            ((tempC - range.first).toFloat() / (range.last - range.first)).coerceIn(0f, 1f)
        }
        val currentFraction = if (isGrill || current == null) 0f else
            ((current - range.first).toFloat() / (range.last - range.first)).coerceIn(0f, 1f)

        // a tick olvasása kell, hogy másodpercenként újrarajzolódjon
        val remSec = if (tick >= 0L) LocalOven.remainingSec() else null
        val totalSec = LocalOven.totalSec()

        // A sütőtér fénye a tényleges hőfokkal nő; kikapcsolt, hűlő sütőnél csak halvány.
        val active = LocalOven.running() || LocalOven.paused()
        val tempHeat = if (LocalOven.connected && current != null) {
            ((current - 40) / 200f).coerceIn(0f, 1f)
        } else 0f
        val heat = if (active) maxOf(0.35f, tempHeat) else tempHeat * 0.5f
        val lampOn = LocalOven.connected && (LocalOven.lightOn() || active)

        Box(
            Modifier
                .fillMaxWidth()
                .height(300.dp)
                .clip(RoundedCornerShape(20.dp))
        ) {
            OvenBackdrop(
                heat = heat,
                lampOn = lampOn,
                fade = MaterialTheme.colorScheme.background,
                modifier = Modifier.matchParentSize()
            )
            Column(
                Modifier
                    .matchParentSize()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        HeroText("Sütő", 22.sp, FontWeight.Bold)
                        HeroText("Bosch HBG7341B1", 12.sp, color = HeroMuted)
                    }
                    if (LocalOven.connected) {
                        Column(horizontalAlignment = Alignment.End) {
                            HeroText("csatlakoztatva", 12.sp, color = HeroMuted)
                            HeroText(
                                if (LocalOven.doorOpen()) "ajtó nyitva" else "ajtó csukva",
                                12.sp,
                                color = HeroMuted
                            )
                        }
                    } else {
                        TextButton(
                            onClick = { LocalOven.connect(store) },
                            enabled = !LocalOven.busy
                        ) { Text(if (LocalOven.busy) "Kapcsolódás…" else "Kapcsolódás") }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    TempGauge(targetFraction, currentFraction, valueColor, size = 164.dp) {
                        HeroText("aktuális", 11.sp, color = HeroMuted)
                        HeroText(
                            if (LocalOven.connected && current != null) "$current °C" else "– °C",
                            32.sp,
                            FontWeight.Bold
                        )
                        HeroText(
                            if (isGrill) "cél: ${levelN}. fokozat" else "cél: $tempC °C",
                            14.sp,
                            FontWeight.SemiBold,
                            color = valueColor
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(
                        Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        HeroText(
                            when {
                                !LocalOven.connected -> "Nincs kapcsolat"
                                LocalOven.running() -> "Sütés folyamatban"
                                LocalOven.paused() -> "Szüneteltetve"
                                else -> LocalOven.operationText().replaceFirstChar { it.uppercase() }
                            },
                            16.sp,
                            FontWeight.Bold
                        )
                        HeroText(
                            OvenPrograms.nameOf(modeUid),
                            13.sp,
                            color = if (modeDirty) PendingColor else HeroMuted
                        )
                        if (remSec != null) {
                            Spacer(Modifier.height(6.dp))
                            HeroText("hátralévő idő", 11.sp, color = HeroMuted)
                            Row(verticalAlignment = Alignment.Bottom) {
                                HeroText(LocalOven.formatClock(remSec), 28.sp, FontWeight.Bold)
                                if (totalSec != null) {
                                    HeroText(" / ${LocalOven.formatClock(totalSec)}", 13.sp, color = HeroMuted)
                                }
                            }
                            LocalOven.progress()?.let { p ->
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .height(6.dp)
                                        .clip(RoundedCornerShape(3.dp))
                                        .background(Color.White.copy(alpha = 0.18f))
                                ) {
                                    if (p > 0f) {
                                        Box(
                                            Modifier
                                                .fillMaxWidth(p)
                                                .fillMaxHeight()
                                                .background(SyncedColor)
                                        )
                                    }
                                }
                            }
                            if (totalSec != null) {
                                HeroText(
                                    "eltelt ${LocalOven.formatClock((totalSec - remSec).coerceAtLeast(0))}",
                                    11.sp,
                                    color = HeroMuted
                                )
                            }
                        }
                    }
                }
            }
        }

        LocalOven.lastError?.takeIf { !LocalOven.connected }?.let {
            Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
        }

        // ---------------- fűtési mód

        Text("Fűtési mód", fontWeight = FontWeight.SemiBold)

        val quick = remember {
            listOf(8217, 8209, 8277, 8213, 8224, 8212, 8226, 8215)
        }
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            quick.forEach { uid ->
                val selected = uid == modeUid
                val border = when {
                    !selected -> MaterialTheme.colorScheme.outlineVariant
                    modeDirty -> PendingColor
                    else -> SyncedColor
                }
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (selected) MaterialTheme.colorScheme.surfaceVariant
                        else MaterialTheme.colorScheme.surface
                    ),
                    border = BorderStroke(if (selected) 2.dp else 1.dp, border),
                    modifier = Modifier
                        .width(128.dp)
                        .clickable {
                            modeUid = uid
                            modeDirty = true
                            store.putForm("mModeUid", uid.toString())
                        }
                ) {
                    Text(
                        OvenPrograms.nameOf(uid),
                        Modifier.padding(10.dp),
                        fontSize = 13.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
            OutlinedButton(onClick = { allModes = true }) { Text("Összes mód") }
        }

        if (allModes) {
            AlertDialog(
                onDismissRequest = { allModes = false },
                title = { Text("Fűtési mód") },
                text = {
                    Column(
                        Modifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        OvenPrograms.manualModes.forEach { (name, uid) ->
                            TextButton(
                                onClick = {
                                    modeUid = uid
                                    modeDirty = true
                                    store.putForm("mModeUid", uid.toString())
                                    allModes = false
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    name,
                                    modifier = Modifier.fillMaxWidth(),
                                    color = if (uid == modeUid) SyncedColor
                                    else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { allModes = false }) { Text("Mégsem") }
                }
            )
        }

        // ---------------- hőfok vagy grillfokozat

        if (isGrill) {
            Card(Modifier.fillMaxWidth()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Grillfokozat", fontWeight = FontWeight.SemiBold)
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(1, 2, 3).forEach { n ->
                            FilterChip(
                                selected = levelN == n,
                                onClick = {
                                    levelN = n
                                    levelDirty = true
                                    store.putForm("mLevel", n.toString())
                                },
                                label = { Text("$n.") }
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        OutlinedButton(
                            onClick = {
                                send(
                                    { tempMsg = it },
                                    { LocalOven.level()?.let { it == levelN } ?: true },
                                    { levelDirty = false }
                                ) { LocalOven.setLevel(store, levelN) }
                            },
                            enabled = !working && canAdjust
                        ) { Text("Beállít") }
                    }
                    Text(
                        tempMsg ?: if (canAdjust) "sütés közben is átállítható"
                        else "indításkor kerül a sütőre",
                        fontSize = 12.sp,
                        color = if (tempMsg != null) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            ValueCard(
                title = "Hőfok",
                value = tempC,
                unit = "°C",
                actual = LocalOven.currentTemp()?.let { "(jelenleg $it °C)" },
                color = if (tempDirty) PendingColor else SyncedColor,
                sliderRange = range.first.toFloat()..range.last.toFloat(),
                sliderSteps = ((range.last - range.first) / 5 - 1).coerceAtLeast(0),
                enabled = !working && canAdjust,
                message = tempMsg,
                hint = "${range.first}–${range.last} °C  ·  " +
                    if (canAdjust) "sütés közben is átállítható" else "indításkor kerül a sütőre",
                onMinus = {
                    tempC = (tempC - 5).coerceAtLeast(range.first); tempDirty = true
                    store.putForm("mTemp", tempC.toString())
                },
                onPlus = {
                    tempC = (tempC + 5).coerceAtMost(range.last); tempDirty = true
                    store.putForm("mTemp", tempC.toString())
                },
                onSlider = { v ->
                    tempC = v.coerceIn(range.first, range.last); tempDirty = true
                    store.putForm("mTemp", tempC.toString())
                },
                onTap = { editTemp = true },
                onApply = {
                    if (tempC !in range) {
                        tempMsg = "A hőfok ${range.first} és ${range.last} °C között lehet."
                    } else {
                        send(
                            { tempMsg = it },
                            { LocalOven.setpoint() == tempC },
                            { tempDirty = false }
                        ) { LocalOven.setTemp(store, tempC) }
                    }
                }
            )
        }

        if (editTemp) {
            NumberDialog(
                title = "Hőfok beírása",
                initial = tempC,
                range = range,
                unit = "°C",
                onDismiss = { editTemp = false },
                onOk = { v ->
                    tempC = v
                    tempDirty = true
                    store.putForm("mTemp", v.toString())
                    editTemp = false
                }
            )
        }

        // ---------------- idő

        ValueCard(
            title = "Sütési idő",
            value = durMin,
            unit = "perc",
            actual = (if (tick >= 0L) LocalOven.remainingSec() else null)
                ?.let { "(hátra ${LocalOven.formatClock(it)})" },
            color = if (timeDirty) PendingColor else SyncedColor,
            sliderRange = 0f..240f,
            sliderSteps = 47,
            enabled = !working && canAdjust,
            message = timeMsg,
            hint = "0 = időzítés nélkül  ·  " +
                if (canAdjust) "sütés közben is átállítható" else "indításkor kerül a sütőre",
            onMinus = {
                durMin = (durMin - 1).coerceAtLeast(0); timeDirty = true
                store.putForm("mMin", durMin.toString())
            },
            onPlus = {
                durMin = (durMin + 1).coerceAtMost(1439); timeDirty = true
                store.putForm("mMin", durMin.toString())
            },
            onSlider = { v ->
                durMin = v.coerceIn(0, 240); timeDirty = true
                store.putForm("mMin", durMin.toString())
            },
            onTap = { editTime = true },
            onApply = {
                if (durMin < 1) timeMsg = "Az időt legalább 1 percre állítsd."
                else send(
                    { timeMsg = it },
                    { LocalOven.totalSec() == durMin * 60 },
                    { timeDirty = false }
                ) { LocalOven.setDurationMin(store, durMin) }
            }
        )

        if (editTime) {
            NumberDialog(
                title = "Sütési idő beírása",
                initial = durMin,
                range = 0..1439,
                unit = "perc",
                onDismiss = { editTime = false },
                onOk = { v ->
                    durMin = v
                    timeDirty = true
                    store.putForm("mMin", v.toString())
                    editTime = false
                }
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Gyors felfűtés", Modifier.weight(1f), fontSize = 15.sp)
            Switch(checked = fast, onCheckedChange = { fast = it })
        }

        // ---------------- indítás és leállítás

        Button(
            onClick = {
                val grill = isGrill
                send(
                    { msg = it },
                    {
                        LocalOven.activeProgram() == modeUid &&
                            (grill || LocalOven.setpoint() == tempC) &&
                            (!grill || (LocalOven.level()?.let { it == levelN } ?: true)) &&
                            (durMin == 0 || LocalOven.totalSec() == durMin * 60)
                    },
                    {
                        modeDirty = false
                        tempDirty = false
                        timeDirty = false
                        levelDirty = false
                    }
                ) {
                    LocalOven.startManual(
                        store, modeUid,
                        if (isGrill) null else tempC,
                        if (isGrill) levelN else null,
                        durMin.takeIf { it > 0 },
                        fast
                    )
                }
            },
            enabled = !working,
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp)
        ) {
            Text(
                if (LocalOven.running() || LocalOven.paused()) "Indítás (felülírja a futót)"
                else "Indítás",
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    if (LocalOven.paused()) cmd { LocalOven.resume(store) }
                    else cmd { LocalOven.pause(store) }
                },
                enabled = !working && canAdjust,
                modifier = Modifier.weight(1f)
            ) { Text(if (LocalOven.paused()) "Folytatás" else "Szünet") }

            OutlinedButton(
                onClick = { cmd { LocalOven.abort(store) } },
                enabled = !working,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                ),
                modifier = Modifier.weight(1f)
            ) { Text("Leállítás") }
        }

        OutlinedButton(
            onClick = {
                if (LocalOven.powerOn()) cmd { LocalOven.switchOff(store) }
                else cmd { LocalOven.switchOn(store) }
            },
            enabled = !working,
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (LocalOven.powerOn()) "Kikapcsolás (készenlét)" else "Bekapcsolás") }

        msg?.let { Text(it, fontSize = 14.sp, color = MaterialTheme.colorScheme.primary) }

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
                modifier = Modifier.weight(1.4f)
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
