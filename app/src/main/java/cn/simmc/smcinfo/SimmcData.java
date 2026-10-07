package cn.simmc.smcinfo;

import android.text.Html;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SimmcData {
    public static final String DEFAULT_URL = "https://map.simmc.cn/tiles/minecraft_overworld/markers.json";
    private static final String LAND_LAYER = "lands_world";

    private static final Pattern NAME_RE = Pattern.compile("font-size:\\s*200%;.*?>(.*?)</span>", Pattern.DOTALL);
    private static final Pattern BALANCE_RE = Pattern.compile("余额:\\s*\\$([\\d,]+(?:\\.\\d+)?)");
    private static final Pattern CHUNKS_RE = Pattern.compile("区块:\\s*(\\d+)");
    private static final Pattern PLAYERS_RE = Pattern.compile("玩家\\((\\d+)\\):\\s*(.*?)</li>", Pattern.DOTALL);
    private static final Pattern NATION_RE = Pattern.compile("这块地属于国家\\s*(.*?):</strong>", Pattern.DOTALL);
    private static final Pattern CAPITAL_RE = Pattern.compile("<li>首都:(.*?)</li>", Pattern.DOTALL);

    public static final class Point {
        public final double x, z;
        public Point(double x, double z) { this.x = x; this.z = z; }
    }

    public static final class Land {
        public String name;
        public BigDecimal balance;
        public int chunks, x, z;
        public String nation, capital;
        public List<String> players = new ArrayList<String>();
        public int playerCount;
        public List<List<Point>> polygons = new ArrayList<List<Point>>();
    }

    public static final class ParsedMap {
        public final JSONArray root;
        public final List<Land> lands;
        public final int skipped;
        public ParsedMap(JSONArray root, List<Land> lands, int skipped) { this.root=root; this.lands=lands; this.skipped=skipped; }
    }

    public interface ProgressListener {
        void onProgress(String message, int color);
    }

    private SimmcData() {}

    public static ParsedMap downloadAndParse(File cacheFile) throws Exception {
        return downloadAndParse(cacheFile, null);
    }

    public static ParsedMap downloadAndParse(File cacheFile, ProgressListener listener) throws Exception {
        notify(listener, "准备下载地图数据：" + DEFAULT_URL, 0);
        downloadToFile(cacheFile, listener);
        notify(listener, "地图文件下载完成，开始读取本地缓存。", 1);
        FileInputStream in = new FileInputStream(cacheFile);
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            char[] buffer = new char[8192];
            int n;
            while ((n = reader.read(buffer)) != -1) sb.append(buffer, 0, n);
            notify(listener, "本地地图数据读取完成，共 " + sb.length() + " 个字符，开始解析 JSON。", 1);
            return parse(new JSONArray(sb.toString()), listener);
        } finally { in.close(); }
    }

    private static void downloadToFile(File target, ProgressListener listener) throws Exception {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) throw new Exception("无法创建缓存目录");
        notify(listener, "正在连接 SIMMC 地图服务器…", 0);
        HttpURLConnection connection = (HttpURLConnection) new URL(DEFAULT_URL).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(120000);
        connection.setRequestProperty("User-Agent", "SMC-Data-Collector-Java/2.0");
        connection.setUseCaches(false);
        File temp = new File(target.getParentFile(), "markers.json.tmp");
        try {
            int response = connection.getResponseCode();
            int total = connection.getContentLength();
            notify(listener, "服务器响应 HTTP " + response + "，文件大小：" + formatBytes(total), 0);
            java.io.InputStream input = connection.getInputStream();
            FileOutputStream output = new FileOutputStream(temp);
            try {
                byte[] buffer = new byte[16384];
                int n;
                long done = 0;
                int lastPercent = -1;
                long lastReport = 0;
                while ((n = input.read(buffer)) != -1) {
                    output.write(buffer, 0, n);
                    done += n;
                    long now = System.currentTimeMillis();
                    int percent = total > 0 ? (int)((done * 100L) / total) : -1;
                    if ((percent >= 0 && (lastPercent < 0 || percent / 5 != lastPercent / 5)) || now - lastReport >= 1000) {
                        String progress;
                        if (total > 0) progress = "下载进度：" + percent + "%（" + formatBytes(done) + " / " + formatBytes(total) + "）";
                        else progress = "下载进度：" + formatBytes(done) + "（服务器未提供文件大小）";
                        notify(listener, progress, 0);
                        lastPercent = percent;
                        lastReport = now;
                    }
                }
                notify(listener, "下载完成：" + formatBytes(done), 1);
            } finally { try { input.close(); } finally { output.close(); } }
            if (target.exists() && !target.delete()) throw new Exception("无法替换地图缓存");
            if (!temp.renameTo(target)) throw new Exception("无法保存地图缓存");
            notify(listener, "地图缓存已写入：" + target.getName(), 1);
        } finally {
            connection.disconnect();
            if (temp.exists()) temp.delete();
        }
    }

    private static String formatBytes(long bytes) {
        if (bytes < 0) return "未知";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0);
        return String.format(java.util.Locale.US, "%.2f MB", bytes / 1024.0 / 1024.0);
    }

    private static void notify(ProgressListener listener, String message, int type) {
        if (listener == null) return;
        listener.onProgress(message, type == 1 ? android.graphics.Color.rgb(55, 145, 90) : android.graphics.Color.rgb(65, 115, 175));
    }

    public static ParsedMap parse(JSONArray root) throws Exception {
        return parse(root, null);
    }

    public static ParsedMap parse(JSONArray root, ProgressListener listener) throws Exception {
        JSONObject layer = null;
        notify(listener, "正在查找领地图层：" + LAND_LAYER, 0);
        for (int i=0; i<root.length(); i++) {
            JSONObject item = root.getJSONObject(i);
            if (LAND_LAYER.equals(item.optString("id"))) { layer=item; break; }
        }
        if (layer == null) throw new Exception("找不到地图图层：" + LAND_LAYER);
        notify(listener, "找到领地图层，开始解析领地标记。", 1);
        JSONArray markers = layer.optJSONArray("markers");
        if (markers == null) markers = new JSONArray();
        List<Land> lands = new ArrayList<Land>();
        int skipped = 0;
        int totalMarkers = markers.length();
        notify(listener, "地图共有 " + totalMarkers + " 个领地标记。", 0);
        int lastReport = -1;
        for (int i=0; i<markers.length(); i++) {
            JSONObject marker = markers.getJSONObject(i);
            String popup = marker.optString("popup", marker.optString("tooltip", ""));
            Matcher nameM=NAME_RE.matcher(popup), balanceM=BALANCE_RE.matcher(popup), chunksM=CHUNKS_RE.matcher(popup);
            if (!nameM.find() || !balanceM.find() || !chunksM.find()) { skipped++; continue; }
            Land land = new Land();
            land.name = clean(nameM.group(1));
            try { land.balance = new BigDecimal(balanceM.group(1).replace(",", "")); } catch (Exception e) { skipped++; continue; }
            try { land.chunks = Integer.parseInt(chunksM.group(1)); } catch (Exception e) { skipped++; continue; }
            Matcher pm=PLAYERS_RE.matcher(popup);
            if (pm.find()) {
                String[] names=pm.group(2).split(",");
                for (String name:names) { String v=clean(name); if (v.length()>0 && !"...".equals(v) && !"…".equals(v)) land.players.add(v); }
                try { land.playerCount=Integer.parseInt(pm.group(1)); } catch(Exception e) { land.playerCount=land.players.size(); }
            } else land.playerCount=0;
            Matcher nm=NATION_RE.matcher(popup); if(nm.find()) land.nation=clean(nm.group(1));
            Matcher cm=CAPITAL_RE.matcher(popup); if(cm.find()) land.capital=clean(cm.group(1));
            double[] center=markerCenter(marker); land.x=(int)center[0]; land.z=(int)center[1];
            land.polygons=markerPolygons(marker);
            lands.add(land);
            int percent = totalMarkers == 0 ? 100 : (int)(((long)(i + 1) * 100L) / totalMarkers);
            if (percent != lastReport && (percent % 10 == 0 || i + 1 == totalMarkers)) {
                notify(listener, "领地解析进度：" + percent + "%（" + (i + 1) + "/" + totalMarkers + "）", 0);
                lastReport = percent;
            }
        }
        notify(listener, "领地解析完成：成功 " + lands.size() + "，跳过 " + skipped + "。", 1);
        return new ParsedMap(root, lands, skipped);
    }

    public static String clean(String value) {
        if (value == null) return "";
        return Html.fromHtml(value).toString().trim().replaceAll("\\s+", " ");
    }

    private static double[] markerCenter(JSONObject marker) {
        List<Point> points=new ArrayList<Point>(); collectPoints(marker.opt("points"), points);
        if(points.isEmpty()) collectPoints(marker.opt("point"), points);
        if(points.isEmpty()) return new double[]{0,0};
        double minX=points.get(0).x,maxX=minX,minZ=points.get(0).z,maxZ=minZ;
        for(Point p:points){if(p.x<minX)minX=p.x;if(p.x>maxX)maxX=p.x;if(p.z<minZ)minZ=p.z;if(p.z>maxZ)maxZ=p.z;}
        return new double[]{(minX+maxX)/2.0,(minZ+maxZ)/2.0};
    }

    private static void collectPoints(Object value,List<Point> out){
        if(value instanceof JSONObject){ JSONObject o=(JSONObject)value; if(o.has("x")&&o.has("z")) out.add(new Point(o.optDouble("x"),o.optDouble("z"))); }
        else if(value instanceof JSONArray){ JSONArray a=(JSONArray)value; for(int i=0;i<a.length();i++) collectPoints(a.opt(i),out); }
    }

    private static List<List<Point>> markerPolygons(JSONObject marker){
        List<List<Point>> result=new ArrayList<List<Point>>(); visitPolygons(marker.opt("points"),result); return result;
    }
    private static void visitPolygons(Object value,List<List<Point>> result){
        if(!(value instanceof JSONArray)) return;
        JSONArray a=(JSONArray)value; boolean polygon=a.length()>0;
        List<Point> points=new ArrayList<Point>();
        for(int i=0;i<a.length();i++){Object v=a.opt(i); if(!(v instanceof JSONObject)) {polygon=false;break;} JSONObject o=(JSONObject)v; if(!o.has("x")||!o.has("z")){polygon=false;break;} points.add(new Point(o.optDouble("x"),o.optDouble("z")));}
        if(polygon) result.add(points); else for(int i=0;i<a.length();i++) visitPolygons(a.opt(i),result);
    }
}
