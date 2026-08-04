package cn.simmc.smcinfo

import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.text.DecimalFormat
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

enum class OutputFormat { HTML, TXT, XLSX }
data class TaskSelection(
    val gdp: Boolean = true, val territories: Boolean = true, val chunks: Boolean = true,
    val capitals: Boolean = true, val gateways: Boolean = true, val population: Boolean = true,
)
data class Report(val fileName: String, val mimeType: String, val bytes: ByteArray)

object Reports {
    private const val HEADER = "该排名是从上到下降序排序，排行数字越小，代表排名越高"
    private val money = DecimalFormat("#,##0.00")
    private fun row(vararg values: Any?) = values.joinToString("\t")

    fun generate(map: ParsedMap, tasks: TaskSelection, format: OutputFormat): List<Report> {
        val texts = linkedMapOf<String, String>()
        val lands = map.lands
        val grouped = lands.filter { it.nation != null }.groupBy { it.nation!! }
        if (tasks.gdp) {
            val capitals = grouped.map { (nation, members) ->
                val capital = members.mapNotNull { it.capital }.sorted().firstOrNull() ?: "未知"
                val capitalLands = members.filter { it.name == capital }
                listOf(nation, capital, capitalLands.sumOf { it.chunks }, capitalLands.sumMoney())
            }.sortedByDescending { it[3] as BigDecimal }
            texts["国家首都排行"] = buildString {
                appendLine(HEADER); appendLine(); appendLine(row("国家名称", "国家首都", "首都区块", "首都存款", "排名次"))
                capitals.forEachIndexed { i, r -> appendLine(row(r[0], r[1], r[2], money.format(r[3]), "第${i + 1}名")) }
            }
            val nations = grouped.map { (nation, members) ->
                val capital = members.firstNotNullOfOrNull { it.capital } ?: "未知"
                val richest = members.maxOf { it.balance }
                listOf(nation, capital, members.filter { it.balance == richest }.map { it.name }.distinct().sorted().joinToString("、"), members.sumMoney())
            }.sortedByDescending { it[3] as BigDecimal }
            texts["国家总体排行"] = buildString {
                appendLine(HEADER); appendLine(); appendLine(row("国家名", "首都名", "国家最富有领土", "总存款", "排名次"))
                nations.forEachIndexed { i, r -> appendLine(row(r[0], r[1], r[2], money.format(r[3]), "第${i + 1}名")) }
            }
            texts["全世界领地GDP排行"] = buildString {
                appendLine(HEADER); appendLine(); appendLine(row("领地名称", "所属国家", "领地类型", "领地存款", "排名次"))
                lands.sortedByDescending { it.balance }.forEachIndexed { i, land ->
                    appendLine(row(land.name, land.nation ?: "未加入国家", land.kind(), money.format(land.balance), "第${i + 1}名"))
                }
            }
        }
        if (tasks.territories) texts["全部领地坐标及区块"] = buildString {
            appendLine("坐标为领地范围中心点，区块数取自地图领地资料"); appendLine()
            appendLine(row("国家", "领地类型", "领地名称", "中心坐标", "所占区块", "存款"))
            lands.sortedWith(compareBy<Land>({ it.nation == null }, { it.nation ?: "" }, { it.name != it.capital }, { it.name })).forEach {
                appendLine(row(it.nation ?: "未加入国家", it.kind(), it.name, "X=${it.x}, Z=${it.z}", it.chunks, money.format(it.balance)))
            }
        }
        if (tasks.chunks) texts["国家所有领地区块排行"] = buildString {
            appendLine(HEADER); appendLine(); appendLine(row("国家名", "首都名", "领地数量", "总区块数", "排名次"))
            grouped.map { (n, m) -> listOf(n, m.firstNotNullOfOrNull { it.capital } ?: "未知", m.size, m.sumOf { it.chunks }) }
                .sortedByDescending { it[3] as Int }.forEachIndexed { i, r -> appendLine(row(r[0], r[1], r[2], r[3], "第${i + 1}名")) }
        }
        if (tasks.capitals) texts["国家首都坐标表"] = buildString {
            appendLine("坐标为首都领地范围中心点"); appendLine(); appendLine(row("国家名", "首都名", "中心坐标", "首都区块数"))
            grouped.toSortedMap().forEach { (nation, members) ->
                val capital = members.firstNotNullOfOrNull { it.capital } ?: "未知"
                val found = members.filter { it.name == capital }
                appendLine(row(nation, capital, if (found.isEmpty()) "地图领地图层中未找到" else found.joinToString("、") { "X=${it.x}, Z=${it.z}" }, found.sumOf { it.chunks }))
            }
        }
        if (tasks.gateways) texts["港口和驿站坐标"] = gatewayText(map)
        if (tasks.population) texts["国家及独立领地玩家总数排行"] = buildString {
            appendLine(HEADER); appendLine(); appendLine(row("类型", "国家或独立领地名称", "玩家总数", "排名次"))
            val rows = grouped.map { (n, m) -> Triple("国家", n, m.flatMap { it.players }.toSet().size) } +
                lands.filter { it.nation == null }.map { Triple("独立领地", it.name, it.players.toSet().size) }
            rows.sortedByDescending { it.third }.forEachIndexed { i, r -> appendLine(row(r.first, r.second, r.third, "第${i + 1}名")) }
        }
        return texts.map { (name, text) -> convert(name, text, format) }
    }

