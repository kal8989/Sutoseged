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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

/** Alkalmazás-szintű scope: a tervezés akkor is fut tovább, ha kilépsz az appból. */
object AppScope : CoroutineScope by CoroutineScope(SupervisorJob() + Dispatchers.Main)

/**
 * A tervezőlap állapota. Azért az képernyőn kívül, egyetlen példányban, hogy sem a
 * fülváltás, sem az app háttérbe kerülése ne törölje az adatokat és ne állítsa meg
 * a folyamatban lévő tervezést.
 */
object Planner {
    var foodPreset by mutableStateOf(FoodPresets.all.first())
    var foodCustom by mutableStateOf("")
    var darab by mutableStateOf("")
    var suly by mutableStateOf("")
    var vastagsag by mutableStateOf("")
    var strategia by mutableStateOf(Strategies.all[1])
    var megjegyzes by mutableStateOf("")
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var plan by mutableStateOf<Plan?>(null)
    var savedNote by mutableStateOf<String?>(null)

    fun etel(): String = if (foodPreset.startsWith("Egyéb")) foodCustom else foodPreset

    fun request() = PlanRequest(etel(), darab, suly, vastagsag, strategia, megjegyzes)
}

@Composable
fun App() {
    val context = LocalContext.current
    val store = remember { Store(context) }
    val planner = Planner
    var tab by remember { mutableIntStateOf(0) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    LaunchedEffect(Unit) {
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
                    label = { Text("Beállítás") }, icon = { Text("⚙️", fontSize = 18.sp) }
                )
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (tab) {
                0 -> PlannerScreen(store, planner)
                1 -> SavedScreen(store)
                else -> SettingsScreen(store)
            }
        }
    }
}

// ---------------------------------------------------------------- Tervező

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlannerScreen(store: Store, st: Planner) {
    val context = LocalContext.current
    var foodMenu by remember { mutableStateOf(false) }
    var stratMenu by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Sütési terv", fontWeight = FontWeight.Bold, fontSize = 22.sp)

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
            label = { Text("Megjegyzés (pác, köret, korábbi tapasztalat…)") },
            minLines = 2,
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
                st.loading = true
                val cfg = store.aiConfig()
                val prompt = st.request().toUserPrompt()
                val appCtx = context.applicationContext
                AppScope.launch {
                    val result = AiClient.plan(cfg, prompt)
                    st.loading = false
                    result.fold(
                        onSuccess = {
                            st.plan = it
                            Notifications.show(
                                appCtx, Notifications.CH_PLAN,
                                "Elkészült a sütési terv", it.cim, 1001
                            )
                        },
                        onFailure = { st.error = it.message ?: "Ismeretlen hiba" }
                    )
                }
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
    val endAt = TimerStore.endAt[timerKey]
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    val reszlet = "${phase.futesiMod} · ${phase.homerseklet} · ${phase.szint}. szint"

    LaunchedEffect(endAt) {
        while (endAt != null) {
            now = System.currentTimeMillis()
            if (now >= endAt) {
                TimerStore.endAt.remove(timerKey)
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
                            TimerStore.endAt.remove(timerKey)
                            Alarms.cancel(appCtx, timerKey, phase.nev, reszlet)
                        } else {
                            val end = System.currentTimeMillis() + phase.idoPerc * 60_000L
                            TimerStore.endAt[timerKey] = end
                            now = System.currentTimeMillis()
                            Alarms.schedule(appCtx, timerKey, phase.nev, reszlet, end)
                        }
                    }) { Text(if (running) "Állj" else "Indít") }

                    OutlinedButton(onClick = {
                        TimerStore.endAt.remove(timerKey)
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
                .verticalScroll(rememberScrollState())
                .imePadding()
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
            .verticalScroll(rememberScrollState())
            .imePadding()
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
