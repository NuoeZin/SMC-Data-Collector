package cn.simmc.smcinfo;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.provider.DocumentsContract;
import android.net.Uri;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableStringBuilder;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int REQ_STORAGE = 1001;
    private static final int ACCENT = Color.rgb(0, 150, 136);
    private static final int ACCENT_DARK = Color.rgb(0, 121, 107);
    private static final int BG = Color.rgb(245, 246, 247);
    private static final int CARD = Color.WHITE;
    private static final int TEXT = Color.rgb(45, 45, 45);
    private static final int MUTED = Color.rgb(110, 116, 120);
    private static final int BORDER = Color.rgb(224, 227, 229);

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final boolean[] tasks = new boolean[]{true, true, true, true, true, true};
    private final String[] taskNames = new String[]{
            "国家首都排行 + 国家总体排行",
            "全部领地坐标及区块",
            "国家所有领地区块排行",
            "国家首都坐标表",
            "港口和驿站坐标及归属",
            "国家与独立领地人口排行"
    };

    private FrameLayout pageRoot;
    private LinearLayout settingsPage;
    private TextView logView;
    private TextView generateButton;
    private TextView formatValue;
    private ModernSwitch autoClearSwitch;
    private File cacheDir;
    private File mapCache;
    private SharedPreferences prefs;
    private boolean settingsOpen;
    private boolean generating;
    private final StringBuilder rawLog = new StringBuilder();
    private final SpannableStringBuilder logBuilder = new SpannableStringBuilder();
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.US);

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(ACCENT_DARK);
        cacheDir = new File(getCacheDir(), "map_cache");
        mapCache = new File(cacheDir, "markers.json");
        prefs = getSharedPreferences("settings", MODE_PRIVATE);
        buildUi();
        updateCacheInfo();
        requestLegacyStorageIfNeeded();
        appendLog("应用启动：SMC 信息生成器", ACCENT);
        appendLog("Mon3tr 已就位！", MUTED);
        appendLog("地图数据使用临时缓存，生成成功后可自动清除。", MUTED);
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private TextView text(String value, float size, int color) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(size);
        v.setTextColor(color);
        v.setGravity(Gravity.CENTER_VERTICAL);
        return v;
    }

    private GradientDrawable bg(int color, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radius));
        return d;
    }

    private GradientDrawable borderBg(int fill, int stroke, float radius) {
        GradientDrawable d = bg(fill, radius);
        d.setStroke(dp(1), stroke);
        return d;
    }

    private ImageButton iconButton(String name, String description, int size) {
        ImageButton b = new ImageButton(this);
        int id = getResources().getIdentifier(name, "drawable", getPackageName());
        if (id != 0) b.setImageResource(id);
        b.setContentDescription(description);
        b.setBackgroundColor(Color.TRANSPARENT);
        b.setPadding(dp(8), dp(8), dp(8), dp(8));
        b.setScaleType(ImageButton.ScaleType.CENTER_INSIDE);
        return b;
    }

    private void buildUi() {
        pageRoot = new FrameLayout(this);
        pageRoot.setBackgroundColor(BG);

        LinearLayout main = buildMainPage();
        pageRoot.addView(main, new FrameLayout.LayoutParams(-1, -1));

        settingsPage = buildSettingsPage();
        FrameLayout.LayoutParams settingsParams = new FrameLayout.LayoutParams(-1, -1);
        settingsParams.gravity = Gravity.RIGHT;
        pageRoot.addView(settingsPage, settingsParams);
        settingsPage.setVisibility(View.GONE);
        settingsOpen = false;

        setContentView(pageRoot);
    }

    private LinearLayout toolbar(final boolean settings) {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), 0, dp(6), 0);
        bar.setBackgroundColor(CARD);

        if (settings) {
            ImageButton back = iconButton("ic_back", "返回", 48);
            back.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { closeSettings(); }
            });
            bar.addView(back, new LinearLayout.LayoutParams(dp(48), dp(56)));
        }

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text(settings ? "设置" : "SMC 信息生成器", 19, TEXT);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        titles.addView(title, new LinearLayout.LayoutParams(-1, dp(settings ? 56 : 31)));
        if (!settings) {
            TextView sub = text("SIMMC 地图数据排行与报表工具", 11, MUTED);
            titles.addView(sub, new LinearLayout.LayoutParams(-1, dp(22)));
        }
        bar.addView(titles, new LinearLayout.LayoutParams(0, dp(56), 1));

        if (!settings) {
            ImageButton gear = iconButton("ic_settings", "打开设置", 48);
            gear.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { openSettings(); }
            });
            bar.addView(gear, new LinearLayout.LayoutParams(dp(48), dp(56)));
        }

        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.addView(bar, new LinearLayout.LayoutParams(-1, dp(56)));
        View line = new View(this);
        line.setBackgroundColor(BORDER);
        wrapper.addView(line, new LinearLayout.LayoutParams(-1, dp(1)));
        return wrapper;
    }

    private LinearLayout buildMainPage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(BG);
        page.addView(toolbar(false));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(10), dp(8), dp(10), dp(14));
        scroll.addView(content);
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        TextView outputTitle = sectionTitle("输出内容");
        content.addView(outputTitle, new LinearLayout.LayoutParams(-1, dp(32)));

        LinearLayout outputCard = new LinearLayout(this);
        outputCard.setOrientation(LinearLayout.VERTICAL);
        outputCard.setPadding(dp(8), dp(3), dp(8), dp(3));
        outputCard.setBackgroundDrawable(borderBg(CARD, BORDER, 3));
        for (int i = 0; i < taskNames.length; i++) outputCard.addView(makeTaskRow(i));
        content.addView(outputCard, new LinearLayout.LayoutParams(-1, dp(6 * 34 + 6)));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dp(8), 0, dp(5));
        generateButton = action("开始生成", ACCENT, Color.WHITE);
        generateButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startGenerate(prefs.getInt("format", Reports.HTML)); }
        });
        actions.addView(generateButton, new LinearLayout.LayoutParams(0, dp(42), 1));
        TextView open = action("打开输出目录", CARD, TEXT);
        open.setBackgroundDrawable(borderBg(CARD, BORDER, 3));
        open.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openOutputFolder(); }
        });
        LinearLayout.LayoutParams openParams = new LinearLayout.LayoutParams(0, dp(42), 1);
        openParams.leftMargin = dp(7);
        actions.addView(open, openParams);
        content.addView(actions);

        LinearLayout logCard = buildLogCard();
        LinearLayout.LayoutParams logParams = new LinearLayout.LayoutParams(-1, dp(222));
        logParams.topMargin = dp(4);
        content.addView(logCard, logParams);

        return page;
    }

    private TextView sectionTitle(String value) {
        TextView t = text(value, 15, TEXT);
        t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private LinearLayout makeTaskRow(final int index) {
        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(4), 0, dp(4), 0);

        final TextView mark = text("", 14, Color.WHITE);
        mark.setGravity(Gravity.CENTER);
        mark.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        row.addView(mark, new LinearLayout.LayoutParams(dp(22), dp(22)));

        TextView label = text(taskNames[index], 13, TEXT);
        label.setPadding(dp(8), 0, 0, 0);
        row.addView(label, new LinearLayout.LayoutParams(0, dp(34), 1));
        updateTaskMark(mark, tasks[index]);

        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                tasks[index] = !tasks[index];
                updateTaskMark(mark, tasks[index]);
            }
        });
        return row;
    }

    private void updateTaskMark(TextView mark, boolean checked) {
        mark.setText(checked ? "✓" : "");
        mark.setTextColor(Color.WHITE);
        mark.setBackgroundDrawable(checked ? bg(ACCENT, 3) : borderBg(Color.WHITE, Color.rgb(175, 180, 184), 3));
    }

    private TextView action(String value, int color, int textColor) {
        TextView v = text(value, 14, textColor);
        v.setGravity(Gravity.CENTER);
        v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        v.setBackgroundDrawable(bg(color, 3));
        return v;
    }

    private LinearLayout buildLogCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundDrawable(borderBg(Color.WHITE, BORDER, 3));

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(10), 0, dp(3), 0);
        TextView title = text("运行日志", 14, TEXT);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        head.addView(title, new LinearLayout.LayoutParams(0, dp(36), 1));

        ImageButton clear = iconButton("ic_clear", "清理日志", 34);
        clear.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { clearLog(); }
        });
        head.addView(clear, new LinearLayout.LayoutParams(dp(34), dp(34)));

        ImageButton copy = iconButton("ic_copy", "复制日志", 34);
        copy.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { copyLog(); }
        });
        head.addView(copy, new LinearLayout.LayoutParams(dp(34), dp(34)));
        card.addView(head, new LinearLayout.LayoutParams(-1, dp(36)));

        View line = new View(this);
        line.setBackgroundColor(BORDER);
        card.addView(line, new LinearLayout.LayoutParams(-1, dp(1)));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        logView = new TextView(this);
        logView.setTextSize(11.5f);
        logView.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL);
        logView.setGravity(Gravity.TOP | Gravity.LEFT);
        logView.setTextColor(Color.rgb(55, 55, 55));
        logView.setPadding(dp(8), dp(7), dp(8), dp(7));
        logView.setTextIsSelectable(true);
        logView.setBackgroundColor(Color.WHITE);
        scroll.addView(logView, new ScrollView.LayoutParams(-1, -2));
        card.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        return card;
    }

    private LinearLayout buildSettingsPage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(BG);
        page.addView(toolbar(true));

        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(10), dp(10), dp(10), dp(18));
        scroll.addView(content);
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        content.addView(sectionTitle("生成设置"), new LinearLayout.LayoutParams(-1, dp(32)));

        final LinearLayout formatRow = settingRow();
        TextView formatLabel = text("默认输出格式", 14, TEXT);
        formatRow.addView(formatLabel, new LinearLayout.LayoutParams(0, dp(46), 1));
        formatValue = text(formatName(prefs.getInt("format", Reports.HTML)), 14, ACCENT_DARK);
        formatValue.setCompoundDrawablesWithIntrinsicBounds(0, 0, getResources().getIdentifier("ic_arrow_drop_down", "drawable", getPackageName()), 0);
        formatValue.setCompoundDrawablePadding(dp(2));
        formatValue.setGravity(Gravity.CENTER);
        formatValue.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        formatRow.addView(formatValue, new LinearLayout.LayoutParams(dp(110), dp(46)));
        formatRow.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showFormatPopup(formatValue); }
        });
        content.addView(formatRow);

        LinearLayout autoRow = settingRow();
        TextView autoLabel = text("生成成功后自动清除地图缓存", 14, TEXT);
        autoRow.addView(autoLabel, new LinearLayout.LayoutParams(0, dp(46), 1));
        autoClearSwitch = new ModernSwitch(this);
        autoClearSwitch.setChecked(prefs.getBoolean("auto_clear", true));
        autoClearSwitch.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                boolean next = autoClearSwitch.isChecked();
                prefs.edit().putBoolean("auto_clear", next).apply();
                appendLog("设置已更新：生成成功后自动清除缓存 = " + (next ? "开启" : "关闭"), ACCENT);
            }
        });
        autoRow.addView(autoClearSwitch, new LinearLayout.LayoutParams(dp(52), dp(32)));
        content.addView(autoRow);

        content.addView(sectionTitle("缓存管理"), new LinearLayout.LayoutParams(-1, dp(32)));
        TextView clear = action("立即清除地图缓存", CARD, TEXT);
        clear.setBackgroundDrawable(borderBg(CARD, BORDER, 3));
        clear.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { clearCacheNow(); }
        });
        content.addView(clear, new LinearLayout.LayoutParams(-1, dp(42)));

        content.addView(sectionTitle("关于"), new LinearLayout.LayoutParams(-1, dp(40)));
        TextView about = action("版本与项目说明", CARD, TEXT);
        about.setBackgroundDrawable(borderBg(CARD, BORDER, 3));
        about.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showInfoPanel("关于 SMC 信息生成器", "\n从 SIMMC 网页地图获取数据，生成国家 GDP、领地、区块、首都坐标、交通节点和人口排行。\n\n最低支持 Android 4.4\n地图数据仅作为临时缓存使用。\n数据来源：SIMMC网页卫星地图。\n仅用于个人娱乐与数据分析。\n仓库：https://github.com/NuoeZin/SMC-Data-Collector\n\n"); }
        });
        content.addView(about, new LinearLayout.LayoutParams(-1, dp(42)));

        TextView note = text("比比拉布", 12, MUTED);
        note.setPadding(dp(4), dp(12), dp(4), 0);
        content.addView(note, new LinearLayout.LayoutParams(-1, dp(48)));
        return page;
    }

    private LinearLayout settingRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), 0, dp(8), 0);
        row.setBackgroundDrawable(borderBg(CARD, BORDER, 3));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(48));
        p.bottomMargin = dp(7);
        row.setLayoutParams(p);
        return row;
    }

    private void showFormatPopup(View anchor) {
        final PopupWindow popup = new PopupWindow(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(4), dp(4), dp(4), dp(4));
        box.setBackgroundDrawable(bg(Color.WHITE, 3));
        String[] names = new String[]{"HTML", "TXT", "XLSX"};
        int[] values = new int[]{Reports.HTML, Reports.TXT, Reports.XLSX};
        for (int i = 0; i < names.length; i++) {
            final int value = values[i];
            TextView item = text(names[i], 14, TEXT);
            item.setGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT);
            item.setPadding(dp(14), 0, dp(10), 0);
            if (prefs.getInt("format", Reports.HTML) == value) {
                item.setTextColor(ACCENT_DARK);
                item.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            }
            item.setBackgroundDrawable(bg(Color.WHITE, 2));
            box.addView(item, new LinearLayout.LayoutParams(dp(150), dp(42)));
            item.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    prefs.edit().putInt("format", value).apply();
                    if (formatValue != null) formatValue.setText(formatName(value));
                    appendLog("设置已更新：默认输出格式 = " + formatName(value), ACCENT);
                    popup.dismiss();
                }
            });
        }
        popup.setContentView(box);
        popup.setWidth(dp(158));
        popup.setHeight(dp(3 * 42 + 8));
        popup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.WHITE));
        popup.setOutsideTouchable(true);
        popup.setFocusable(true);
        if (Build.VERSION.SDK_INT >= 21) popup.setElevation(dp(5));
        // 以右侧的格式值控件为锚点，让弹出列表紧贴其下方，并与右边缘对齐。
        popup.showAsDropDown(anchor, -dp(48), dp(2));
    }

    private String formatName(int f) {

        if (f == Reports.TXT) return "TXT";
        if (f == Reports.XLSX) return "XLSX";
        return "HTML";
    }

    private void openSettings() {
        if (settingsOpen) return;
        settingsOpen = true;
        settingsPage.setVisibility(View.VISIBLE);
        settingsPage.bringToFront();
        settingsPage.setTranslationX(pageRoot.getWidth() == 0 ? dp(360) : pageRoot.getWidth());
        settingsPage.animate().translationX(0).setDuration(170).setInterpolator(new AccelerateDecelerateInterpolator()).start();
    }

    private void closeSettings() {
        if (!settingsOpen) return;
        settingsOpen = false;
        settingsPage.animate().translationX(pageRoot.getWidth()).setDuration(170).setInterpolator(new AccelerateDecelerateInterpolator()).withEndAction(new Runnable() {
            @Override public void run() { settingsPage.setVisibility(View.GONE); }
        }).start();
    }

    @Override public void onBackPressed() {
        if (settingsOpen) { closeSettings(); return; }
        super.onBackPressed();
    }

    private void appendLog(final String message, final int color) {
        if (logView == null) return;
        final Runnable r = new Runnable() {
            @Override public void run() {
                String line = "[" + timeFormat.format(new Date()) + "] " + message + "\n";
                rawLog.append(line);
                int begin = logBuilder.length();
                logBuilder.append(line);
                logBuilder.setSpan(new android.text.style.ForegroundColorSpan(color), begin, logBuilder.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                // Re-assign the complete buffer on every message. This deliberately forces
                // TextView invalidation/layout so progress is visible line-by-line even on
                // older Android rendering implementations.
                logView.setText(logBuilder);
                logView.requestLayout();
                logView.invalidate();
                logView.post(new Runnable() {
                    @Override public void run() {
                        ViewParent p = logView.getParent();
                        if (p instanceof ScrollView) {
                            ((ScrollView)p).fullScroll(View.FOCUS_DOWN);
                        }
                    }
                });
            }
        };
        if (Thread.currentThread() == getMainLooper().getThread()) r.run(); else runOnUiThread(r);
    }

    private void clearLog() {
        rawLog.setLength(0);
        logBuilder.clear();
        if (logView != null) logView.setText("");
        appendLog("日志已清理。", MUTED);
    }

    private void copyLog() {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("SMC 运行日志", rawLog.toString()));
        Toast.makeText(this, "日志已复制到剪贴板", Toast.LENGTH_SHORT).show();
    }

    private void startGenerate(final int format) {
        if (generating) return;
        boolean any = false;
        for (boolean b : tasks) if (b) { any = true; break; }
        if (!any) { appendLog("错误：没有选择任何输出内容。", Color.rgb(210, 55, 55)); return; }
        generating = true;
        generateButton.setText("生成中…");
        generateButton.setBackgroundDrawable(bg(Color.rgb(120, 130, 133), 3));
        final boolean[] selected = tasks.clone();
        appendLog("========== 开始一次新的数据任务 ==========", ACCENT_DARK);
        appendLog("输出格式：" + formatName(format), ACCENT);
        appendLog("已选择输出项：" + countSelected(selected) + "/" + selected.length, MUTED);
        executor.execute(new Runnable() {
            @Override public void run() {
                try {
                    SimmcData.ParsedMap map = SimmcData.downloadAndParse(mapCache, new SimmcData.ProgressListener() {
                        @Override public void onProgress(String message, int color) { appendLog(message, color); }
                    });
                    appendLog("地图数据解析阶段结束，开始计算报表。", ACCENT_DARK);
                    List<Reports.Report> reports = Reports.generate(map, selected, format, new Reports.ProgressListener() {
                        @Override public void onProgress(String message, int color) { appendLog(message, color); }
                    });
                    appendLog("报表计算完成，准备写入外部存储。", ACCENT_DARK);
                    saveReports(reports);
                    appendLog("全部文件写入完成：Download/SMap_file/", Color.rgb(55, 145, 90));
                    if (prefs.getBoolean("auto_clear", true)) {
                        deleteCacheFiles();
                        appendLog("自动清理完成：地图临时缓存已删除。", Color.rgb(55, 145, 90));
                    } else {
                        appendLog("设置为保留缓存：markers.json 暂未删除。", Color.rgb(210, 140, 40));
                    }
                    appendLog("任务完成：文件 " + reports.size() + " 个；领地 " + map.lands.size() + " 个；跳过 " + map.skipped + " 个。", ACCENT_DARK);
                    appendLog("========== 本次数据任务结束 ==========", ACCENT_DARK);
                    finishGenerationUi();
                } catch (Exception e) {
                    String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                    appendLog("任务失败：" + msg, Color.rgb(210, 55, 55));
                    appendLog("为了方便排查，本次地图缓存暂时保留。", Color.rgb(210, 140, 40));
                    finishGenerationUi();
                }
            }
        });
    }

    private int countSelected(boolean[] values) {
        int n = 0;
        for (boolean v : values) if (v) n++;
        return n;
    }

    private void finishGenerationUi() {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                generating = false;
                generateButton.setText("开始生成");
                generateButton.setBackgroundDrawable(bg(ACCENT, 3));
                updateCacheInfo();
            }
        });
    }

    private void saveReports(List<Reports.Report> reports) throws Exception {
        if (Build.VERSION.SDK_INT >= 29) {
            saveReportsWithMediaStore(reports);
        } else {
            saveReportsLegacy(reports);
        }
    }

    /** Android 10+ uses MediaStore.Downloads so scoped-storage devices do not fail with EPERM. */
    private void saveReportsWithMediaStore(List<Reports.Report> reports) throws Exception {
        ContentResolver resolver = getContentResolver();
        Uri collection = Uri.parse("content://media/external/downloads");
        appendLog("Android 10+：使用 MediaStore.Downloads 保存输出文件。", MUTED);
        appendLog("输出目录：Download/SMap_file", MUTED);
        for (int i = 0; i < reports.size(); i++) {
            Reports.Report report = reports.get(i);
            ContentValues values = new ContentValues();
            values.put("_display_name", report.fileName);
            values.put("mime_type", report.mimeType);
            values.put("relative_path", "Download/SMap_file");
            values.put("is_pending", 1);
            Uri uri = resolver.insert(collection, values);
            if (uri == null) throw new Exception("MediaStore 无法创建文件：" + report.fileName);
            try {
                java.io.OutputStream out = resolver.openOutputStream(uri, "w");
                if (out == null) throw new Exception("无法打开输出流：" + report.fileName);
                try {
                    out.write(report.bytes);
                    out.flush();
                } finally {
                    out.close();
                }
                ContentValues done = new ContentValues();
                done.put("is_pending", 0);
                resolver.update(uri, done, null, null);
                appendLog("写入文件 " + (i + 1) + "/" + reports.size() + "：" + report.fileName + "（" + report.bytes.length + " B）", Color.rgb(55, 145, 90));
            } catch (Exception e) {
                try { resolver.delete(uri, null, null); } catch (Exception ignored) {}
                throw e;
            }
        }
    }

    private void saveReportsLegacy(List<Reports.Report> reports) throws Exception {
        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "SMap_file");
        if (!dir.exists() && !dir.mkdirs()) throw new Exception("无法创建 Download/SMap_file");
        appendLog("Android 9 及以下：使用传统外部存储保存文件。", MUTED);
        appendLog("输出目录：" + dir.getAbsolutePath(), MUTED);
        for (int i = 0; i < reports.size(); i++) {
            Reports.Report report = reports.get(i);
            File file = new File(dir, report.fileName);
            FileOutputStream out = new FileOutputStream(file);
            try { out.write(report.bytes); out.flush(); } finally { out.close(); }
            appendLog("写入文件 " + (i + 1) + "/" + reports.size() + "：" + report.fileName + "（" + report.bytes.length + " B）", Color.rgb(55, 145, 90));
        }
    }

    private void requestLegacyStorageIfNeeded() {
        if (Build.VERSION.SDK_INT >= 23 && Build.VERSION.SDK_INT <= 28 && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
    }

    private void updateCacheInfo() {
        // Cache details are intentionally kept out of the main UI.
        // Cache cleanup remains available from Settings.
    }

    private void clearCacheNow() {
        deleteCacheFiles();
        updateCacheInfo();
        appendLog("地图临时缓存已手动清除。", Color.rgb(55, 145, 90));
        Toast.makeText(this, "地图缓存已清除", Toast.LENGTH_SHORT).show();
    }

    private void deleteCacheFiles() {
        if (cacheDir.exists()) {
            File[] files = cacheDir.listFiles();
            if (files != null) for (File f : files) { if (f.isDirectory()) deleteDir(f); else f.delete(); }
            cacheDir.delete();
        }
    }

    private void deleteDir(File dir) {
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) { if (f.isDirectory()) deleteDir(f); else f.delete(); }
        dir.delete();
    }

    private void openOutputFolder() {
        final String folderPath = "Download/SMap_file";
        // Android 8.0+ can tell DocumentsUI exactly which folder to display.
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                Uri initial = DocumentsContract.buildDocumentUri(
                        "com.android.externalstorage.documents",
                        "primary:" + folderPath);
                i.putExtra("android.provider.extra.INITIAL_URI", initial);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                startActivityForResult(i, 2001);
                return;
            } catch (Exception ignored) { }
        }
        // Android 5.0-7.1: DocumentsUI can often open the concrete folder URI directly.
        if (Build.VERSION.SDK_INT >= 21) {
            try {
                Uri folder = Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload%2FSMap_file");
                Intent i = new Intent(Intent.ACTION_VIEW);
                i.setDataAndType(folder, "vnd.android.document/directory");
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(i);
                return;
            } catch (Exception ignored) {
                try {
                    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                    startActivityForResult(i, 2001);
                    return;
                } catch (Exception ignored2) { }
            }
        }
        // Android 4.4 has no ACTION_OPEN_DOCUMENT_TREE. Ask an installed file manager
        // to open the real directory, with a safe fallback to a clear path message.
        try {
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "SMap_file");
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(Uri.fromFile(dir), "resource/folder");
            startActivity(i);
            return;
        } catch (Exception ignored) { }
        Toast.makeText(this, "输出目录：Download/SMap_file", Toast.LENGTH_LONG).show();
    }

    private void showInfoPanel(String titleText, String bodyText) {
        final FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(Color.argb(90, 0, 0, 0));
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(14), dp(18), dp(12));
        card.setBackgroundDrawable(bg(Color.WHITE, 4));
        TextView title = text(titleText, 18, TEXT);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        card.addView(title, new LinearLayout.LayoutParams(-1, dp(38)));
        TextView body = text(bodyText, 13, Color.rgb(70, 75, 78));
        body.setGravity(Gravity.TOP | Gravity.LEFT);
        card.addView(body, new LinearLayout.LayoutParams(-1, dp(230)));
        TextView close = action("关闭", ACCENT, Color.WHITE);
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pageRoot.removeView(overlay); }
        });
        card.addView(close, new LinearLayout.LayoutParams(-1, dp(42)));
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(-1, dp(340), Gravity.CENTER);
        cp.leftMargin = dp(20); cp.rightMargin = dp(20);
        overlay.addView(card, cp);
        pageRoot.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        overlay.bringToFront();
    }

    private static class ModernSwitch extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean checked;
        private final int onColor = Color.rgb(0, 150, 136);
        private final int offTrack = Color.rgb(205, 210, 212);
        private final int thumbColor = Color.WHITE;
        public ModernSwitch(android.content.Context context) { super(context); setClickable(true); }
        public void setChecked(boolean value) { checked = value; invalidate(); }
        public boolean isChecked() { return checked; }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float d = getResources().getDisplayMetrics().density;
            float left = 4 * d, right = getWidth() - 4 * d;
            float cy = getHeight() / 2f;
            float h = 18 * d;
            float r = h / 2f;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(checked ? Color.rgb(128, 203, 196) : offTrack);
            canvas.drawRoundRect(left, cy-r, right, cy+r, r, r, paint);
            float thumbR = 11 * d;
            float x = checked ? right - thumbR : left + thumbR;
            paint.setColor(checked ? onColor : Color.rgb(245, 245, 245));
            canvas.drawCircle(x, cy, thumbR, paint);
            if (!checked) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(1 * d);
                paint.setColor(Color.rgb(185, 190, 192));
                canvas.drawCircle(x, cy, thumbR, paint);
            }
        }
        @Override public boolean performClick() {
            checked = !checked;
            invalidate();
            return super.performClick();
        }
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }
}