    private fun gatewayText(map: ParsedMap): String {
        val layer = (0 until map.root.length()).map { map.root.getJSONObject(it) }.firstOrNull { it.optString("id") == "transport_gateways" }
            ?: error("找不到地图图层：transport_gateways")
        val markers = layer.optJSONArray("markers") ?: return ""
        val rows = mutableListOf<List<Any>>()
        for (i in 0 until markers.length()) {
            val marker = markers.getJSONObject(i); val p = marker.optJSONObject("point") ?: continue
            if (!p.has("x") || !p.has("z")) continue
            val x = p.optDouble("x"); val z = p.optDouble("z"); val land = findLand(x, z, map.lands)
            rows += listOf(land?.nation ?: "未加入国家", land?.name ?: "未匹配领地", SimmcData.clean(marker.optString("tooltip", marker.optString("popup", "未命名"))), x.toInt(), z.toInt())
        }
        return buildString {
            appendLine("归属通过港口/驿站坐标落入领地多边形进行匹配"); appendLine(); appendLine(row("所属国家", "所属领地", "名称", "坐标点"))
            rows.sortedBy { "${it[0]}${it[1]}${it[2]}" }.forEach { appendLine(row(it[0], it[1], it[2], "X=${it[3]}, Z=${it[4]}")) }
        }
    }

    private fun findLand(x: Double, z: Double, lands: List<Land>) = lands.filter { l -> l.polygons.any { pointInPolygon(x, z, it) } }
        .minByOrNull { l -> l.polygons.flatten().let { p -> if (p.isEmpty()) Double.MAX_VALUE else (p.maxOf { it.x } - p.minOf { it.x }) * (p.maxOf { it.z } - p.minOf { it.z }) } }

    private fun pointInPolygon(x: Double, z: Double, polygon: List<Point>): Boolean {
        var inside = false; var j = polygon.lastIndex
        polygon.forEachIndexed { i, p -> val q = polygon[j]; if ((p.z > z) != (q.z > z) && x < (q.x - p.x) * (z - p.z) / (q.z - p.z) + p.x) inside = !inside; j = i }
        return inside
    }

    private fun convert(name: String, text: String, format: OutputFormat): Report = when (format) {
        OutputFormat.TXT -> Report("$name.txt", "text/plain", byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + text.toByteArray())
        OutputFormat.HTML -> Report("$name.html", "text/html", html(name, text).toByteArray())
        OutputFormat.XLSX -> Report("$name.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", Xlsx.create(text.lines().map { if (it.isEmpty()) emptyList() else it.split('\t') }))
    }
    private fun html(name: String, text: String) = """<!doctype html><html lang="zh-CN"><meta charset="utf-8"><title>${esc(name)}</title><style>body{font-family:sans-serif;margin:24px}table{border-collapse:collapse;width:100%}td{border:1px solid #bbb;padding:6px}tr:first-child{font-weight:bold;background:#eee}</style><h1>${esc(name)}</h1><table>${text.lines().joinToString("") { "<tr>" + it.split('\t').joinToString("") { c -> "<td>${esc(c)}</td>" } + "</tr>" }}</table></html>"""
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    private fun List<Land>.sumMoney() = fold(BigDecimal.ZERO) { a, l -> a + l.balance }
    private fun Land.kind() = if (nation == null) "独立领地" else if (name == capital) "首都" else "附属领地"
}

private object Xlsx {
    fun create(rows: List<List<String>>): ByteArray {
        val out = ByteArrayOutputStream(); ZipOutputStream(out).use { zip ->
            fun add(path: String, value: String) { zip.putNextEntry(ZipEntry(path)); zip.write(value.toByteArray()); zip.closeEntry() }
            add("[Content_Types].xml", """<?xml version="1.0"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>""")
            add("_rels/.rels", """<?xml version="1.0"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""")
            add("xl/workbook.xml", """<?xml version="1.0"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="数据" sheetId="1" r:id="rId1"/></sheets></workbook>""")
            add("xl/_rels/workbook.xml.rels", """<?xml version="1.0"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>""")
            val data = rows.mapIndexed { ri, row -> "<row r=\"${ri + 1}\">" + row.mapIndexed { ci, v -> "<c r=\"${column(ci)}${ri + 1}\" t=\"inlineStr\"><is><t>${xml(v)}</t></is></c>" }.joinToString("") + "</row>" }.joinToString("")
            add("xl/worksheets/sheet1.xml", """<?xml version="1.0" encoding="UTF-8"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>$data</sheetData></worksheet>""")
        }; return out.toByteArray()
    }
    private fun column(i: Int): String { var n = i + 1; var s = ""; while (n > 0) { s = ('A'.code + (n - 1) % 26).toChar() + s; n = (n - 1) / 26 }; return s }
    private fun xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
