package com.nida.nidamap

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.nida.nidamap.data.DatasetInfo
import com.nida.nidamap.data.GtfsImporter
import com.nida.nidamap.data.RailDatabase
import com.nida.nidamap.data.RouteResult
import com.nida.nidamap.data.Station
import com.nida.nidamap.data.TimetableEntry
import com.nida.nidamap.routing.RoutePlanner
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

private val NidaGreen = Color(0xFF008F62)
private val Ink = Color(0xFF17211D)
private val Muted = Color(0xFF66736D)
private val PageBackground = Color(0xFFF5F8F6)
private val RailLineColor = Color(0xFFD5E9E0)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = PageBackground) {
                    NidaMapApp()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NidaMapApp() {
    val context = LocalContext.current
    val database = remember(context) { RailDatabase(context.applicationContext) }
    DisposableEffect(database) {
        onDispose { database.close() }
    }

    var stations by remember { mutableStateOf(database.getStations()) }
    var datasetInfo by remember { mutableStateOf(database.getDatasetInfo()) }
    var dataRevision by remember { mutableIntStateOf(0) }
    var selectedTab by remember { mutableIntStateOf(0) }
    var searchResult by remember { mutableStateOf<RouteResult?>(null) }
    var rideStarted by remember { mutableStateOf(false) }
    var rideStep by remember { mutableIntStateOf(0) }
    var gpsEnabled by remember { mutableStateOf(false) }
    var gpsStatus by remember { mutableStateOf("乗車ガイドは停止中") }
    var motionDetected by remember { mutableStateOf(false) }
    var dataStatus by remember { mutableStateOf("データはこの端末内に保存されます。") }
    val scope = rememberCoroutineScope()
    var confirmReset by remember { mutableStateOf(false) }

    fun refreshData() {
        stations = database.getStations()
        datasetInfo = database.getDatasetInfo()
        dataRevision += 1
        searchResult = null
        rideStarted = false
        rideStep = 0
        gpsEnabled = false
        gpsStatus = "乗車ガイドは停止中"
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            dataStatus = "JSONを端末内へ取り込み中…"
            scope.launch {
                val result = runCatching {
                    withContext(Dispatchers.IO) {
                        val text = context.contentResolver.openInputStream(uri)
                            ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                            ?: throw IllegalArgumentException("ファイルを読み取れませんでした。")
                        database.importJson(text)
                    }
                }
                result.onSuccess { summary ->
                    refreshData()
                    dataStatus = "JSON取込成功：${summary.datasetName}（駅 ${summary.stationCount}・接続 ${summary.connectionCount}・時刻表 ${summary.timetableCount} 件）"
                }.onFailure { error ->
                    dataStatus = "JSON取込失敗：${error.message ?: "ファイル形式を確認してください"}"
                }
            }
        }
    }

    val gtfsImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            dataStatus = "GTFS ZIPを検証・取り込み中…"
            scope.launch {
                val result = runCatching {
                    withContext(Dispatchers.IO) { GtfsImporter.importZip(context, uri, database) }
                }
                result.onSuccess { summary ->
                    refreshData()
                    dataStatus = "GTFS取込成功：${summary.datasetName}（駅 ${summary.stationCount}・接続 ${summary.connectionCount}・時刻表 ${summary.timetableCount} 件）"
                }.onFailure { error ->
                    dataStatus = "GTFS取込失敗：${error.message ?: "ZIP内容を確認してください"}"
                }
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val result = runCatching {
                    withContext(Dispatchers.IO) {
                        val json = database.exportJson()
                        context.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { it.write(json) }
                            ?: throw IllegalArgumentException("保存先に書き込めませんでした。")
                    }
                }
                result.onSuccess { dataStatus = "JSONを書き出しました。" }
                    .onFailure { error -> dataStatus = "書き出し失敗：${error.message ?: "保存先を確認してください"}" }
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        gpsEnabled = fineGranted
        rideStarted = true
        rideStep = 0
        selectedTab = 2
        gpsStatus = if (fineGranted) "GPS待機中" else "位置情報なし・手動ガイド"
    }

    fun startRide() {
        val route = searchResult
        if (route == null || route.stations.size < 2) {
            gpsStatus = "先に経路検索をしてください。"
            selectedTab = 0
            return
        }
        if (datasetInfo.isDemo) {
            gpsEnabled = false
            rideStarted = true
            rideStep = 0
            gpsStatus = "デモ座標でのGPS自動推定は無効です。手動ガイドを利用できます。"
            selectedTab = 2
            return
        }
        val fineGranted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (fineGranted) {
            gpsEnabled = true
            rideStarted = true
            rideStep = 0
            gpsStatus = "GPS待機中"
            selectedTab = 2
        } else {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    val route = searchResult
    val trackingActive = rideStarted && selectedTab == 2 && gpsEnabled && !datasetInfo.isDemo &&
        route != null && rideStep < route.stations.lastIndex

    @SuppressLint("MissingPermission")
    DisposableEffect(trackingActive, route, rideStep) {
        if (trackingActive && route != null) {
            val target = route.stations.getOrNull(rideStep + 1)
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            var closeFixCount = 0
            var alreadyAdvanced = false
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    gpsStatus = "GPS測位中（精度 約${location.accuracy.toInt()}m）"
                    if (target?.latitude == null || target.longitude == null) return
                    if (location.accuracy > 80f || alreadyAdvanced) return
                    val distance = FloatArray(1)
                    Location.distanceBetween(
                        location.latitude, location.longitude,
                        target.latitude, target.longitude,
                        distance
                    )
                    if (distance[0] <= 250f) {
                        closeFixCount += 1
                        if (closeFixCount >= 2) {
                            alreadyAdvanced = true
                            rideStep = (rideStep + 1).coerceAtMost(route.stations.lastIndex)
                            gpsStatus = "次の駅付近を検知：${target.name}"
                            vibrate(context)
                        }
                    } else {
                        closeFixCount = 0
                    }
                }

                override fun onProviderEnabled(provider: String) {
                    gpsStatus = "GPSを使用できます。"
                }

                override fun onProviderDisabled(provider: String) {
                    gpsStatus = "GPSを利用できません。手動で次の駅へ進めます。"
                }

                @Deprecated("Deprecated in Android")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
            }

            try {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    2500L,
                    5f,
                    listener,
                    Looper.getMainLooper()
                )
                gpsStatus = if (target?.latitude == null || target.longitude == null) {
                    "この経路には位置座標がありません。手動ガイドを利用してください。"
                } else {
                    "GPS待機中：次の駅は ${target.name}"
                }
            } catch (_: SecurityException) {
                gpsEnabled = false
                gpsStatus = "位置情報の許可がありません。手動ガイドを利用してください。"
            } catch (_: IllegalArgumentException) {
                gpsStatus = "端末のGPSプロバイダーが利用できません。手動ガイドを利用してください。"
            }

            onDispose {
                runCatching { locationManager.removeUpdates(listener) }
            }
        } else {
            onDispose { }
        }
    }

    val motionTrackingActive = rideStarted && selectedTab == 2 && route != null && rideStep < route.stations.lastIndex
    DisposableEffect(motionTrackingActive) {
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = manager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.values.size >= 3) {
                    val magnitude = sqrt(
                        (event.values[0] * event.values[0] +
                            event.values[1] * event.values[1] +
                            event.values[2] * event.values[2]).toDouble()
                    )
                    motionDetected = magnitude > 0.75
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        if (motionTrackingActive && sensor != null) {
            manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        } else {
            motionDetected = false
        }
        onDispose { manager.unregisterListener(listener) }
    }

    val tabs = listOf("経路", "時刻表", "乗車中", "データ")
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("NidaMAP", fontWeight = FontWeight.Bold, color = Ink)
                        Text(
                            if (datasetInfo.isDemo) "大阪エリア · デモデータ" else "オフライン鉄道ナビ",
                            style = MaterialTheme.typography.labelSmall,
                            color = Muted
                        )
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar(containerColor = Color.White) {
                tabs.forEachIndexed { index, label ->
                    NavigationBarItem(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        icon = { Text(listOf("⇄", "◷", "◎", "▤")[index]) },
                        label = { Text(label) }
                    )
                }
            }
        },
        containerColor = PageBackground
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            when (selectedTab) {
                0 -> RouteSearchScreen(
                    stations = stations,
                    result = searchResult,
                    datasetInfo = datasetInfo,
                    onSearch = { startId, goalId ->
                        val found = RoutePlanner.findRoute(
                            stations,
                            database.getConnections(),
                            startId,
                            goalId
                        )
                        searchResult = found
                        if (found == null) {
                            gpsStatus = "経路が見つかりません。駅同士の接続データを確認してください。"
                        } else {
                            rideStep = 0
                        }
                    },
                    onStartRide = { startRide() }
                )
                1 -> TimetableScreen(
                    stations = stations,
                    dataRevision = dataRevision,
                    loadTimetable = { database.getUpcomingTimetable(it, java.time.LocalDate.now(), LocalTime.now().toSecondOfDay()) },
                    datasetInfo = datasetInfo
                )
                2 -> RideScreen(
                    route = searchResult,
                    rideStarted = rideStarted,
                    currentStep = rideStep,
                    gpsStatus = gpsStatus,
                    motionDetected = motionDetected,
                    onStart = { startRide() },
                    onNext = {
                        val max = searchResult?.stations?.lastIndex ?: 0
                        if (rideStep < max) {
                            rideStep += 1
                            vibrate(context)
                            gpsStatus = "手動で次の駅へ進みました。"
                        }
                    },
                    onPrevious = {
                        if (rideStep > 0) {
                            rideStep -= 1
                            gpsStatus = "前の駅へ戻しました。"
                        }
                    },
                    onStop = {
                        rideStarted = false
                        gpsEnabled = false
                        gpsStatus = "乗車ガイドを停止しました。"
                    }
                )
                else -> DataScreen(
                    info = datasetInfo,
                    message = dataStatus,
                    confirmReset = confirmReset,
                    onImport = { importLauncher.launch(arrayOf("application/json", "text/json", "text/plain")) },
                    onImportGtfs = { gtfsImportLauncher.launch(arrayOf("*/*")) },
                    onExport = { exportLauncher.launch("nidamap-rail-data.json") },
                    onAskReset = { confirmReset = true },
                    onCancelReset = { confirmReset = false },
                    onReset = {
                        database.resetToDemoData()
                        refreshData()
                        confirmReset = false
                        dataStatus = "開発用デモデータに戻しました。"
                    }
                )
            }
            Text(
                if (datasetInfo.isDemo) "デモデータです。実際の移動に使用しないでください。" else "位置情報とデータはこの端末内で処理します。",
                color = Muted,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }
    }
}

