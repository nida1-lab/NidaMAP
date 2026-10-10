package com.nida.nidamap

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private val NidaGreen = Color(0xFF00A86B)
private val Ink = Color(0xFF17211D)
private val Muted = Color(0xFF66736D)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFFF7F9F8)) {
                    NidaMapApp()
                }
            }
        }
    }
}

private data class RailEdge(val from: String, val to: String, val minutes: Int, val line: String)
private data class RouteResult(val stations: List<String>, val minutes: Int, val lines: List<String>)

/* Development-only sample graph, not official railway data. */
private val sampleEdges = listOf(
    RailEdge("大阪", "梅田", 4, "徒歩連絡"),
    RailEdge("梅田", "なんば", 9, "Osaka Metro 御堂筋線"),
    RailEdge("梅田", "淀屋橋", 3, "Osaka Metro 御堂筋線"),
    RailEdge("淀屋橋", "本町", 2, "Osaka Metro 御堂筋線"),
    RailEdge("本町", "心斎橋", 2, "Osaka Metro 御堂筋線"),
    RailEdge("心斎橋", "なんば", 2, "Osaka Metro 御堂筋線"),
    RailEdge("大阪", "新大阪", 4, "JR京都線"),
    RailEdge("新大阪", "千里中央", 13, "北大阪急行"),
    RailEdge("なんば", "天王寺", 6, "Osaka Metro 御堂筋線"),
    RailEdge("大阪", "京橋", 7, "JR大阪環状線"),
    RailEdge("京橋", "天王寺", 12, "JR大阪環状線")
)
private val sampleStations = listOf("大阪", "梅田", "新大阪", "淀屋橋", "本町", "心斎橋", "なんば", "天王寺", "京橋", "千里中央")

private fun findSampleRoute(start: String, goal: String): RouteResult? {
    if (start == goal) return RouteResult(listOf(start), 0, emptyList())
    val distances = mutableMapOf(start to 0)
    val previous = mutableMapOf<String, Pair<String, RailEdge>>()
    val unvisited = sampleStations.toMutableSet().apply { add(start); add(goal) }
    while (unvisited.isNotEmpty()) {
        val current = unvisited.minByOrNull { distances[it] ?: Int.MAX_VALUE } ?: break
        val currentDistance = distances[current] ?: Int.MAX_VALUE
        if (currentDistance == Int.MAX_VALUE) break
        unvisited.remove(current)
        if (current == goal) break
        sampleEdges.forEach { edge ->
            val next = when (current) {
                edge.from -> edge.to
                edge.to -> edge.from
                else -> null
            } ?: return@forEach
            val candidate = currentDistance + edge.minutes
            if (candidate < (distances[next] ?: Int.MAX_VALUE)) {
                distances[next] = candidate
                previous[next] = current to edge
                unvisited.add(next)
            }
        }
    }
    val total = distances[goal] ?: return null
    val path = mutableListOf(goal)
    val lines = mutableListOf<String>()
    var cursor = goal
    while (cursor != start) {
        val step = previous[cursor] ?: return null
        lines.add(step.second.line)
        cursor = step.first
        path.add(cursor)
    }
    path.reverse()
    lines.reverse()
    return RouteResult(path, total, lines)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NidaMapApp() {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("経路検索", "時刻表", "乗車中", "データ")
    Scaffold(
        topBar = {
            TopAppBar(title = {
                Column {
                    Text("NidaMAP", fontWeight = FontWeight.Bold, color = Ink)
                    Text("大阪エリア・オフライン試作", style = MaterialTheme.typography.labelSmall, color = Muted)
                }
            })
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
        containerColor = Color(0xFFF7F9F8)
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            when (selectedTab) {
                0 -> RouteSearchScreen()
                1 -> TimetableScreen()
                2 -> RideScreen()
                else -> DataScreen()
            }
            Text("開発版 · 表示される駅・所要時間はサンプルデータです", color = Muted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(vertical = 8.dp))
        }
    }
}

@Composable
private fun RouteSearchScreen() {
    var start by remember { mutableStateOf("大阪") }
    var goal by remember { mutableStateOf("なんば") }
    var result by remember { mutableStateOf(findSampleRoute("大阪", "なんば")) }
    var error by remember { mutableStateOf<String?>(null) }
    Text("どこへ行きますか？", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Ink)
    Text("通信なしで使える経路検索の土台です。駅名は直接入力できます。", color = Muted)
    Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(value = start, onValueChange = { start = it; error = null }, modifier = Modifier.fillMaxWidth(), label = { Text("出発駅") }, singleLine = true)
            OutlinedTextField(value = goal, onValueChange = { goal = it; error = null }, modifier = Modifier.fillMaxWidth(), label = { Text("到着駅") }, singleLine = true)
            Button(onClick = {
                result = findSampleRoute(start.trim(), goal.trim())
                error = if (result == null) "サンプル経路が見つかりません。駅名を確認してください。" else null
            }, modifier = Modifier.fillMaxWidth()) { Text("経路を検索") }
            error?.let { Text(it, color = Color(0xFFB3261E)) }
        }
    }
    result?.let { route ->
        Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("検索結果（サンプル）", color = NidaGreen, fontWeight = FontWeight.Bold)
                Text("${route.minutes} 分（乗換時間などは未反映）", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Ink)
                route.stations.forEachIndexed { index, station ->
                    Row(Modifier.fillMaxWidth()) {
                        Text(if (index == 0) "●" else if (index == route.stations.lastIndex) "◆" else "│", color = NidaGreen, modifier = Modifier.width(28.dp))
                        Column {
                            Text(station, fontWeight = FontWeight.SemiBold, color = Ink)
                            if (index < route.stations.lastIndex) Text(route.lines.getOrNull(index) ?: "接続情報なし", color = Muted, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Text("※実際の時刻表・公式経路ではありません。", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun TimetableScreen() {
    PageHeading("時刻表", "オフライン時刻表データを取り込む画面")
    InfoCard("準備中", "現時点では公式時刻表を搭載していません。GTFS等の利用可能なデータと再配布条件を確認してから実装します。")
}

@Composable
private fun RideScreen() {
    PageHeading("乗車中ガイド", "次の駅を推定する機能の土台")
    InfoCard("乗車追跡は未実装", "今後、選択した経路・端末センサー・位置情報を端末内で組み合わせます。地下などで推定が不確かな場合は明示し、手動修正も用意します。")
    InfoCard("通知", "駅接近時の画面通知とバイブレーションは今後追加します。音声案内は行いません。")
}

@Composable
private fun DataScreen() {
    PageHeading("オフラインデータ", "データを端末内で管理する方針です")
    InfoCard("データ状態", "現在はアプリ内の小さなサンプル駅データのみです。公式時刻表や運行情報は含まれていません。")
    InfoCard("今後の更新方法", "手動インポート・データ版表示・更新後も通信なしで利用できる仕組みを実装予定です。")
    InfoCard("プライバシー", "この試作では位置情報を取得・送信していません。")
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
