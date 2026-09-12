package com.bm.admin;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * 注册码管理器(手机端): 与 PC GUI keygen_gui.py 功能一致。
 * 生成(AES-128-ECB+Base32, 生成即自动上云) / 解密 / 历史记录(删除联动云端) /
 * 云端激活管理(多选批量: 刷新/全选/停用/启用/续期(手动天数)/删除)。
 */
public class MainActivity extends Activity {

    private static final String KEY = "bM@2026!Lx#Yd$Kz";
    private static final String B32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final String CLOUD_REPO = "LastTime-zt/license-db";
    private static final String CLOUD_PATH = "licenses.json";
    private static final String CLOUD_API =
            "https://api.github.com/repos/" + CLOUD_REPO + "/contents/" + CLOUD_PATH;

    // 配色(与主APP一致的主色系)
    private static final int PRIMARY = 0xFF1A66C2;
    private static final int PRIMARY_DARK = 0xFF124E96;
    private static final int BG = 0xFFF2F5F9;
    private static final int CARD = 0xFFFFFFFF;
    private static final int TEXT = 0xFF222222;
    private static final int TEXT_SUB = 0xFF8A94A0;
    private static final int GREEN = 0xFF0A7D32;
    private static final int RED = 0xFFCC3333;
    private static final int SEL_BG = 0xFFDCE9FA;

    private LinearLayout content;      // 当前 tab 内容区
    private LinearLayout histList;     // 历史记录列表容器
    private LinearLayout cloudList;    // 云端记录列表容器
    private TextView statusLine;       // 底部状态行
    private SQLiteDatabase db;

    private EditText etDev, etDays, etDate, etCode, etPat;
    private final Set<String> selCodes = new HashSet<>();   // 云端多选(规范化码)

