package cn.simmc.smcinfo

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        setContent { SMCApp(this, prefs.getBoolean("dark", false)) { prefs.edit().putBoolean("dark", it).apply() } }
    }

    fun requestLegacyStorage() {
        if (Build.VERSION.SDK_INT <= 28 && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
            permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    fun saveReports(reports: List<Report>) {
        if (Build.VERSION.SDK_INT >= 29) {
            reports.forEach { report ->
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, report.fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, report.mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/SMap_file")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: error("无法创建 ${report.fileName}")
                contentResolver.openOutputStream(uri)?.use { it.write(report.bytes) }
                    ?: error("无法写入 ${report.fileName}")
                values.clear(); values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                contentResolver.update(uri, values, null, null)
            }
        } else {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "SMap_file")
            dir.mkdirs(); reports.forEach { File(dir, it.fileName).writeBytes(it.bytes) }
        }
    }

    fun locateFiles() {
        val uri = Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload%2FSMap_file")
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, DocumentsContract.Document.MIME_TYPE_DIR)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { startActivity(view) }.onFailure {
            startActivity(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).putExtra(DocumentsContract.EXTRA_INITIAL_URI, uri))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SMCApp(activity: MainActivity, initialDark: Boolean, saveDark: (Boolean) -> Unit) {
    var dark by remember { mutableStateOf(initialDark) }
    var settings by remember { mutableStateOf(false) }
    var permissionInfo by remember { mutableStateOf(!activity.getSharedPreferences("settings", Activity.MODE_PRIVATE).getBoolean("storage_seen", false)) }
    val colors = if (dark) darkColorScheme() else lightColorScheme()
    MaterialTheme(colorScheme = colors) {
        if (permissionInfo) AlertDialog(
            onDismissRequest = {}, title = { Text("存储权限") },
            text = { Text("生成的文件将保存到内部存储/Download/SMap_file。Android 10及以上由系统分区存储管理；Android 9及以下需要存储权限。") },
            confirmButton = { TextButton(onClick = {
                activity.getSharedPreferences("settings", Activity.MODE_PRIVATE).edit().putBoolean("storage_seen", true).apply()
                permissionInfo = false; activity.requestLegacyStorage()
            }) { Text("允许并继续") } },
        )
        if (settings) SettingsDialog(dark, { dark = it; saveDark(it) }, { settings = false })
        Scaffold(topBar = {
            TopAppBar(
                title = { Column { Text("SMC信息生成器", fontWeight = FontWeight.SemiBold); Text("地图数据排行与报表", style = MaterialTheme.typography.labelMedium) } },
                actions = { IconButton(onClick = { settings = true }) { Icon(Icons.Outlined.Settings, "设置") } },
            )
        }) { padding -> MainScreen(activity, Modifier.padding(padding)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(activity: MainActivity, modifier: Modifier = Modifier) {
    var tasks by remember { mutableStateOf(TaskSelection()) }
    var format by remember { mutableStateOf(OutputFormat.HTML) }
    var expanded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var hasResults by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("选择内容后点击“开始生成”。") }
    val scope = rememberCoroutineScope()
    Column(modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("生成内容", style = MaterialTheme.typography.titleMedium)
        Option("国家首都排行 + 国家总体排行", tasks.gdp) { tasks = tasks.copy(gdp = it) }
        Option("全部领地坐标及区块", tasks.territories) { tasks = tasks.copy(territories = it) }
        Option("国家所有领地区块排行", tasks.chunks) { tasks = tasks.copy(chunks = it) }
        Option("国家首都坐标表", tasks.capitals) { tasks = tasks.copy(capitals = it) }
        Option("港口和驿站坐标及归属", tasks.gateways) { tasks = tasks.copy(gateways = it) }
        Option("国家与独立领地人口排行", tasks.population) { tasks = tasks.copy(population = it) }
        ExposedDropdownMenuBox(expanded, { expanded = !expanded }) {
            OutlinedTextField(format.name, {}, readOnly = true, label = { Text("保存格式") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) }, modifier = Modifier.menuAnchor().fillMaxWidth())
            ExposedDropdownMenu(expanded, { expanded = false }) { OutputFormat.entries.forEach { DropdownMenuItem({ Text(it.name) }, { format = it; expanded = false }) } }
        }
        Card(Modifier.fillMaxWidth()) { Text(status, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium) }
        Button(
            onClick = {
                if (!listOf(tasks.gdp, tasks.territories, tasks.chunks, tasks.capitals, tasks.gateways, tasks.population).any { it }) { status = "请至少选择一种输出内容。"; return@Button }
                busy = true; hasResults = false; status = "正在下载并处理地图数据……"
                scope.launch {
                    runCatching { withContext(Dispatchers.IO) { val map = SimmcData.downloadAndParse(); val reports = Reports.generate(map, tasks, format); activity.saveReports(reports); map to reports.size } }
                        .onSuccess { (map, count) -> status = "生成完成：$count 个 ${format.name} 文件。\n解析 ${map.lands.size} 块领地。\n保存到 Download/SMap_file"; hasResults = true }
                        .onFailure { status = "生成失败：${it.message}" }
                    busy = false
                }
            }, enabled = !busy, modifier = Modifier.fillMaxWidth().height(50.dp),
        ) { Text(if (busy) "正在生成…" else "开始生成") }
        OutlinedButton(onClick = activity::locateFiles, enabled = hasResults, modifier = Modifier.fillMaxWidth().height(50.dp)) { Icon(Icons.Outlined.FolderOpen, null); Spacer(Modifier.width(8.dp)); Text("定位文件") }
    }
}

@Composable private fun Option(text: String, checked: Boolean, changed: (Boolean) -> Unit) {
    Card(onClick = { changed(!checked) }, modifier = Modifier.fillMaxWidth()) { Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) { Text(text, Modifier.weight(1f)); Checkbox(checked, changed) } }
}

@Composable private fun SettingsDialog(dark: Boolean, setDark: (Boolean) -> Unit, close: () -> Unit) {
    AlertDialog(onDismissRequest = close, title = { Text("设置") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Text("暗色模式", Modifier.weight(1f)); Switch(dark, setDark) }
            HorizontalDivider(); Text("关于", style = MaterialTheme.typography.titleMedium)
            Text("SMC信息生成器\n版本 1.0.0\n\n从 Simmc 网页地图获取领地数据，生成 GDP、区块、人口和坐标报表。")
        }
    }, confirmButton = { TextButton(onClick = close) { Text("完成") } })
}