@Composable
private fun RouteSearchScreen(
    stations: List<Station>,
    result: RouteResult?,
    datasetInfo: DatasetInfo,
    onSearch: (String, String) -> Unit,
    onStartRide: () -> Unit
) {
    var startId by remember(stations) {
        mutableStateOf(stations.firstOrNull { it.id == "osaka" }?.id ?: stations.firstOrNull()?.id.orEmpty())
    }
    var goalId by remember(stations) {
        mutableStateOf(stations.firstOrNull { it.id == "namba" }?.id ?: stations.getOrNull(1)?.id.orEmpty())
    }
    var error by remember { mutableStateOf<String?>(null) }

    Text("どこへ行きますか？", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Ink)
    Text("オフラインの駅データを使って経路を計算します。", color = Muted)
    if (datasetInfo.isDemo) {
        InfoCard("開発用データ", "路線接続・所要時間は実運行用ではありません。駅検索、経路計算、乗車ガイドの動作確認用です。")
    }
    Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StationPicker("出発駅", stations, startId) { startId = it; error = null }
            StationPicker("到着駅", stations, goalId) { goalId = it; error = null }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        val oldStart = startId
                        startId = goalId
                        goalId = oldStart
                        error = null
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("出発・到着を入替") }
                Button(
                    onClick = {
                        if (startId.isBlank() || goalId.isBlank()) {
                            error = "候補から出発駅と到着駅を選んでください。"
                        } else {
                            onSearch(startId, goalId)
                            error = if (startId == goalId) "出発駅と到着駅が同じです。" else null
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("経路を検索") }
            }
            error?.let { Text(it, color = Color(0xFFB3261E)) }
        }
    }

    if (result != null) {
        Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
            Column(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("経路結果", color = NidaGreen, fontWeight = FontWeight.Bold)
                Text(
                    "約 ${result.totalMinutes} 分",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = Ink
                )
                Text(
                    "乗換 ${result.transfers} 回 · 区間 ${result.legs.size} · 乗車 ${result.trainMinutes} 分 + 乗換見積 ${result.transferMinutes} 分",
                    color = Muted,
                    style = MaterialTheme.typography.bodySmall
                )
                result.stations.forEachIndexed { index, station ->
                    Row(Modifier.fillMaxWidth()) {
                        Column(Modifier.width(26.dp), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                            Text(if (index == 0) "●" else if (index == result.stations.lastIndex) "◆" else "│", color = NidaGreen)
                            if (index != result.stations.lastIndex) Spacer(Modifier.height(14.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(station.name, color = Ink, fontWeight = FontWeight.SemiBold)
                            if (index < result.legs.size) {
                                val leg = result.legs[index]
                                Text("${leg.lineName} · ${leg.minutes} 分", color = Muted, style = MaterialTheme.typography.bodySmall)
                                if (leg.transferMinutes > 0) {
                                    Text("乗換時間の見積 +${leg.transferMinutes} 分", color = Muted, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
                Button(onClick = onStartRide, modifier = Modifier.fillMaxWidth()) {
                    Text("この経路で乗車ガイドを開始")
                }
                Text("所要時間は接続データに基づく推定値です。実際の時刻表・運行状況ではありません。", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun StationPicker(
    label: String,
    stations: List<Station>,
    selectedStationId: String,
    onSelect: (String) -> Unit
) {
    var query by remember { mutableStateOf("") }
    LaunchedEffect(selectedStationId, stations) {
        query = stations.firstOrNull { it.id == selectedStationId }?.name.orEmpty()
    }
    val matches = remember(query, stations) {
        if (query.isBlank()) emptyList()
        else stations.filter { it.name.contains(query.trim(), ignoreCase = true) || it.id.contains(query.trim(), ignoreCase = true) }
            .take(5)
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(label) },
            singleLine = true,
            supportingText = {
                val selected = stations.firstOrNull { it.id == selectedStationId }
                Text(if (selected?.name == query) "選択中：${selected.name}" else "駅名を入力し、候補をタップ")
            }
        )
        if (query.isNotBlank() && stations.none { it.name == query && it.id == selectedStationId } && matches.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FBF9))
            ) {
                Column(Modifier.padding(4.dp)) {
                    matches.forEach { station ->
                        Text(
                            text = station.name,
                            modifier = Modifier.fillMaxWidth().clickable {
                                onSelect(station.id)
                                query = station.name
                            }.padding(horizontal = 12.dp, vertical = 10.dp),
                            color = Ink
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TimetableScreen(
    stations: List<Station>,
    dataRevision: Int,
    loadTimetable: (String) -> List<TimetableEntry>,
    datasetInfo: DatasetInfo
) {
    var stationId by remember(stations) {
        mutableStateOf(stations.firstOrNull { it.id == "osaka" }?.id ?: stations.firstOrNull()?.id.orEmpty())
    }
    val entries = remember(stationId, dataRevision) { loadTimetable(stationId) }
    PageHeading("時刻表", "今日の現在時刻以降に運行する、端末内の登録便を表示します。")
    StationPicker("駅を選択", stations, stationId) { stationId = it }
    if (datasetInfo.isDemo) {
        InfoCard("架空のテスト時刻", "表示される時刻は画面の動作確認用です。実際の列車時刻・運行状況ではありません。")
    }
    if (entries.isEmpty()) {
        InfoCard("この後の便がありません", "今日の残り時間に該当する便がないか、サービス日に当てはまる便がありません。GTFS ZIPまたはNidaMAP JSONを確認してください。")
    } else {
        val now = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm", Locale.JAPAN))
        val next = entries.firstOrNull { it.departureTime >= now }
        if (next != null) {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFE6F5ED))) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("次の登録列車", color = NidaGreen, fontWeight = FontWeight.Bold)
                    Text("${next.departureTime} 発  ${next.destination} 行", color = Ink, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                    Text("${next.lineName} · 到着予定 ${next.arrivalTime}", color = Muted)
                }
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                entries.forEach { entry ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 9.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(entry.departureTime, color = NidaGreen, fontWeight = FontWeight.Bold)
                        Column(Modifier.weight(1f)) {
                            Text("${entry.destination} 行", color = Ink, fontWeight = FontWeight.SemiBold)
                            Text("${entry.lineName} · 到着 ${entry.arrivalTime}", color = Muted, style = MaterialTheme.typography.bodySmall)
                            Text(entry.serviceDays, color = Muted, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RideScreen(
    route: RouteResult?,
    rideStarted: Boolean,
    currentStep: Int,
    gpsStatus: String,
    motionDetected: Boolean,
    onStart: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onStop: () -> Unit
) {
    PageHeading("乗車中ガイド", "GPSで次駅付近を検知。地下や測位できない区間では手動で進めます。")
    if (route == null || route.stations.size < 2) {
        InfoCard("経路が選択されていません", "［経路］で出発駅と到着駅を選び、経路を検索してください。")
        return
    }
    val boundedStep = currentStep.coerceIn(0, route.stations.lastIndex)
    val current = route.stations[boundedStep]
    val next = route.stations.getOrNull(boundedStep + 1)
    Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (rideStarted) "乗車ガイド稼働中" else "経路ガイド準備完了", color = NidaGreen, fontWeight = FontWeight.Bold)
            Text(current.name, color = Ink, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.headlineMedium)
            Text(if (next == null) "目的地に到着しました" else "次の駅：${next.name}", color = Muted, style = MaterialTheme.typography.titleMedium)
            Text("進捗 ${boundedStep + 1} / ${route.stations.size}", color = Muted)
            androidx.compose.material3.LinearProgressIndicator(
                progress = { (boundedStep + 1).toFloat() / route.stations.size.toFloat() },
                modifier = Modifier.fillMaxWidth(),
                color = NidaGreen,
                trackColor = RailLineColor
            )
            Text(gpsStatus, color = if (gpsStatus.contains("利用できません") || gpsStatus.contains("ありません")) Color(0xFF9A5B00) else Muted)
            Text(
                if (motionDetected) "端末の動きを検知中（列車判定ではありません）" else "加速度センサー：大きな動きなし",
                color = Muted,
                style = MaterialTheme.typography.bodySmall
            )
            if (rideStarted) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onPrevious, enabled = boundedStep > 0, modifier = Modifier.weight(1f)) {
                        Text("前の駅")
                    }
                    Button(onClick = onNext, enabled = next != null, modifier = Modifier.weight(1f)) {
                        Text(if (next == null) "到着済み" else "次の駅へ")
                    }
                }
                OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) { Text("ガイドを停止") }
            } else {
                Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) { Text("乗車ガイドを開始") }
                Text("位置情報は駅付近の検知にのみ使用し、外部へ送信しません。", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("この経路", fontWeight = FontWeight.Bold, color = Ink)
            route.stations.forEachIndexed { index, station ->
                Row(Modifier.fillMaxWidth()) {
                    Text(if (index < boundedStep) "✓" else if (index == boundedStep) "●" else "○", color = if (index <= boundedStep) NidaGreen else Muted, modifier = Modifier.width(28.dp))
                    Column {
                        Text(station.name, color = Ink, fontWeight = if (index == boundedStep) FontWeight.Bold else FontWeight.Normal)
                        route.legs.getOrNull(index)?.let { leg ->
                            Text("${leg.lineName} · 約 ${leg.minutes} 分", color = Muted, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
    InfoCard("地下区間について", "GPS精度が落ちた場合、自動で駅を確定できないことがあります。加速度センサーは端末の動きを補助的に表示しますが、列車や駅を単独で判定しません。誤案内を防ぐため、［次の駅へ］で手動修正できます。")
}

@Composable
private fun DataScreen(
    info: DatasetInfo,
    message: String,
    confirmReset: Boolean,
    onImport: () -> Unit,
    onImportGtfs: () -> Unit,
    onExport: () -> Unit,
    onAskReset: () -> Unit,
    onCancelReset: () -> Unit,
    onReset: () -> Unit
) {
    PageHeading("オフラインデータ", "路線・駅・時刻表は端末内のSQLiteに保存します。")
    Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("データセット", color = NidaGreen, fontWeight = FontWeight.Bold)
            Text(info.name, color = Ink, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("バージョン：${info.version}", color = Muted)
            Text("駅 ${info.stationCount} 件", color = Ink)
            Text("路線接続 ${info.connectionCount} 件", color = Ink)
            Text("時刻表 ${info.timetableCount} 件", color = Ink)
            Text("更新：${info.importedAt}", color = Muted, style = MaterialTheme.typography.bodySmall)
            Text(if (info.isDemo) "状態：開発用デモ" else "状態：インポート済み", color = if (info.isDemo) Color(0xFF9A5B00) else NidaGreen, fontWeight = FontWeight.SemiBold)
        }
    }
    Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("データの管理", color = Ink, fontWeight = FontWeight.Bold)
            Button(onClick = onImportGtfs, modifier = Modifier.fillMaxWidth()) { Text("GTFS ZIP（鉄道時刻表）を取り込む") }
            OutlinedButton(onClick = onImport, modifier = Modifier.fillMaxWidth()) { Text("NidaMAP JSONを取り込む") }
            OutlinedButton(onClick = onExport, modifier = Modifier.fillMaxWidth()) { Text("現在のデータを書き出す") }
            OutlinedButton(onClick = onAskReset, modifier = Modifier.fillMaxWidth()) { Text("開発用デモに戻す") }
            if (confirmReset) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF4E5)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("現在のデータを置き換えて、組み込みデモデータに戻しますか？", color = Ink)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = onCancelReset, modifier = Modifier.weight(1f)) { Text("キャンセル") }
                            Button(onClick = onReset, modifier = Modifier.weight(1f)) { Text("戻す") }
                        }
                    }
                }
            }
            Text(message, color = Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
    InfoCard("JSON形式", "format は nidamap-rail-data。stations に id・name と任意の latitude・longitude、connections に from・to・line・minutes、timetables に stationId・line・destination・departure・arrival・serviceDays を指定します。")
    InfoCard("通信と位置情報", "通常の経路検索と保存済み時刻表はオフラインで動作します。このアプリから位置情報や駅データをサーバーに送信しません。遅延・運休のリアルタイム情報は含みません。")
}

@Composable
private fun PageHeading(title: String, subtitle: String) {
    Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Ink)
    Text(subtitle, color = Muted)
}

@Composable
private fun InfoCard(title: String, body: String) {
    Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, color = NidaGreen, fontWeight = FontWeight.Bold)
            Text(body, color = Ink)
        }
    }
}

private fun vibrate(context: Context) {
    runCatching {
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        if (vibrator?.hasVibrator() == true) {
            vibrator.vibrate(VibrationEffect.createOneShot(110L, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }
}