    // ================= 生命周期 =================
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        // 崩溃自显: 出错不闪退, 把堆栈显示在屏幕上便于远程定位
        try {
            init(b);
        } catch (Throwable t) {
            setContentView(crashView(t));
        }
    }

    private ScrollView crashView(Throwable t) {
        java.io.StringWriter sw = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(sw));
        TextView tv = new TextView(this);
        tv.setText("启动出错, 请截图发给管理员:\n\n" + sw);
        tv.setTextSize(11);
        tv.setTextColor(0xFFCC3333);
        tv.setPadding(dp(12), dp(12), dp(12), dp(12));
        tv.setTextIsSelectable(true);
        ScrollView sc = new ScrollView(this);
        sc.addView(tv);
        return sc;
    }

    private void init(Bundle b) {
        db = new Db(this).getWritableDatabase();
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        // 顶部标题栏(渐变)
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setGravity(Gravity.CENTER);
        bar.setPadding(dp(14), dp(14), dp(14), dp(14));
        android.graphics.drawable.GradientDrawable grad =
                new android.graphics.drawable.GradientDrawable(
                        android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                        new int[]{PRIMARY, PRIMARY_DARK});
        grad.setCornerRadii(new float[]{0, 0, 0, 0, dp(14), dp(14), dp(14), dp(14)});
        bar.setBackground(grad);
        TextView title = new TextView(this);
        title.setText("注册码管理器");
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.WHITE);
        title.setGravity(Gravity.CENTER);
        bar.addView(title);
        TextView sub = new TextView(this);
        sub.setText("生成 · 解密 · 云端绑定管理");
        sub.setTextSize(11);
        sub.setTextColor(0xCCFFFFFF);
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, dp(3), 0, 0);
        bar.addView(sub);
        root.addView(bar);

        // tab 按钮
        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setPadding(dp(6), dp(6), dp(6), dp(6));
        tabs.setBackgroundColor(Color.WHITE);
        root.addView(tabs);
        String[] names = {"生成", "解密", "历史", "云端"};
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            Button t = mkBtn(names[i], 0xFFE8ECF2, 0xFF555555, 14);
            t.setOnClickListener(v -> {
                for (int j = 0; j < tabs.getChildCount(); j++) {
                    Button tb = (Button) tabs.getChildAt(j);
                    tb.getBackground().setColorFilter(null);
                    tb.setBackground(roundBtn(j == idx ? PRIMARY : 0xFFE8ECF2));
                    tb.setTextColor(j == idx ? Color.WHITE : 0xFF555555);
                }
                showTab(idx);
            });
            tabs.addView(t, row(0, dp(40), 1));
        }
        ((Button) tabs.getChildAt(0)).setBackground(roundBtn(PRIMARY));
        ((Button) tabs.getChildAt(0)).setTextColor(Color.WHITE);

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        // 垂直布局子项宽度必须 MATCH_PARENT, 传 0 会导致整个内容区 0 像素宽(白屏)
        root.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // 底部状态行
        statusLine = new TextView(this);
        statusLine.setTextSize(12);
        statusLine.setTextColor(TEXT_SUB);
        statusLine.setBackgroundColor(Color.WHITE);
        statusLine.setPadding(dp(14), dp(8), dp(14), dp(10));
        root.addView(statusLine);
        String ver;
        try {
            ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            ver = "";
        }
        setStatus("就绪 · v" + ver);

        setContentView(root);
        showTab(0);
    }

    private void showTab(int idx) {
        content.removeAllViews();
        if (idx == 0) buildGenTab();
        else if (idx == 1) buildDecTab();
        else if (idx == 2) buildHistTab();
        else buildCloudTab();
    }

    private void setStatus(String s) { statusLine.setText(s); }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    // ================= Tab1: 生成 =================
    private void buildGenTab() {
        ScrollView sc = scroll();
        LinearLayout f = col(sc);
        cardTitle(f, "生成注册码 (生成后自动同步云端)");

        etDev = input(f, "", "设备码: APP 内显示的 8 位码");

        // 有效期模式: 天数 / 到期日期
        TextView lMode = label(f, "有效期模式:");
        LinearLayout modeRow = new LinearLayout(this);
        modeRow.setOrientation(LinearLayout.HORIZONTAL);
        RadioGroup rgMode = new RadioGroup(this);
        rgMode.setOrientation(RadioGroup.HORIZONTAL);
        RadioButton rbDays = new RadioButton(this);
        rbDays.setText("按天数");
        rbDays.setChecked(true);
        rbDays.setTextColor(TEXT);
        RadioButton rbDate = new RadioButton(this);
        rbDate.setText("指定日期(YYYY-MM-DD)");
        rbDate.setTextColor(TEXT);
        rgMode.addView(rbDays);
        rgMode.addView(rbDate);
        modeRow.addView(rgMode);
        f.addView(modeRow);
        rgMode.setOnCheckedChangeListener((g, checkedId) -> {
            boolean isDays = (checkedId == rbDays.getId());
            etDays.setVisibility(isDays ? View.VISIBLE : View.GONE);
            etDate.setVisibility(isDays ? View.GONE : View.VISIBLE);
        });

        etDays = input(f, "365", "天数");
        etDate = new EditText(this);
        etDate.setHint("到期日期: YYYY-MM-DD");
        etDate.setTextSize(14);
        etDate.setSingleLine(true);
        etDate.setPadding(dp(12), dp(10), dp(12), dp(10));
        etDate.setBackground(roundInput());
        etDate.setVisibility(View.GONE);
        f.addView(etDate);

        Button bGen = mkBtn("生成注册码", PRIMARY, Color.WHITE, 15);
        bGen.setOnClickListener(v -> onGen(rbDays.isChecked(),
                etDays.getText().toString().trim(),
                etDate.getText().toString().trim()));
        f.addView(bGen, row(ViewGroup.LayoutParams.MATCH_PARENT, dp(48), 0));

        TextView l3 = label(f, "注册码:");
        final TextView tvCode = new TextView(this);
        tvCode.setTextSize(16);
        tvCode.setTypeface(Typeface.MONOSPACE);
        tvCode.setTextColor(GREEN);
        tvCode.setPadding(dp(12), dp(10), dp(12), dp(10));
        tvCode.setBackground(roundCard());
        f.addView(tvCode);
        Button bCopy = mkBtn("复制注册码", 0xFFE8ECF2, 0xFF555555, 14);
        bCopy.setOnClickListener(v -> {
            String c = tvCode.getText().toString().trim();
            if (!c.isEmpty() && !"-".equals(c)) {
                copy(c);
                toast("已复制");
            }
        });
        f.addView(bCopy, row(ViewGroup.LayoutParams.MATCH_PARENT, dp(44), 0));
        tvCode.setTag("code_out");
    }

    private void onGen(boolean isDays, String daysStr, String dateStr) {
        try { doGen(isDays, daysStr, dateStr); }
        catch (Exception e) { toast("生成失败: " + e.getMessage()); }
    }

    private void doGen(boolean isDays, String daysStr, String dateStr) throws Exception {
        String dev = etDev.getText().toString().trim().toUpperCase(Locale.US);
        if (dev.length() != 8 || !dev.matches("[0-9A-F]{8}")) {
            toast("设备码应为 8 位十六进制");
            return;
        }
        String exp;
        String mode;
        String daysDesc;
        if (isDays) {
            int days = Integer.parseInt(daysStr.trim());
            if (days <= 0) throw new NumberFormatException();
            exp = new SimpleDateFormat("yyMMdd", Locale.US)
                    .format(new Date(System.currentTimeMillis() + days * 86400000L));
            mode = "按天数";
            daysDesc = String.valueOf(days);
        } else {
            java.util.Date d = new SimpleDateFormat("yyyy-MM-dd", Locale.US)
                    .parse(dateStr.trim());
            if (d == null) throw new Exception("日期格式错误, 请用 YYYY-MM-DD");
            exp = new SimpleDateFormat("yyMMdd", Locale.US).format(d);
            mode = "指定日期";
            daysDesc = dateStr.trim();
        }
        String code = makeCode(dev, exp);
        // 显示
        for (int i = 0; i < content.getChildCount(); i++) {
            View c = content.getChildAt(i);
            if (c instanceof ScrollView) {
                ViewGroup g = (ViewGroup) ((ScrollView) c).getChildAt(0);
                for (int j = 0; j < g.getChildCount(); j++) {
                    View x = g.getChildAt(j);
                    if (x instanceof TextView && "code_out".equals(x.getTag())) {
                        ((TextView) x).setText(code);
                    }
                }
            }
        }
        // 入库
        String now = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                .format(new Date());
        long expMs = isDays ? System.currentTimeMillis() + Integer.parseInt(daysStr.trim()) * 86400000L
                : new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(daysDesc).getTime();
        String expFull = new SimpleDateFormat("yyMMddHHmmss", Locale.US).format(new Date(expMs));
        db.execSQL("INSERT INTO hist(ts, dev, expire, code, mode, days, exp_ts, status)"
                + " VALUES(?,?,?,?,?,?,?,?)",
                new Object[]{now, dev, exp, code, mode, daysDesc, expFull, "未启用"});
        setStatus("已生成: " + dev + " / " + exp);
        cloudPush(code, dev, expFull, "");
    }

    /** 登记注册码到云端(已存在则跳过) */
    private void cloudPush(String code, String dev, String expTs, String note) {
        bg(() -> {
            try {
                Object[] pair = cloudGet();
                JSONArray arr = ((JSONObject) pair[0]).optJSONArray("licenses");
                if (arr == null) arr = new JSONArray();
                String norm = norm(code);
                for (int i = 0; i < arr.length(); i++) {
                    if (norm(((JSONObject) arr.getJSONObject(i))
                            .optString("code", "")).equals(norm)) {
                        ui(() -> setStatus("云端已存在, 跳过登记"));
                        return;
                    }
                }
                JSONObject o = new JSONObject();
                o.put("code", code);
                o.put("device", dev);
                o.put("expire", expTs.length() >= 6 ? expTs.substring(0, 6) : "");
                o.put("status", "active");
                o.put("note", note == null ? "" : note);
                o.put("updated_at", nowSec());
                arr.put(o);
                cloudPut(new JSONObject().put("licenses", arr), (String) pair[1]);
                ui(() -> setStatus("已自动登记到云端"));
            } catch (Throwable e) {
                ui(() -> setStatus("云端登记失败(可用同步按钮补): " + e.getMessage()));
            }
        });
    }

    /** 弹窗让用户从历史列表选中一条记录登记到云端 */
    private void showPushDialog() {
        android.database.Cursor c = db.rawQuery(
                "SELECT id, code, dev, expire, status FROM hist ORDER BY id DESC", null);
        if (c.getCount() == 0) {
            c.close();
            toast("历史记录为空");
            return;
        }
        final int[] ids = new int[c.getCount()];
        final CharSequence[] labels = new CharSequence[c.getCount()];
        int i = 0;
        while (c.moveToNext()) {
            ids[i] = c.getInt(0);
            labels[i] = c.getString(1) + "  " + c.getString(2) + "  " + c.getString(3)
                    + " [" + c.getString(4) + "]";
            i++;
        }
        c.close();
        new AlertDialog.Builder(this)
                .setTitle("选择要登记到云端的记录")
                .setItems(labels, new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int which) {
                        int id = ids[which];
                        android.database.Cursor c2 = db.rawQuery(
                                "SELECT code, dev, exp_ts, note FROM hist WHERE id=?",
                                new String[]{String.valueOf(id)});
                        if (c2.moveToNext()) {
                            String code = c2.getString(0);
                            String dev = c2.getString(1);
                            String expTs = c2.getString(2);
                            String note = c2.getString(3);
                            c2.close();
                            cloudPush(code, dev, expTs, note);
                        } else {
                            c2.close();
                            toast("记录不存在");
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ================= Tab2: 解密 =================
    private void buildDecTab() {
        ScrollView sc = scroll();
        LinearLayout f = col(sc);
        cardTitle(f, "解密注册码");
        etCode = input(f, "", "粘贴注册码 XXXX-XXXX-...");
        Button bDec = mkBtn("解 密", PRIMARY, Color.WHITE, 15);
        bDec.setOnClickListener(v -> onDec());
        f.addView(bDec, row(ViewGroup.LayoutParams.MATCH_PARENT, dp(48), 0));

        TextView tvOut = new TextView(this);
        tvOut.setTextSize(14);
        tvOut.setTextColor(TEXT);
        tvOut.setPadding(dp(12), dp(12), dp(12), dp(12));
        tvOut.setBackground(roundCard());
        f.addView(tvOut);
        tvOut.setTag("dec_out");
    }

    private void onDec() {
        String code = etCode.getText().toString().trim();
        if (code.isEmpty()) return;
        String out;
        try {
            String info = decryptCode(code);
            String ver = info.substring(0, 1);
            String dev = info.substring(1, 9);
            String exp = info.substring(9, Math.min(15, info.length()));
            String now = new SimpleDateFormat("yyMMdd", Locale.US)
                    .format(new Date());
            String st = exp.compareTo(now) < 0 ? "已过期" : "有效";
            out = "版本: " + ver + "\n绑定设备: " + dev
                    + "\n到期: " + fmtExp(exp) + "\n状态: " + st;
        } catch (Exception e) {
            out = "注册码无效";
        }
        for (int i = 0; i < content.getChildCount(); i++) {
            View c = content.getChildAt(i);
            if (c instanceof ScrollView) {
                ViewGroup g = (ViewGroup) ((ScrollView) c).getChildAt(0);
                for (int j = 0; j < g.getChildCount(); j++) {
                    View x = g.getChildAt(j);
                    if (x instanceof TextView && "dec_out".equals(x.getTag())) {
                        ((TextView) x).setText(out);
                    }
                }
            }
        }
    }

    // ================= Tab3: 历史记录 =================
    private void buildHistTab() {
        ScrollView sc = scroll();
        LinearLayout f = col(sc);
        cardTitle(f, "历史记录 (点条目: 复制 | 长按: 删除; 操作按钮: 备注/详情/续期)");
        histList = new LinearLayout(this);
        histList.setOrientation(LinearLayout.VERTICAL);
        f.addView(histList);
        Button bSync = mkBtn("一键同步 (云端为主)", PRIMARY, Color.WHITE, 14);
        bSync.setOnClickListener(v -> onSync());
        f.addView(bSync, row(ViewGroup.LayoutParams.MATCH_PARENT, dp(48), 0));
        Button bPush = mkBtn("登记选中到云端", 0xFF1A8C4A, Color.WHITE, 14);
        bPush.setOnClickListener(v -> showPushDialog());
        f.addView(bPush, row(ViewGroup.LayoutParams.MATCH_PARENT, dp(48), 0));
        loadHist();
    }

    private void loadHist() {
        if (histList == null) return;
        histList.removeAllViews();
        android.database.Cursor c = db.rawQuery(
                "SELECT id, ts, dev, expire, code, days, exp_ts, status, note"
                        + " FROM hist ORDER BY id DESC", null);
        int n = 0;
        while (c.moveToNext()) {
            final int id = c.getInt(0);
            String dev = c.getString(2);
            String expire = c.getString(3);
            final String code = c.getString(4);
            String daysStr = c.getString(5);
            final String note = c.getString(8);
            String st = c.getString(7);
            final String expTs = c.getString(6);
            LinearLayout row = cardRow();

            // 主内容行
            LinearLayout mainRow = new LinearLayout(this);
            mainRow.setOrientation(LinearLayout.HORIZONTAL);
            mainRow.setGravity(Gravity.CENTER_VERTICAL);
            mainRow.setPadding(dp(0), dp(6), dp(0), dp(6));
            TextView tv = new TextView(this);
            tv.setText(String.format(Locale.US, "#%d  %s\n设备 %s | 到期 %s | %s",
                    id, code, dev, expire, st));
            tv.setTextSize(13);
            tv.setTextColor(TEXT);
            tv.setLayoutParams(row(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            mainRow.addView(tv);
            row.addView(mainRow, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));

            // 操作按钮行
            LinearLayout btnRow = new LinearLayout(this);
            btnRow.setOrientation(LinearLayout.HORIZONTAL);
            btnRow.setPadding(dp(4), dp(2), dp(0), dp(6));
            Button bNote = mkBtn("备注", 0xFFE8ECF2, 0xFF555555, 11);
            Button bDetail = mkBtn("详情", 0xFFE8ECF2, 0xFF555555, 11);
            Button bRenew = mkBtn("续期", 0xFFE8ECF2, 0xFF555555, 11);
            btnRow.addView(bNote);
            btnRow.addView(bDetail);
            btnRow.addView(bRenew);
            row.addView(btnRow, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            row.setPadding(dp(12), dp(8), dp(12), dp(8));

            row.setOnClickListener(v -> copy(code));
            row.setOnLongClickListener(v -> { delHist(id, code); return true; });

            bNote.setOnClickListener(v -> onHistNote(id, code, note != null ? note : ""));
            bDetail.setOnClickListener(v -> onHistDetail(id, code, dev, expire,
                    daysStr != null ? daysStr + "天" : "", expTs, note));
            bRenew.setOnClickListener(v -> onHistRenew(id, code, expTs));

            histList.addView(row);
            n++;
        }
        c.close();
        if (n == 0) {
            TextView t = new TextView(this);
            t.setText("暂无记录");
            t.setTextColor(TEXT_SUB);
            t.setPadding(dp(4), dp(8), 0, 0);
            histList.addView(t);
        }
    }

    /** 编辑备注 */
    private void onHistNote(final int id, final String code, final String curNote) {
        final EditText in = new EditText(this);
        in.setHint("客户名/微信号等备注信息");
        in.setText(curNote);
        in.setSingleLine(true);
        in.setPadding(dp(12), dp(8), dp(12), dp(8));
        new AlertDialog.Builder(this)
                .setTitle("备注 #" + id + " " + code)
                .setView(in)
                .setPositiveButton("保存", (d, w) -> {
                    String note = in.getText().toString().trim();
                    db.execSQL("UPDATE hist SET note=? WHERE id=?",
                            new Object[]{note, id});
                    toast("备注已保存");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 查看详情弹窗 */
    private void onHistDetail(int id, String code, String dev,
                               String expire, String days, String expTs, String note) {
        boolean expired = expTs != null && !expTs.isEmpty()
                && expTs.compareTo(new SimpleDateFormat("yyMMddHHmmss", Locale.US)
                        .format(new Date())) < 0;
        String status = expired ? "已过期" : "有效";
        String body = String.format(Locale.US,
                "记录 #%d\n注册码 %s\n设备码 %s\n到期日 %s\n时长 %s\n状态 %s\n备注 %s",
                id, code, dev, expire, days, status,
                note == null ? "(无)" : note);
        new AlertDialog.Builder(this)
                .setTitle("详情")
                .setMessage(body)
                .setPositiveButton("关闭", null)
                .show();
    }

    /** 续期弹窗(手动输入天数) */
    private void onHistRenew(final int id, final String code, final String expTs) {
        final EditText in = new EditText(this);
        in.setHint("续期天数, 如 30");
        in.setInputType(InputType.TYPE_CLASS_NUMBER);
        in.setSingleLine(true);
        in.setPadding(dp(12), dp(8), dp(12), dp(8));
        new AlertDialog.Builder(this)
                .setTitle("续期 #" + id + " " + code)
                .setMessage("在原到期日基础上顺延")
                .setView(in)
                .setPositiveButton("确定", (d, w) -> {
                    String t = in.getText().toString().trim();
                    int days;
                    try {
                        days = Integer.parseInt(t);
                        if (days <= 0) throw new NumberFormatException();
                    } catch (Exception e) {
                        toast("请输入有效天数");
                        return;
                    }
                    doRenew(id, code, expTs, days);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 执行续期(云端 + 本地) */
    private void doRenew(final int id, final String code,
                          final String expTs, final int days) {
        setStatus("续期中...");
        bg(() -> {
            try {
                Object[] pair = cloudGet();
                JSONArray arr = ((JSONObject) pair[0]).optJSONArray("licenses");
                boolean found = false;
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject o = arr.getJSONObject(i);
                        if (norm(o.optString("code", "")).equals(norm(code))) {
                            String exp = o.optString("expire", "");
                            int yy = Integer.parseInt(exp.substring(0, 2));
                            int mm = Integer.parseInt(exp.substring(2, 4));
                            int dd = Integer.parseInt(exp.substring(4, 6));
                            java.util.Calendar c = java.util.Calendar.getInstance();
                            c.clear();
                            c.set(2000 + yy, mm - 1, dd);
                            c.add(java.util.Calendar.DAY_OF_MONTH, days);
                            String ne = new SimpleDateFormat("yyMMdd", Locale.US)
                                    .format(c.getTime());
                            o.put("expire", ne);
                            o.put("status", "active");
                            o.put("updated_at", nowSec());
                            found = true;
                            break;
                        }
                    }
                    if (found)
                        cloudPut(new JSONObject().put("licenses", arr), (String) pair[1]);
                }
                // 更新本地
                java.util.Calendar c = java.util.Calendar.getInstance();
                c.clear();
                c.set(2000, 0, 1);
                if (expTs != null && expTs.length() >= 6) {
                    int yy = Integer.parseInt(expTs.substring(0, 2));
                    int mm = Integer.parseInt(expTs.substring(2, 4));
                    int dd = Integer.parseInt(expTs.substring(4, 6));
                    c.set(2000 + yy, mm - 1, dd);
                }
                c.add(java.util.Calendar.DAY_OF_MONTH, days);
                String ne = new SimpleDateFormat("yyMMddHHmmss", Locale.US)
                        .format(c.getTime());
                String expShow = new SimpleDateFormat("yyMMdd", Locale.US)
                        .format(c.getTime());
                db.execSQL("UPDATE hist SET exp_ts=?, expire=? WHERE id=?",
                        new Object[]{ne, fmtExp(expShow), id});
                final String nd = expShow;
                ui(() -> {
                    setStatus("续期完成: 到期 " + nd + " (" + days + "天)");
                    loadHist();
                    toast("续期成功");
                });
            } catch (Throwable e) {
                ui(() -> setStatus("续期失败: " + e.getMessage()));
            }
        });
    }

    /** 删除历史记录并联动删除云端 */
    private void delHist(int id, String code) {
        db.execSQL("DELETE FROM hist WHERE id=?", new Object[]{id});
        loadHist();
        toast("本地已删除");
        bg(() -> {
            try {
                Object[] pair = cloudGet();
                JSONArray arr = ((JSONObject) pair[0]).optJSONArray("licenses");
                JSONArray remain = new JSONArray();
                String norm = norm(code);
                boolean changed = false;
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject o = arr.getJSONObject(i);
                        if (norm(o.optString("code", "")).equals(norm)) {
                            changed = true;
                            continue;
                        }
                        remain.put(o);
                    }
                }
                if (changed) {
                    cloudPut(new JSONObject().put("licenses", remain),
                            (String) pair[1]);
                    ui(() -> setStatus("本地及云端记录均已删除"));
                } else {
                    ui(() -> setStatus("云端无此记录, 仅删除本地"));
                }
            } catch (Throwable e) {
                ui(() -> setStatus("云端删除失败: " + e.getMessage()));
            }
        });
    }

    /**
     * 一键同步(双向合并):
     * 1) 下载云端数据, 本机没有的记录插入本地历史
     * 2) 本机有而云端没有的新增记录上传合并到云端
     * 3) 云端已停用(blocked)的, 本地标"已作废"
     * 结果: 两边记录并集一致
     */
    private void onSync() {
        setStatus("同步中...");
        bg(() -> {
            try {
                Object[] pair = cloudGet();
                JSONArray arr = ((JSONObject) pair[0]).optJSONArray("licenses");
                if (arr == null) arr = new JSONArray();

                // 本地已有码集合(规范化)
                Set<String> localNorm = new HashSet<>();
                android.database.Cursor c = db.rawQuery(
                        "SELECT code FROM hist", null);
                while (c.moveToNext())
                    localNorm.add(norm(c.getString(0)));
                c.close();

                // 1) 云端 -> 本地(下载) + 收集 blocked
                List<Integer> blockedIds = new ArrayList<>();
                int downloaded = 0;
                SimpleDateFormat df = new SimpleDateFormat(
                        "yyyy-MM-dd HH:mm:ss", Locale.US);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    String code = o.optString("code", "");
                    String n = norm(code);
                    if (n.isEmpty() || localNorm.contains(n)) {
                        // 云端已有: 若 blocked 找本地 id 标作废
                        if ("blocked".equals(o.optString("status", ""))) {
                            android.database.Cursor bc = db.rawQuery(
                                    "SELECT id FROM hist WHERE code=?",
                                    new String[]{code});
                            while (bc.moveToNext()) blockedIds.add(bc.getInt(0));
                            bc.close();
                        }
                        continue;
                    }
                    // 插入本地历史(下载)
                    String exp6 = o.optString("expire", "");
                    String expFull = exp6.length() == 6 ? exp6 + "235959" : "";
                    String expShow = exp6.length() == 6 ? fmtExp(exp6) : "";
                    db.execSQL("INSERT INTO hist(ts, dev, expire, code, mode,"
                                    + " days, exp_ts, status, note)"
                                    + " VALUES(?,?,?,?,?,?,?,?,?)",
                            new Object[]{df.format(new Date()),
                                    o.optString("device", ""), expShow, code,
                                    "云端下载", "", expFull,
                                    "blocked".equals(o.optString("status", ""))
                                            ? "已作废" : "云端",
                                    o.optString("note", "")});
                    localNorm.add(n);
                    downloaded++;
                }

                // 2) 本地 -> 云端(上传本机新增)
                int uploaded = 0;
                android.database.Cursor c2 = db.rawQuery(
                        "SELECT id, code, dev, exp_ts, note, status FROM hist", null);
                Set<String> cloudNorm = new HashSet<>();
                for (int i = 0; i < arr.length(); i++)
                    cloudNorm.add(norm(((JSONObject) arr.getJSONObject(i))
                            .optString("code", "")));
                while (c2.moveToNext()) {
                    String code = c2.getString(1);
                    String n = norm(code);
                    if (n.isEmpty() || cloudNorm.contains(n)) continue;
                    JSONObject o = new JSONObject();
                    o.put("code", code);
                    o.put("device", c2.getString(2));
                    String et = c2.getString(3);
                    o.put("expire", et != null && et.length() >= 6
                            ? et.substring(0, 6) : "");
                    o.put("status", "active");
                    o.put("note", c2.getString(4) == null ? "" : c2.getString(4));
                    o.put("updated_at", df.format(new Date()));
                    arr.put(o);
                    uploaded++;
                }
                c2.close();
                if (uploaded > 0)
                    cloudPut(new JSONObject().put("licenses", arr),
                            (String) pair[1]);

                // 3) 云端 blocked -> 本地标已作废
                for (int id : blockedIds)
                    db.execSQL("UPDATE hist SET status='已作废' WHERE id=?",
                            new Object[]{id});

                final int fd = downloaded, fu = uploaded, fb = blockedIds.size();
                ui(() -> {
                    setStatus(String.format(Locale.US,
                            "同步完成: 下载 %d 条, 上传 %d 条, 标作废 %d 条",
                            fd, fu, fb));
                    loadHist();
                    toast("同步完成");
                });
            } catch (Throwable e) {
                ui(() -> setStatus("同步失败: " + e.getMessage()));
            }
        });
    }

    // ================= Tab4: 云端管理(多选批量) =================
    private void buildCloudTab() {
        ScrollView sc = scroll();
        LinearLayout f = col(sc);
        cardTitle(f, "管理员 PAT");

        LinearLayout pr = new LinearLayout(this);
        pr.setOrientation(LinearLayout.HORIZONTAL);
        pr.setGravity(Gravity.CENTER_VERTICAL);
        etPat = new EditText(this);
        etPat.setText(pat());
        etPat.setTextSize(13);
        etPat.setSingleLine(true);
        etPat.setHint("GitHub PAT (长按可粘贴)");
        etPat.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        etPat.setPadding(dp(12), dp(8), dp(12), dp(8));
        etPat.setBackground(roundInput());
        etPat.setLayoutParams(new LinearLayout.LayoutParams(0, dp(44), 1f));
        pr.addView(etPat);
        Button bSave = mkBtn("保存", 0xFFE8ECF2, 0xFF555555, 13);
        LinearLayout.LayoutParams lsb = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(44));
        lsb.leftMargin = dp(8);
        bSave.setLayoutParams(lsb);
        bSave.setOnClickListener(v -> {
            savePat(etPat.getText().toString().trim());
            toast("PAT 已保存");
        });
        pr.addView(bSave);
        f.addView(pr);

        cardTitle(f, "云端激活记录 (点条目多选, 批量操作)");
        cloudList = new LinearLayout(this);
        cloudList.setOrientation(LinearLayout.VERTICAL);
        f.addView(cloudList);

        // 两行按钮
        LinearLayout bf1 = new LinearLayout(this);
        bf1.setOrientation(LinearLayout.HORIZONTAL);
        Button b1 = mkBtn("刷新", PRIMARY, Color.WHITE, 13);
        b1.setOnClickListener(v -> loadCloud());
        Button b2 = mkBtn("全选/取消", 0xFFE8ECF2, 0xFF555555, 13);
        b2.setOnClickListener(v -> toggleAll());
        Button b5 = mkBtn("删除选中", RED, Color.WHITE, 13);
        b5.setOnClickListener(v -> cloudDel());
        bf1.addView(b1); bf1.addView(b2); bf1.addView(b5);
        f.addView(bf1);

        LinearLayout bf2 = new LinearLayout(this);
        bf2.setOrientation(LinearLayout.HORIZONTAL);
        Button b3 = mkBtn("批量停用", 0xFFE8ECF2, 0xFF555555, 13);
        b3.setOnClickListener(v -> cloudToggle("blocked"));
        Button b4 = mkBtn("批量启用", 0xFFE8ECF2, 0xFF555555, 13);
        b4.setOnClickListener(v -> cloudToggle("active"));
        Button b6 = mkBtn("批量续期", 0xFFE8ECF2, 0xFF555555, 13);
        b6.setOnClickListener(v -> cloudRenew());
        Button b7 = mkBtn("解绑账号", 0xFFB26A00, Color.WHITE, 13);
        b7.setOnClickListener(v -> cloudUnbind());
        bf2.addView(b3); bf2.addView(b4); bf2.addView(b6); bf2.addView(b7);
        f.addView(bf2);

        TextView tip = label(f, "续期可手动输入天数; 停用后 APP 将无法激活");
        tip.setTextColor(TEXT_SUB);
        loadCloud();
    }

    private void toggleAll() {
        if (cloudAllCodes.isEmpty()) return;
        if (selCodes.size() >= cloudAllCodes.size()) selCodes.clear();
        else selCodes.addAll(cloudAllCodes);
        refreshCloudSelection();
    }

    private final Set<String> cloudAllCodes = new HashSet<>();

    private void loadCloud() {
        if (cloudList == null) return;
        cloudList.removeAllViews();
        if (pat().isEmpty()) {
            TextView t = new TextView(this);
            t.setText("请先填写并保存 PAT");
            t.setTextColor(RED);
            t.setPadding(dp(4), dp(8), 0, 0);
            cloudList.addView(t);
            return;
        }
        setStatus("云端加载中...");
        bg(() -> {
            try {
                Object[] pair = cloudGet();
                JSONArray arr = ((JSONObject) pair[0]).optJSONArray("licenses");
                List<JSONObject> list = new ArrayList<>();
                if (arr != null)
                    for (int i = 0; i < arr.length(); i++)
                        list.add(arr.getJSONObject(i));
                ui(() -> renderCloud(list));
            } catch (Throwable e) {
                ui(() -> setStatus("云端加载失败: " + e.getMessage()));
            }
        });
    }

    private void renderCloud(List<JSONObject> list) {
        cloudList.removeAllViews();
        cloudAllCodes.clear();
        int bound = 0;
        for (JSONObject o : list) {
            final String code = o.optString("code", "");
            cloudAllCodes.add(norm(code));
            String st = o.optString("status", "");
            String acc = o.optString("account", "");
            if (!acc.isEmpty()) bound++;
            LinearLayout row = cardRow();
            TextView tv = new TextView(this);
            String exp = o.optString("expire", "");
            String line2 = "设备 " + o.optString("device", "")
                    + " | 到期 " + (exp.length() == 6 ? fmtExp(exp) : "-")
                    + " | " + st;
            if (!acc.isEmpty()) {
                line2 += "\n账号 " + acc + " | 密码 " + o.optString("pwd", "");
            }
            tv.setText(code + "\n" + line2);
            tv.setTextSize(12);
            tv.setTextColor("blocked".equals(st) ? RED : GREEN);
            row.addView(tv, row(0, -2, 1));
            row.setOnClickListener(v -> {
                String n = norm(code);
                if (selCodes.contains(n)) selCodes.remove(n);
                else selCodes.add(n);
                refreshCloudSelection();
            });
            cloudList.addView(row);
        }
        selCodes.retainAll(cloudAllCodes);   // 清掉已不存在的
        refreshCloudSelection();
        setStatus("共 " + list.size() + " 条, 已绑定账号 " + bound + " 条");
    }

    /** 解绑: 清除选中记录的 account/pwd, 该码可重新绑定任意账号 */
    private void cloudUnbind() {
        if (pat().isEmpty()) { toast("请先保存 PAT"); return; }
        if (selCodes.isEmpty()) { toast("先点选云端记录"); return; }
        final Set<String> targets = new HashSet<>(selCodes);
        setStatus("解绑中 (" + targets.size() + " 条)...");
        bg(() -> {
            try {
                Object[] pair = cloudGet();
                JSONArray arr = ((JSONObject) pair[0]).optJSONArray("licenses");
                int hit = 0;
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    if (targets.contains(norm(o.optString("code", "")))
                            && o.has("account")) {
                        o.remove("account");
                        o.remove("pwd");
                        o.put("updated_at", nowSec());
                        hit++;
                    }
                }
                if (hit == 0) {
                    ui(() -> toast("选中的记录均未绑定账号"));
                    return;
                }
                cloudPut(new JSONObject().put("licenses", arr), (String) pair[1]);
                selCodes.clear();
                final int fh = hit;
                ui(() -> {
                    toast("已解绑 " + fh + " 条");
                    loadCloud();
                });
            } catch (Throwable e) {
                ui(() -> setStatus("解绑失败: " + e.getMessage()));
            }
        });
    }

    /** 按选中集重刷高亮和状态栏 */
    private void refreshCloudSelection() {
        int shown = 0;
        for (int i = 0; i < cloudList.getChildCount(); i++) {
            View row = cloudList.getChildAt(i);
            if (!(row instanceof LinearLayout)) continue;
            TextView tv = (TextView) ((LinearLayout) row).getChildAt(0);
            String c = tv.getText().toString().split("\n")[0];
            boolean sel = selCodes.contains(norm(c));
            row.setBackground(roundCard(sel ? SEL_BG : CARD));
            shown++;
        }
        setStatus("共 " + shown + " 条 | 已选 " + selCodes.size() + " 条");
    }

    /** 批量变更: 对所有选中项应用 mutator(返回 false 删除), 一次 PUT */
    private void cloudMutateMulti(Mutator m) {
        if (pat().isEmpty()) { toast("请先保存 PAT"); return; }
        if (selCodes.isEmpty()) { toast("先点选云端记录"); return; }
        final Set<String> targets = new HashSet<>(selCodes);
        setStatus("操作中 (" + targets.size() + " 条)...");
        bg(() -> {
            try {
                Object[] pair = cloudGet();
                JSONArray arr = ((JSONObject) pair[0]).optJSONArray("licenses");
                JSONArray out = new JSONArray();
                int hit = 0, del = 0;
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    if (targets.contains(norm(o.optString("code", "")))) {
                        hit++;
                        if (m.apply(o)) out.put(o);
                        else del++;
                        continue;
                    }
                    out.put(o);
                }
                if (hit == 0) {
                    ui(() -> { toast("未找到选中的记录"); });
                    return;
                }
                cloudPut(new JSONObject().put("licenses", out), (String) pair[1]);
                selCodes.clear();
                final int fh = hit, fd = del;
                ui(() -> {
                    toast("已处理 " + fh + " 条" + (fd > 0 ? ", 删除 " + fd : ""));
                    loadCloud();
                });
            } catch (Throwable e) {
                ui(() -> setStatus("操作失败: " + e.getMessage()));
            }
        });
    }

    private interface Mutator { boolean apply(JSONObject o) throws Exception; }

    private void cloudToggle(String st) {
        cloudMutateMulti(o -> {
            o.put("status", st);
            o.put("updated_at", nowSec());
            return true;
        });
    }

    /** 批量续期: 弹窗手动输入天数 */
    private void cloudRenew() {
        if (selCodes.isEmpty()) { toast("先点选云端记录"); return; }
        final EditText in = new EditText(this);
        in.setHint("续期天数, 如 30");
        in.setInputType(InputType.TYPE_CLASS_NUMBER);
        in.setSingleLine(true);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        in.setPadding(pad, pad / 2, pad, pad / 2);
        new AlertDialog.Builder(this)
                .setTitle("批量续期 (" + selCodes.size() + " 条)")
                .setMessage("输入要增加的有效期天数, 在原到期日基础上顺延")
                .setView(in)
                .setPositiveButton("确定", (d, w) -> {
                    String t = in.getText().toString().trim();
                    int days;
                    try {
                        days = Integer.parseInt(t);
                        if (days <= 0) throw new NumberFormatException();
                    } catch (Exception e) {
                        toast("请输入有效天数");
                        return;
                    }
                    doRenew(days);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void doRenew(int days) {
        cloudMutateMulti(o -> {
            String exp = o.optString("expire", "");
            int yy = Integer.parseInt(exp.substring(0, 2));
            int mm = Integer.parseInt(exp.substring(2, 4));
            int dd = Integer.parseInt(exp.substring(4, 6));
            java.util.Calendar c = java.util.Calendar.getInstance();
            c.clear();
            c.set(2000 + yy, mm - 1, dd);
            c.add(java.util.Calendar.DAY_OF_MONTH, days);
            String ne = new SimpleDateFormat("yyMMdd", Locale.US).format(c.getTime());
            o.put("expire", ne);
            o.put("status", "active");
            o.put("updated_at", nowSec());
            return true;
        });
    }

    private void cloudDel() {
        cloudMutateMulti(o -> false);
    }

    // ================= 云端读写 =================
    /** GET licenses.json: [0]=数据(JSONObject) [1]=blob sha(String) */
    private Object[] cloudGet() throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(CLOUD_API).openConnection();
        c.setRequestProperty("Authorization", "Bearer " + pat());
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setConnectTimeout(10000);
        c.setReadTimeout(10000);
        int code = c.getResponseCode();
        String body = readAll(code >= 400 ? c.getErrorStream() : c.getInputStream());
        if (code >= 400) {
            if (code == 401)
                throw new RuntimeException("PAT 无效或已过期, 请重新粘贴并保存");
            throw new RuntimeException("HTTP " + code + " " + body.substring(0,
                    Math.min(120, body.length())));
        }
        JSONObject info = new JSONObject(body);
        String raw = new String(android.util.Base64.decode(
                info.getString("content"), android.util.Base64.DEFAULT),
                StandardCharsets.UTF_8);
        return new Object[]{new JSONObject(raw), info.optString("sha", "")};
    }

    /** PUT licenses.json */
    private void cloudPut(JSONObject data, String sha) throws Exception {
        JSONObject body = new JSONObject();
        body.put("message", "update licenses.json");
        body.put("content", android.util.Base64.encodeToString(
                data.toString().getBytes(StandardCharsets.UTF_8),
                android.util.Base64.NO_WRAP));
        if (sha != null && !sha.isEmpty()) body.put("sha", sha);
        HttpURLConnection c = (HttpURLConnection) new URL(CLOUD_API).openConnection();
        c.setRequestMethod("PUT");
        c.setRequestProperty("Authorization", "Bearer " + pat());
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setDoOutput(true);
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        c.getOutputStream().write(body.toString().getBytes(StandardCharsets.UTF_8));
        int code = c.getResponseCode();
        if (code >= 300) {
            String err = readAll(c.getErrorStream());
            if (code == 401)
                throw new RuntimeException("PAT 无效或已过期, 请重新粘贴并保存");
            throw new RuntimeException("HTTP " + code + " " + err.substring(0,
                    Math.min(120, err.length())));
        }
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        return new String(bo.toByteArray(), StandardCharsets.UTF_8);
    }

    // ================= 注册码算法(与 keygen_license.py 一致) =================
    private static String b32encode(byte[] data) {
        int bitbuf = 0, bits = 0;
        StringBuilder out = new StringBuilder();
        for (byte b : data) {
            bitbuf = (bitbuf << 8) | (b & 0xFF);
            bits += 8;
            while (bits >= 5) {
                out.append(B32.charAt((bitbuf >> (bits - 5)) & 31));
                bits -= 5;
            }
        }
        if (bits > 0) out.append(B32.charAt((bitbuf << (5 - bits)) & 31));
        return out.toString();
    }

    private static byte[] b32decode(String s) {
        int bitbuf = 0, bits = 0;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (char ch : s.toUpperCase(Locale.US).toCharArray()) {
            int v = B32.indexOf(ch);
            if (v < 0) throw new IllegalArgumentException("bad char " + ch);
            bitbuf = (bitbuf << 5) | v;
            bits += 5;
            if (bits >= 8) {
                out.write((bitbuf >> (bits - 8)) & 0xFF);
                bits -= 8;
            }
        }
        return out.toByteArray();
    }

    private static byte[] aes(byte[] data, int mode) throws Exception {
        javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/ECB/NoPadding");
        cipher.init(mode, new SecretKeySpec(KEY.getBytes(StandardCharsets.UTF_8), "AES"));
        return cipher.doFinal(data);
    }

    private static byte[] pkcs5(byte[] b) {
        int n = 16 - b.length % 16;
        byte[] out = new byte[b.length + n];
        System.arraycopy(b, 0, out, 0, b.length);
        for (int i = b.length; i < out.length; i++) out[i] = (byte) n;
        return out;
    }

    /** 明文 "1"+设备码(8)+到期yyMMdd(6)=15字节, 单 AES 块 */
    private static String makeCode(String dev, String exp) throws Exception {
        byte[] plain = ("1" + dev + exp).getBytes(StandardCharsets.UTF_8);
        String raw = b32encode(aes(pkcs5(plain), Cipher.ENCRYPT_MODE));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < raw.length(); i += 4) {
            if (i > 0) sb.append('-');
            sb.append(raw, i, Math.min(i + 4, raw.length()));
        }
        return sb.toString();
    }

    private static String decryptCode(String code) throws Exception {
        String s = code.replace("-", "").replace(" ", "").toUpperCase(Locale.US);
        byte[] d = aes(b32decode(s), Cipher.DECRYPT_MODE);
        int pad = d[d.length - 1];
        int len = d.length - pad;
        return new String(d, 0, len, StandardCharsets.UTF_8);
    }

    // ================= 工具 =================
    private static String norm(String s) {
        return s == null ? "" : s.replace("-", "").replace(" ", "")
                .toUpperCase(Locale.US);
    }

    private static String fmtExp(String exp) {
        return "20" + exp.substring(0, 2) + "-" + exp.substring(2, 4)
                + "-" + exp.substring(4, 6);
    }

    private static String nowSec() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                .format(new Date());
    }

    private void copy(String s) {
        android.content.ClipboardManager cm = (android.content.ClipboardManager)
                getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(android.content.ClipData.newPlainText("code", s));
    }

    private String pat() {
        return getSharedPreferences("cfg", MODE_PRIVATE)
                .getString("admin_pat", "");
    }

    private void savePat(String t) {
        getSharedPreferences("cfg", MODE_PRIVATE).edit()
                .putString("admin_pat", t).apply();
    }

    private interface UiJob { void run(); }

    private void bg(final Runnable r) {
        new Thread(() -> {
            try {
                r.run();
            } catch (Throwable e) {
                ui(() -> setStatus("错误: " + e.getMessage()));
            }
        }).start();
    }

    private void ui(UiJob j) {
        runOnUiThread(j::run);
    }

    // ================= UI 构建 =================
    private ScrollView scroll() {
        ScrollView sc = new ScrollView(this);
        sc.setFillViewport(true);
        return sc;
    }

    private LinearLayout col(ScrollView parent) {
        LinearLayout f = new LinearLayout(this);
        f.setOrientation(LinearLayout.VERTICAL);
        f.setPadding(dp(12), dp(12), dp(12), dp(12));
        parent.addView(f, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(parent, row(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        return f;
    }

    private void cardTitle(LinearLayout parent, String title) {
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(15);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(PRIMARY_DARK);
        t.setPadding(dp(2), dp(10), 0, dp(6));
        parent.addView(t);
    }

    /** 圆角卡片背景(带细描边) */
    private GradientDrawable roundCard(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(10));
        g.setStroke(dp(1), 0xFFE3E8F0);
        return g;
    }

    private GradientDrawable roundCard() { return roundCard(CARD); }

    private GradientDrawable roundInput() {
        GradientDrawable g = new GradientDrawable();
        g.setColor(CARD);
        g.setCornerRadius(dp(10));
        g.setStroke(dp(1), 0xFFD5DCE5);
        return g;
    }

    private GradientDrawable roundBtn(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(8));
        return g;
    }

    /** 历史记录条目卡片 */
    private LinearLayout cardRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        row.setBackground(roundCard());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        row.setLayoutParams(lp);
        return row;
    }

    private TextView label(LinearLayout parent, String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(13);
        t.setTextColor(TEXT_SUB);
        t.setPadding(dp(2), dp(10), 0, dp(4));
        parent.addView(t);
        return t;
    }

    private EditText input(LinearLayout parent, String text, String hint) {
        EditText e = new EditText(this);
        e.setText(text);
        e.setTextSize(14);
        e.setSingleLine(true);
        e.setHint(hint);
        e.setPadding(dp(12), dp(10), dp(12), dp(10));
        e.setBackground(roundInput());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        lp.topMargin = dp(4);
        parent.addView(e, lp);
        return e;
    }

    private Button mkBtn(String text, int color, int fg, float sizeSp) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(sizeSp);
        b.setTextColor(fg);
        b.setBackground(roundBtn(color));
        b.setAllCaps(false);
        b.setStateListAnimator(null);
        b.setMinHeight(0);
        b.setMinWidth(0);
        return b;
    }

    private static LinearLayout.LayoutParams row(int w, int h, float weight) {
        return new LinearLayout.LayoutParams(w, h, weight);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    // ================= 历史库 =================
    private static class Db extends SQLiteOpenHelper {
        Db(Context c) { super(c, "admin_hist.db", null, 2); }

        @Override public void onCreate(SQLiteDatabase d) {
            d.execSQL("CREATE TABLE hist(id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "ts TEXT, dev TEXT, expire TEXT, code TEXT, mode TEXT,"
                    + "days TEXT, exp_ts TEXT, status TEXT, note TEXT)");
        }

        @Override public void onUpgrade(SQLiteDatabase d, int oldVer, int newVer) {
            if (oldVer < 2)
                d.execSQL("ALTER TABLE hist ADD COLUMN note TEXT");
        }
    }
}
