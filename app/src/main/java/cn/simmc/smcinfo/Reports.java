package cn.simmc.smcinfo;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class Reports {
    public static final int TXT=0, HTML=1, XLSX=2;
    public static final class Report { public String fileName,mimeType; public byte[] bytes; public Report(String f,String m,byte[] b){fileName=f;mimeType=m;bytes=b;} }
    private static final String HEADER="该排名是从上到下降序排序，排行数字越小，代表排名越高";
    private static final DecimalFormat MONEY=new DecimalFormat("#,##0.00");
    public interface ProgressListener {
        void onProgress(String message, int color);
    }

    private Reports(){}
    private static String row(Object... values){StringBuilder s=new StringBuilder();for(int i=0;i<values.length;i++){if(i>0)s.append('\t');s.append(values[i]==null?"":String.valueOf(values[i]));}return s.toString();}

    public static List<Report> generate(SimmcData.ParsedMap map, boolean[] tasks, int format) throws Exception {
        return generate(map, tasks, format, null, null);
    }

    public static List<Report> generate(SimmcData.ParsedMap map, boolean[] tasks, int format, ProgressListener listener) throws Exception {
        return generate(map, tasks, format, null, listener);
    }

    public static List<Report> generate(SimmcData.ParsedMap map, boolean[] tasks, int format, String stamp, ProgressListener listener) throws Exception {
        notify(listener, "正在按国家整理 " + map.lands.size() + " 个领地…", 0);
        Map<String,List<SimmcData.Land>> grouped=new LinkedHashMap<String,List<SimmcData.Land>>();
        for(SimmcData.Land l:map.lands) if(l.nation!=null){if(!grouped.containsKey(l.nation))grouped.put(l.nation,new ArrayList<SimmcData.Land>());grouped.get(l.nation).add(l);}
        notify(listener, "国家整理完成：" + grouped.size() + " 个国家/国家实体。", 1);
        Map<String,String> texts=new LinkedHashMap<String,String>();
        if(tasks[0]){
            notify(listener, "开始生成：国家首都排行、国家总体排行、世界领地 GDP 排行。", 0);
            List<String[]> capitals=new ArrayList<String[]>();
            for(Map.Entry<String,List<SimmcData.Land>> e:grouped.entrySet()){String capital=findCapital(e.getValue());BigDecimal money=BigDecimal.ZERO;int chunks=0;for(SimmcData.Land l:e.getValue())if(l.name.equals(capital)){money=money.add(l.balance);chunks+=l.chunks;}capitals.add(new String[]{e.getKey(),capital,String.valueOf(chunks),money.toString()});}
            Collections.sort(capitals,new Comparator<String[]>(){public int compare(String[]a,String[]b){return new BigDecimal(b[3]).compareTo(new BigDecimal(a[3]));}});
            StringBuilder s=new StringBuilder();s.append(HEADER).append("\n\n").append(row("国家名称","国家首都","首都区块","首都存款","排名次")).append('\n');for(int i=0;i<capitals.size();i++){String[]r=capitals.get(i);s.append(row(r[0],r[1],r[2],MONEY.format(new BigDecimal(r[3])),"第"+(i+1)+"名")).append('\n');}texts.put("国家首都排行",s.toString());
            List<String[]> nations=new ArrayList<String[]>();for(Map.Entry<String,List<SimmcData.Land>>e:grouped.entrySet()){BigDecimal total=BigDecimal.ZERO;BigDecimal richest=null;List<String> rich=new ArrayList<String>();for(SimmcData.Land l:e.getValue()){total=total.add(l.balance);if(richest==null||l.balance.compareTo(richest)>0){richest=l.balance;rich.clear();rich.add(l.name);}else if(l.balance.compareTo(richest)==0)rich.add(l.name);}Collections.sort(rich);nations.add(new String[]{e.getKey(),findCapital(e.getValue()),join(rich,"、"),total.toString()});}Collections.sort(nations,new Comparator<String[]>(){public int compare(String[]a,String[]b){return new BigDecimal(b[3]).compareTo(new BigDecimal(a[3]));}});s=new StringBuilder();s.append(HEADER).append("\n\n").append(row("国家名","首都名","国家最富有领土","总存款","排名次")).append('\n');for(int i=0;i<nations.size();i++){String[]r=nations.get(i);s.append(row(r[0],r[1],r[2],MONEY.format(new BigDecimal(r[3])),"第"+(i+1)+"名")).append('\n');}texts.put("国家总体排行",s.toString());
            List<SimmcData.Land> lands=new ArrayList<SimmcData.Land>(map.lands);Collections.sort(lands,new Comparator<SimmcData.Land>(){public int compare(SimmcData.Land a,SimmcData.Land b){return b.balance.compareTo(a.balance);}});s=new StringBuilder();s.append(HEADER).append("\n\n").append(row("领地名称","所属国家","领地类型","领地存款","排名次")).append('\n');for(int i=0;i<lands.size();i++){SimmcData.Land l=lands.get(i);s.append(row(l.name,l.nation==null?"未加入国家":l.nation,kind(l),MONEY.format(l.balance),"第"+(i+1)+"名")).append('\n');}texts.put("全世界领地GDP排行",s.toString());
        }
        if(tasks[1]){
            notify(listener, "开始生成：全部领地坐标及区块。", 0);StringBuilder s=new StringBuilder();s.append("坐标为领地范围中心点，区块数取自地图领地资料\n\n").append(row("国家","领地类型","领地名称","中心坐标","所占区块","存款")).append('\n');List<SimmcData.Land> lands=new ArrayList<SimmcData.Land>(map.lands);Collections.sort(lands,new Comparator<SimmcData.Land>(){public int compare(SimmcData.Land a,SimmcData.Land b){String x=(a.nation==null?"1":a.nation)+a.name;String y=(b.nation==null?"1":b.nation)+b.name;return x.compareTo(y);}});for(SimmcData.Land l:lands)s.append(row(l.nation==null?"未加入国家":l.nation,kind(l),l.name,"X="+l.x+", Z="+l.z,l.chunks,MONEY.format(l.balance))).append('\n');texts.put("全部领地坐标及区块",s.toString());}
        if(tasks[2]){
            notify(listener, "开始生成：国家所有领地区块排行。", 0);StringBuilder s=new StringBuilder();s.append(HEADER).append("\n\n").append(row("国家名","首都名","领地数量","总区块数","排名次")).append('\n');List<String[]> rows=new ArrayList<String[]>();for(Map.Entry<String,List<SimmcData.Land>>e:grouped.entrySet()){int chunks=0;for(SimmcData.Land l:e.getValue())chunks+=l.chunks;rows.add(new String[]{e.getKey(),findCapital(e.getValue()),String.valueOf(e.getValue().size()),String.valueOf(chunks)});}Collections.sort(rows,new Comparator<String[]>(){public int compare(String[]a,String[]b){return Integer.parseInt(b[3])-Integer.parseInt(a[3]);}});for(int i=0;i<rows.size();i++){String[]r=rows.get(i);s.append(row(r[0],r[1],r[2],r[3],"第"+(i+1)+"名")).append('\n');}texts.put("国家所有领地区块排行",s.toString());}
        if(tasks[3]){
            notify(listener, "开始生成：国家首都坐标表。", 0);StringBuilder s=new StringBuilder();s.append("坐标为首都领地范围中心点\n\n").append(row("国家名","首都名","中心坐标","首都区块数")).append('\n');List<String> names=new ArrayList<String>(grouped.keySet());Collections.sort(names);for(String nation:names){List<SimmcData.Land> ms=grouped.get(nation);String cap=findCapital(ms);List<String> pos=new ArrayList<String>();int chunks=0;for(SimmcData.Land l:ms)if(l.name.equals(cap)){pos.add("X="+l.x+", Z="+l.z);chunks+=l.chunks;}s.append(row(nation,cap,pos.size()==0?"地图领地图层中未找到":join(pos,"、"),chunks)).append('\n');}texts.put("国家首都坐标表",s.toString());}
        if(tasks[4]) {
            notify(listener, "开始生成：港口和驿站坐标及领地归属。", 0);
            texts.put("港口和驿站坐标",gatewayText(map));
            notify(listener, "港口和驿站坐标生成完成。", 1);
        }
        if(tasks[5]){
            notify(listener, "开始生成：国家与独立领地人口排行。", 0);StringBuilder s=new StringBuilder();s.append(HEADER).append("\n\n").append(row("类型","国家或独立领地名称","玩家总数","排名次")).append('\n');List<String[]> rows=new ArrayList<String[]>();for(Map.Entry<String,List<SimmcData.Land>>e:grouped.entrySet()){Set<String> p=new HashSet<String>();for(SimmcData.Land l:e.getValue())p.addAll(l.players);rows.add(new String[]{"国家",e.getKey(),String.valueOf(p.size())});}for(SimmcData.Land l:map.lands)if(l.nation==null){Set<String>p=new HashSet<String>(l.players);rows.add(new String[]{"独立领地",l.name,String.valueOf(p.size())});}Collections.sort(rows,new Comparator<String[]>(){public int compare(String[]a,String[]b){return Integer.parseInt(b[2])-Integer.parseInt(a[2]);}});for(int i=0;i<rows.size();i++){String[]r=rows.get(i);s.append(row(r[0],r[1],r[2],"第"+(i+1)+"名")).append('\n');}texts.put("国家及独立领地玩家总数排行",s.toString());}
        notify(listener, "报表文本生成完成，共 " + texts.size() + " 项，开始转换为 " + formatName(format) + " 文件。", 0);
        List<Report> out=new ArrayList<Report>();
        int converted=0;
        for(Map.Entry<String,String>e:texts.entrySet()){
            out.add(convert(e.getKey(),e.getValue(),format,stamp));
            converted++;
            notify(listener, "文件转换进度：" + converted + "/" + texts.size() + " —— " + e.getKey(), 0);
        }
        notify(listener, "报表生成完成，共 " + out.size() + " 个文件。", 1);
        return out;
    }

    private static String formatName(int format) {
        if (format == TXT) return "TXT";
        if (format == XLSX) return "XLSX";
        return "HTML";
    }

    private static void notify(ProgressListener listener, String message, int color) {
        if (listener != null) listener.onProgress(message, color == 1 ? android.graphics.Color.rgb(55, 145, 90) : android.graphics.Color.rgb(65, 115, 175));
    }

    private static String findCapital(List<SimmcData.Land> ms){for(SimmcData.Land l:ms)if(l.capital!=null)return l.capital;return "未知";}
    private static String kind(SimmcData.Land l){return l.nation==null?"独立领地":(l.name.equals(l.capital)?"首都":"附属领地");}
    private static String join(List<String>a,String sep){StringBuilder s=new StringBuilder();for(int i=0;i<a.size();i++){if(i>0)s.append(sep);s.append(a.get(i));}return s.toString();}

    private static String gatewayText(SimmcData.ParsedMap map)throws Exception{JSONObject layer=null;for(int i=0;i<map.root.length();i++){JSONObject o=map.root.getJSONObject(i);if("transport_gateways".equals(o.optString("id"))){layer=o;break;}}if(layer==null)throw new Exception("找不到地图图层：transport_gateways");JSONArray markers=layer.optJSONArray("markers");if(markers==null)return "";StringBuilder s=new StringBuilder();s.append("归属通过港口/驿站坐标落入领地多边形进行匹配\n\n").append(row("所属国家","所属领地","名称","坐标点")).append('\n');List<String> rows=new ArrayList<String>();for(int i=0;i<markers.length();i++){JSONObject m=markers.getJSONObject(i),p=m.optJSONObject("point");if(p==null||!p.has("x")||!p.has("z"))continue;double x=p.optDouble("x"),z=p.optDouble("z");SimmcData.Land l=findLand(x,z,map.lands);rows.add(row(l==null||l.nation==null?"未加入国家":l.nation,l==null?"未匹配领地":l.name,SimmcData.clean(m.optString("tooltip",m.optString("popup","未命名"))),"X="+(int)x+", Z="+(int)z));}Collections.sort(rows);for(String r:rows)s.append(r).append('\n');return s.toString();}
    private static SimmcData.Land findLand(double x,double z,List<SimmcData.Land> lands){SimmcData.Land best=null;double bestArea=Double.MAX_VALUE;for(SimmcData.Land l:lands)for(List<SimmcData.Point> p:l.polygons)if(pointInPolygon(x,z,p)){double minX=p.get(0).x,maxX=minX,minZ=p.get(0).z,maxZ=minZ;for(SimmcData.Point q:p){if(q.x<minX)minX=q.x;if(q.x>maxX)maxX=q.x;if(q.z<minZ)minZ=q.z;if(q.z>maxZ)maxZ=q.z;}double area=(maxX-minX)*(maxZ-minZ);if(area<bestArea){bestArea=area;best=l;}}return best;}
    private static boolean pointInPolygon(double x,double z,List<SimmcData.Point> p){if(p.size()<3)return false;boolean inside=false;int j=p.size()-1;for(int i=0;i<p.size();i++){SimmcData.Point a=p.get(i),b=p.get(j);if((a.z>z)!=(b.z>z)&&x<(b.x-a.x)*(z-a.z)/(b.z-a.z)+a.x)inside=!inside;j=i;}return inside;}

    /** 文件名后面都加了时间戳，文件夹也是，分别不同版本的时间区分 */
    private static String stamped(String base, String ext, String stamp) {
        if (stamp == null || stamp.length() == 0) return base + ext;
        return base + "_" + stamp + ext;
    }

    private static Report convert(String name,String text,int format,String stamp){if(format==TXT)return new Report(stamped(name,".txt",stamp),"text/plain",bom(text));if(format==XLSX)return new Report(stamped(name,".xlsx",stamp),"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",Xlsx.create(text));return new Report(stamped(name,".html",stamp),"text/html",html(name,text).getBytes(java.nio.charset.Charset.forName("UTF-8")));}
    private static byte[] bom(String s){byte[] b=s.getBytes(java.nio.charset.Charset.forName("UTF-8"));byte[] o=new byte[b.length+3];o[0]=(byte)0xEF;o[1]=(byte)0xBB;o[2]=(byte)0xBF;System.arraycopy(b,0,o,3,b.length);return o;}
    private static String html(String name,String text){StringBuilder s=new StringBuilder("<!doctype html><html lang=\"zh-CN\"><meta charset=\"utf-8\"><title>");s.append(esc(name)).append("</title><style>body{font-family:sans-serif;margin:24px}table{border-collapse:collapse;width:100%}td{border:1px solid #bbb;padding:6px}tr:first-child{font-weight:bold;background:#eee}</style><h1>").append(esc(name)).append("</h1><table>");String[] lines=text.split("\\n",-1);for(String line:lines){s.append("<tr>");String[] cells=line.split("\\t",-1);for(String c:cells)s.append("<td>").append(esc(c)).append("</td>");s.append("</tr>");}return s.append("</table></html>").toString();}
    private static String esc(String s){return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;");}

    private static final class Xlsx{
        static byte[] create(String text){try{ByteArrayOutputStream out=new ByteArrayOutputStream();ZipOutputStream zip=new ZipOutputStream(out);add(zip,"[Content_Types].xml","<?xml version=\"1.0\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/><Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/></Types>");add(zip,"_rels/.rels","<?xml version=\"1.0\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>");add(zip,"xl/workbook.xml","<?xml version=\"1.0\"?><workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets><sheet name=\"数据\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>");add(zip,"xl/_rels/workbook.xml.rels","<?xml version=\"1.0\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/></Relationships>");StringBuilder data=new StringBuilder();String[]lines=text.split("\\n",-1);for(int r=0;r<lines.length;r++){data.append("<row r=\"").append(r+1).append("\">");String[]cells=lines[r].split("\\t",-1);for(int c=0;c<cells.length;c++)data.append("<c r=\"").append(column(c)).append(r+1).append("\" t=\"inlineStr\"><is><t>").append(xml(cells[c])).append("</t></is></c>");data.append("</row>");}add(zip,"xl/worksheets/sheet1.xml","<?xml version=\"1.0\" encoding=\"UTF-8\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>"+data+"</sheetData></worksheet>");zip.close();return out.toByteArray();}catch(Exception e){throw new RuntimeException(e);}}
        static void add(ZipOutputStream z,String p,String v)throws Exception{z.putNextEntry(new ZipEntry(p));z.write(v.getBytes(java.nio.charset.Charset.forName("UTF-8")));z.closeEntry();}
        static String column(int i){int n=i+1;String s="";while(n>0){s=(char)('A'+(n-1)%26)+s;n=(n-1)/26;}return s;}
        static String xml(String s){return esc(s).replace("\"","&quot;");}
    }
}