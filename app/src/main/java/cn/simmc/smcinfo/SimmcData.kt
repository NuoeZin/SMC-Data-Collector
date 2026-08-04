package cn.simmc.smcinfo

import android.text.Html
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.net.HttpURLConnection
import java.net.URL

data class Point(val x: Double, val z: Double)
data class Land(
    val name: String,
    val balance: BigDecimal,
    val chunks: Int,
    val x: Int,
    val z: Int,
    val nation: String?,
    val capital: String?,
    val players: List<String>,
    val playerCount: Int,
    val polygons: List<List<Point>>,
)

data class ParsedMap(val root: JSONArray, val lands: List<Land>, val skipped: Int)

object SimmcData {
    const val DEFAULT_URL = "https://map.simmc.cn/tiles/minecraft_overworld/markers.json"
    private const val LAND_LAYER = "lands_world"

    private val nameRe = Regex("font-size:\\s*200%;.*?>(.*?)</span>", setOf(RegexOption.DOT_MATCHES_ALL))
    private val balanceRe = Regex("余额:\\s*\\$([\\d,]+(?:\\.\\d+)?)")
    private val chunksRe = Regex("区块:\\s*(\\d+)")
    private val playersRe = Regex("玩家\\((\\d+)\\):\\s*(.*?)</li>", setOf(RegexOption.DOT_MATCHES_ALL))
    private val nationRe = Regex("这块地属于国家\\s*(.*?):</strong>", setOf(RegexOption.DOT_MATCHES_ALL))
    private val capitalRe = Regex("<li>首都:(.*?)</li>", setOf(RegexOption.DOT_MATCHES_ALL))

    fun downloadAndParse(): ParsedMap {
        val connection = URL(DEFAULT_URL).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 120_000
        connection.setRequestProperty("User-Agent", "smc-info-generator/1.0")
        connection.inputStream.bufferedReader(Charsets.UTF_8).use {
            return parse(JSONArray(it.readText()))
        }
    }

    fun parse(root: JSONArray): ParsedMap {
        val layer = (0 until root.length()).map { root.getJSONObject(it) }
            .firstOrNull { it.optString("id") == LAND_LAYER }
            ?: error("找不到地图图层：$LAND_LAYER")
        val markers = layer.optJSONArray("markers") ?: JSONArray()
        val lands = mutableListOf<Land>()
        var skipped = 0
        for (i in 0 until markers.length()) {
            val marker = markers.getJSONObject(i)
            val popup = Html.fromHtml(
                marker.optString("popup", marker.optString("tooltip", "")),
                Html.FROM_HTML_MODE_LEGACY,
            ).toString().let { marker.optString("popup", marker.optString("tooltip", "")) }
            val name = nameRe.find(popup)?.groupValues?.get(1)
            val balance = balanceRe.find(popup)?.groupValues?.get(1)?.replace(",", "")?.toBigDecimalOrNull()
            val chunks = chunksRe.find(popup)?.groupValues?.get(1)?.toIntOrNull()
            if (name == null || balance == null || chunks == null) { skipped++; continue }
            val playerMatch = playersRe.find(popup)
            val players = playerMatch?.groupValues?.get(2)?.split(",")
                ?.map(::clean)?.filter { it.isNotBlank() && it !in setOf("...", "…") } ?: emptyList()
            val center = markerCenter(marker)
            lands += Land(
                clean(name), balance, chunks, center.first, center.second,
                nationRe.find(popup)?.groupValues?.get(1)?.let(::clean),
                capitalRe.find(popup)?.groupValues?.get(1)?.let(::clean),
                players, playerMatch?.groupValues?.get(1)?.toIntOrNull() ?: players.size,
                markerPolygons(marker),
            )
        }
        return ParsedMap(root, lands, skipped)
    }

    fun clean(value: String): String = Html.fromHtml(value, Html.FROM_HTML_MODE_LEGACY)
        .toString().trim().replace(Regex("\\s+"), " ")

    private fun markerCenter(marker: JSONObject): Pair<Int, Int> {
        val points = mutableListOf<Point>()
        collectPoints(marker.opt("points"), points)
        if (points.isEmpty()) collectPoints(marker.opt("point") ?: marker, points)
        if (points.isEmpty()) return 0 to 0
        return ((points.minOf { it.x } + points.maxOf { it.x }) / 2).toInt() to
            ((points.minOf { it.z } + points.maxOf { it.z }) / 2).toInt()
    }

    private fun collectPoints(value: Any?, out: MutableList<Point>) {
        when (value) {
            is JSONObject -> if (value.has("x") && value.has("z"))
                out += Point(value.optDouble("x"), value.optDouble("z"))
            is JSONArray -> for (i in 0 until value.length()) collectPoints(value.opt(i), out)
        }
    }

    private fun markerPolygons(marker: JSONObject): List<List<Point>> {
        val result = mutableListOf<List<Point>>()
        fun visit(value: Any?) {
            if (value is JSONArray) {
                val isPolygon = value.length() > 0 && (0 until value.length()).all {
                    value.opt(it) is JSONObject && (value.opt(it) as JSONObject).has("x") && (value.opt(it) as JSONObject).has("z")
                }
                if (isPolygon) result += (0 until value.length()).map {
                    val p = value.getJSONObject(it); Point(p.optDouble("x"), p.optDouble("z"))
                } else for (i in 0 until value.length()) visit(value.opt(i))
            }
        }
        visit(marker.opt("points"))
        return result
    }
}
