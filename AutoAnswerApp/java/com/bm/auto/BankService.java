package com.bm.auto;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 彬煤智慧课堂自动答题无障碍服务
 * 页面特征(实测): 题干 View 无 viewId(取页面上最长的 TextView 文本);
 *                选项 RadioButton 的 viewId 为 "题号|选项号"("3|2") 且与选项文本同行;
 *                翻页按钮文本 "上一题"/"下一题"; 顶部无文本 Button 为交卷入口; 弹窗含"确定/确认交卷/结束考试"。
 */
public class BankService extends AccessibilityService {

    private static final String TAG = "BankSvc";
    private static final String PKG = "smzg.Coal.Exam";
    private static final Pattern P_RADIO = Pattern.compile("\\d+\\|\\d+");
    private static final Pattern P_STEM = Pattern.compile("\\d+");
    private static final Pattern P_LEADING_NUM = Pattern.compile("^\\d+[.、．]");
    private static final Pattern P_OPT_PREFIX = Pattern.compile("^[A-Za-z][.、．:：]?");
    private static final Pattern P_QNUM = Pattern.compile("^(\\d+)");

    // ---- 对外状态/控制 (MainActivity、悬浮窗直接读写) ----
    /** 当前唯一有效实例: MIUI 重绑/重装可能残留多个 Service 实例, 旧实例必须自我休眠 */
    private static volatile BankService self = null;
    public static volatile boolean alive = false;
    public static volatile boolean paused = false;
    public static volatile boolean forceSubmit = false;
    public static volatile int qNum = 0;
    public static volatile int answered = 0;
    public static volatile int hits = 0;
    public static volatile String status = "待机";
    public static volatile boolean forceUpdate = false;
    public static volatile String updateInfo = "未更新";

    public static void resetStats() {
        qNum = 0; answered = 0; hits = 0; status = "待机";
    }

    private SQLiteDatabase bank;
    /** 内存题库索引: 精确题干 / 去标点题干 -> 答案记录, 兜住屏幕文本标点差异 */
    private final java.util.Map<String, String[]> memExact = new java.util.HashMap<>();
    private final java.util.Map<String, String[]> memNorm = new java.util.HashMap<>();
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Random rnd = new Random();
    private boolean busy = false;
    private String lastStem = "";
    private int sameCount = 0;
    private long lastDbg = 0;
    private AccessibilityNodeInfo clickedNode;

    // ---- 悬浮窗(静态单例: 服务被系统重绑时不产生重叠面板) ----
    private static WindowManager wm;
    private static LinearLayout panel;
    private static TextView tvPanel;
    private static Button bPausePanel;

    static class Item {
        AccessibilityNodeInfo n;
        Rect b = new Rect();
        String t = "";
        String id = "";
        String cls = "";
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        // 成为唯一有效实例; 旧实例的回调靠 self != this 守卫自行退出
        self = this;
        alive = true;
        loadBank();
        scheduleNextUpdate(false);
        h.removeCallbacks(panelLoop);
        h.postDelayed(panelLoop, 1000);
        Toast.makeText(this, "行藏有度已就绪", Toast.LENGTH_SHORT).show();
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        // 只有当前实例解绑才影响全局状态; 被新实例顶替的旧实例不得翻状态
        if (self == this) {
            alive = false;
            self = null;
            hidePanel();
        }
        return super.onUnbind(intent);
    }

    private void loadBank() {
        try {
            File f = new File(getFilesDir(), "bank.db");
            if (!f.exists() || f.length() == 0) {
                InputStream is = getAssets().open("question_bank.db");
                FileOutputStream os = new FileOutputStream(f);
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
                os.close();
                is.close();
            }
            bank = SQLiteDatabase.openDatabase(f.getPath(), null, SQLiteDatabase.OPEN_READONLY);
            buildMemIndex();
            long cnt = -1;
            try { cnt = bank.compileStatement("SELECT COUNT(*) FROM bank").simpleQueryForLong(); } catch (Exception ignore) { }
            updateInfo = cnt >= 0 ? "在线题库 " + cnt + " 题" : "题库已加载";
            Log.i(TAG, "题库加载成功 count=" + cnt);
        } catch (Exception e) {
            bank = null;
            updateInfo = "题库加载失败";
            Log.e(TAG, "题库加载失败", e);
        }
    }

    /** 构建题干内存索引(精确 + 去标点归一化) */
    private void buildMemIndex() {
        memExact.clear();
        memNorm.clear();
        if (bank == null) return;
        Cursor c = null;
        try {
            c = bank.query("bank", new String[]{"question", "qtype", "options", "answer"},
                    null, null, null, null, null);
            while (c.moveToNext()) {
                String q = c.getString(0);
                String[] rec = new String[]{c.getString(1), c.getString(2), c.getString(3)};
                memExact.put(q, rec);
                memNorm.put(normKey(q), rec);
            }
        } catch (Exception e) {
            Log.e(TAG, "buildMemIndex", e);
        } finally {
            if (c != null) c.close();
        }
    }

    /** 去空白/标点, 只保留汉字字母数字(小写) */
    private static String normKey(String s) {
        if (s == null) return "";
        return s.replaceAll("[^\\p{L}\\p{N}]", "").toLowerCase();
    }

    // ---------- 题库定时更新(直连平台, 免登录) ----------

    /** 按配置的间隔(小时)排定下一次更新 */
    private void scheduleNextUpdate(boolean force) {
        long hours = getSharedPreferences("cfg", MODE_PRIVATE).getLong("update_hours", 24);
        long delay = hours * 3600 * 1000L;
        if (hours <= 0 && !force) return;   // 间隔<=0: 关闭定时(手动"立即更新"仍可用)
        long sinceFile = System.currentTimeMillis()
                - new File(getFilesDir(), "bank.db").lastModified();
        long wait;
        if (force) {
            wait = 1000;
        } else if (sinceFile >= delay) {
            wait = 5000;   // 距上次更新已超周期, 启动后稍等即更新
        } else {
            wait = (delay - sinceFile) + 5000;
        }
        h.removeCallbacks(updateAlarm);
        h.postDelayed(updateAlarm, wait);
        if (!force && hours > 0) {
            double hh = wait / 3600000.0;
            updateInfo = "题库距下次更新 " + (hh >= 1 ? String.format("%.1f小时", hh)
                    : Math.max(1, wait / 60000) + "分钟");
        }
    }

    private final Runnable updateAlarm = new Runnable() {
        @Override public void run() { doBankUpdate(); }
    };

    private void doBankUpdate() {
        if (self != this || BankUpdater.running) return;
        updateInfo = "题库更新中...";
        new Thread(new Runnable() {
            @Override public void run() {
                BankUpdater.Result r = BankUpdater.update(BankService.this);
                if (self != BankService.this) return;   // 已被新实例顶替
                if (r.ok) {
                    // 重开只读连接以看到新数据
                    try {
                        if (bank != null) bank.close();
                    } catch (Exception ignore) { }
                    File f = new File(getFilesDir(), "bank.db");
                    f.setLastModified(System.currentTimeMillis());
                    bank = SQLiteDatabase.openDatabase(f.getPath(), null,
                            SQLiteDatabase.OPEN_READONLY);
                    buildMemIndex();
                    updateInfo = r.message + " · 已自动更新";
                } else {
                    updateInfo = r.message;
                }
                scheduleNextUpdate(false);
            }
        }).start();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (self != this) return;   // 已被新实例顶替的僵尸实例不再处理事件
        CharSequence p = event.getPackageName();
        int t = event.getEventType();
        if (p == null || !PKG.contentEquals(p)) return;
        if (t != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && t != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return;
        if (!getSharedPreferences("cfg", MODE_PRIVATE).getBoolean("enabled", true)) return;
        h.removeCallbacks(tick);
        h.postDelayed(tick, 300);
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() { doTick(); }
    };

    /** 状态文本 */
    public static String statusText() {
        if (paused) return "已暂停";
        if (SystemClock.elapsedRealtime() < sCooldown) return "等待提交结果...";
        return status;
    }

    private static volatile long sCooldown = 0;

    /** 交卷/弹窗点击后的静默期, 防止成绩弹窗出现前误识别到旧题目; 结束后自动唤醒 */
    private void cooldown(long ms) {
        sCooldown = SystemClock.elapsedRealtime() + ms;
        h.removeCallbacks(tick);
        h.postDelayed(tick, ms + 800);
    }

    private void doTick() {
        if (self != this) return;   // 僵尸实例立即退出, 不再自续
        if (busy) return;
        if (paused) return;   // 显示层由 statusText() 处理, 不污染 status
        if (forceSubmit) {
            forceSubmit = false;
            AccessibilityNodeInfo r0 = getRootInActiveWindow();
            if (r0 != null) {
                status = "手动交卷";
                clickSubmitByPowerButton(walk(r0));
            }
            cooldown(2500);   // 短冷却: 稍后还要点确认交卷弹窗
            return;
        }
        long remain = sCooldown - SystemClock.elapsedRealtime();
        if (remain > 0) {
            status = "等待提交结果...";
            // 关键: 重新排队自唤醒, 防止冷却期内的事件把唤醒任务移除后无人点"确认交卷/结束考试"
            h.removeCallbacks(tick);
            h.postDelayed(tick, remain + 500);
            return;
        }
        if (bank == null) {
            loadBank();
            if (bank == null) { dbg("诊断: 题库不可用"); return; }
        }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) { dbg("诊断: root=null"); return; }

        final List<Item> items = walk(root);

        // 1. 确认/交卷/成绩弹窗
        Item ok = findButton(items, "确定|确认|交卷|提交|结束考试", 0, 1 << 20);
        if (ok != null) {
            log("点击: " + ok.t);
            String bt = ok.t;
            if (bt.contains("结束考试")) {
                status = "考试完成";
                cooldown(10000);
            } else if (bt.contains("交卷") || bt.contains("提交")) {
                status = "已提交, 等待成绩...";
                cooldown(8000);
            } else {
                cooldown(3000);
            }
            click(ok.n);
            return;
        }

        // 2. 答题页检测
        int radioCount = 0;
        for (Item it : items) {
            if (it.id != null && P_RADIO.matcher(it.id).matches()) radioCount++;
        }
        if (radioCount == 0 || radioCount > 8) {
            dbg("诊断: radios=" + radioCount + " nodes=" + items.size()
                    + " winPkg=" + root.getPackageName());
            return;
        }

        busy = true;
        h.postDelayed(new Runnable() {
            @Override public void run() { answer(); }
        }, answerDelay());
    }

    /** 作答前停留: 快速 0.3~0.8s, 慢速 1.5~4s */
    private long answerDelay() {
        boolean fast = getSharedPreferences("cfg", MODE_PRIVATE).getBoolean("fast", true);
        return fast ? 300 + rnd.nextInt(500) : 1500 + rnd.nextInt(2500);
    }

    private long nextDelay() {
        boolean fast = getSharedPreferences("cfg", MODE_PRIVATE).getBoolean("fast", true);
        return fast ? 250 + rnd.nextInt(350) : 800 + rnd.nextInt(800);
    }

    /** 重新读屏作答(避免翻页过渡期的过期坐标), 然后翻页 */
    private void answer() {
        try {
            if (paused) { busy = false; return; }
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) { busy = false; return; }
            List<Item> items = walk(root);
            List<Item> radios = new ArrayList<>();
            for (Item it : items) {
                if (it.id != null && P_RADIO.matcher(it.id).matches()) radios.add(it);
            }
            if (radios.isEmpty() || radios.size() > 8) { busy = false; return; }

            String stemText = findStem(items);
            if (stemText.equals(lastStem)) {
                sameCount++;
            } else {
                sameCount = 0;
                lastStem = stemText;
            }
            if (sameCount >= 2) {
                log("同一题连续出现, 尝试交卷");
                sameCount = 0;
                status = "正在交卷...";
                clickSubmitByPowerButton(items);
                cooldown(2500);   // 之后还需点确认交卷弹窗
                h.postDelayed(release, 1200);
                return;
            }

            Matcher mn = P_QNUM.matcher(stemText.trim());
            if (mn.find()) qNum = Integer.parseInt(mn.group(1));

            String q = normalizeStem(stemText);
            String[] hit = lookup(q);
            Item target = null;

            if (hit != null && "single".equals(hit[0])) {
                String optText = jsonOpt(hit[1], hit[2]);
                target = findRadioByText(radios, items, optText, false);
                log("单选命中 -> " + hit[2] + "." + optText);
            } else if (hit != null && "judge".equals(hit[0])) {
                target = findRadioByText(radios, items, null, "Y".equals(hit[2]));
                log("判断命中 -> " + hit[2]);
            } else {
                log("未命中, 随机作答: " + (q.length() > 20 ? q.substring(0, 20) : q));
                target = radios.get(rnd.nextInt(radios.size()));
            }
            if (target == null) target = radios.get(0);
            click(target.n);
            answered++;
            if (hit != null) hits++;
            status = hit != null ? "答题中" : "答题中(随机)";

            h.postDelayed(new Runnable() {
                @Override public void run() { gotoNext(); }
            }, nextDelay());
        } catch (Exception e) {
            Log.e(TAG, "answer", e);
            busy = false;
        }
    }

    private final Runnable release = new Runnable() {
        @Override public void run() { busy = false; }
    };

    private void gotoNext() {
        try {
            if (paused) return;
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return;
            List<Item> items = walk(root);
            Item submit = findButton(items, "交卷|提交", 0, 1 << 20);
            Item next = findButton(items, "下一题", 0, 1 << 20);
            if (submit != null) {
                log("点击交卷");
                status = "已提交, 等待成绩...";
                cooldown(2500);   // 之后还会弹确认交卷
                click(submit.n);
            } else if (next != null) {
                click(next.n);
            } else {
                log("未找到翻页按钮, 尝试顶部交卷入口");
                status = "正在交卷...";
                clickSubmitByPowerButton(items);
                cooldown(2500);
            }
        } catch (Exception e) {
            Log.e(TAG, "gotoNext", e);
        } finally {
            h.postDelayed(release, fast() ? 250 : 600);
        }
    }

    private boolean fast() {
        return getSharedPreferences("cfg", MODE_PRIVATE).getBoolean("fast", true);
    }

    private void clickSubmitByPowerButton(List<Item> items) {
        for (Item it : items) {
            if (it.t.isEmpty() && it.cls.endsWith("Button") && it.b.top < 400) {
                log("交卷: 命中顶部按钮 (" + it.b.centerX() + "," + it.b.centerY() + ")");
                click(it.n);
                return;
            }
        }
        // 兜底: 顶部电源键坐标固定, 直接手势点击
        log("交卷: 未匹配到顶部按钮, 坐标兜底点击");
        gesture(824, 226);
    }

    // ---------- 悬浮窗控制面板 ----------

    private final Runnable panelLoop = new Runnable() {
        @Override public void run() {
            if (self != BankService.this) return;   // 已被新实例顶替, 停止自续
            try {
                boolean want = getSharedPreferences("cfg", MODE_PRIVATE).getBoolean("float", true);
                if (want && panel == null && alive && Settings.canDrawOverlays(BankService.this)) {
                    showPanel();
                } else if (!want && panel != null) {
                    hidePanel();
                }
                if (panel != null) refreshPanel();
            } catch (Exception e) {
                Log.e(TAG, "panelLoop", e);
            }
            if (forceUpdate && !BankUpdater.running) {
                forceUpdate = false;
                doBankUpdate();
            }
            h.postDelayed(this, 1000);
        }
    };

    private void showPanel() {
        try {
            // 已存在(可能是上一个服务实例残留但仍挂载)则不重复创建
            if (panel != null) {
                if (panel.isAttachedToWindow()) return;
                panel = null;
            }
            wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            final float d = getResources().getDisplayMetrics().density;
            panel = new LinearLayout(this);
            panel.setOrientation(LinearLayout.VERTICAL);
            panel.setBackgroundColor(0xE6222444);
            int p = (int) (10 * d);
            panel.setPadding(p, p / 2, p, p / 2);

            tvPanel = new TextView(this);
            tvPanel.setTextColor(0xFFFFFFFF);
            tvPanel.setTextSize(13);
            tvPanel.setPadding(0, 0, 0, (int) (4 * d));
            panel.addView(tvPanel);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);

            bPausePanel = mkBtn("暂停", (int) (52 * d));
            bPausePanel.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    paused = !paused;
                    status = paused ? "已暂停" : "继续答题";
                }
            });
            row.addView(bPausePanel);

            Button bSub = mkBtn("交卷", (int) (46 * d));
            bSub.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { forceSubmit = true; }
            });
            row.addView(bSub);

            Button bHide = mkBtn("隐藏", (int) (46 * d));
            bHide.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    getSharedPreferences("cfg", MODE_PRIVATE).edit().putBoolean("float", false).apply();
                }
            });
            row.addView(bHide);

            panel.addView(row);

            final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT);
            // 默认贴屏幕最底部
            lp.gravity = Gravity.BOTTOM | Gravity.START;
            lp.x = (int) (12 * d);
            lp.y = (int) (20 * d);   // 距底部边距, 仅避开手势条
            wm.addView(panel, lp);

            // 整体拖动
            panel.setOnTouchListener(new View.OnTouchListener() {
                float downX, downY; int baseX, baseY;
                @Override public boolean onTouch(View v, android.view.MotionEvent e) {
                    switch (e.getActionMasked()) {
                        case android.view.MotionEvent.ACTION_DOWN:
                            downX = e.getRawX(); downY = e.getRawY();
                            baseX = lp.x; baseY = lp.y;
                            return true;
                        case android.view.MotionEvent.ACTION_MOVE:
                            // gravity=BOTTOM: y 轴向上为负, 手势下移(rawY增大)应减小 y
                            lp.x = baseX + (int) (e.getRawX() - downX);
                            lp.y = baseY - (int) (e.getRawY() - downY);
                            try { wm.updateViewLayout(panel, lp); } catch (Exception ignore) { }
                            return true;
                        default:
                            return false;
                    }
                }
            });
        } catch (Exception e) {
            panel = null;
            Log.e(TAG, "showPanel", e);
        }
    }

    private Button mkBtn(String text, int widthDpPx) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(11);
        b.setPadding(6, 0, 6, 0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setHeight((int) (34 * getResources().getDisplayMetrics().density));
        b.setWidth(widthDpPx);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = (int) (6 * getResources().getDisplayMetrics().density);
        b.setLayoutParams(lp);
        return b;
    }

    private void hidePanel() {
        try {
            if (panel != null && wm != null && panel.isAttachedToWindow()) {
                wm.removeView(panel);
            }
        } catch (Exception ignore) { }
        panel = null;
        tvPanel = null;
        bPausePanel = null;
    }

    private void refreshPanel() {
        if (tvPanel == null) return;
        tvPanel.setText("第" + qNum + "题  已答" + answered + "  命中" + hits + "\n" + statusText());
        bPausePanel.setText(paused ? "继续" : "暂停");
    }

    // ---------- 节点工具 ----------

    private List<Item> walk(AccessibilityNodeInfo root) {
        List<Item> out = new ArrayList<>();
        Deque<AccessibilityNodeInfo> st = new ArrayDeque<>();
        st.push(root);
        while (!st.isEmpty()) {
            AccessibilityNodeInfo n = st.pop();
            if (n == null) continue;
            Item it = new Item();
            it.n = n;
            it.id = n.getViewIdResourceName();
            CharSequence t = n.getText();
            it.t = t == null ? "" : t.toString();
            n.getBoundsInScreen(it.b);
            CharSequence cn = n.getClassName();
            it.cls = cn == null ? "" : cn.toString();
            boolean isRadio = it.id != null && P_RADIO.matcher(it.id).matches();
            // 无文本的可点击按钮(如顶部交卷电源键)也要收集
            boolean clickableBtn = it.t.length() == 0 && it.cls.endsWith("Button") && n.isClickable();
            if (isRadio || it.t.length() > 0 || clickableBtn) out.add(it);
            for (int i = n.getChildCount() - 1; i >= 0; i--) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) st.push(c);
            }
        }
        return out;
    }

    private String findStem(List<Item> items) {
        Item best = null;
        for (Item it : items) {
            if (it.t.length() < 8) continue;
            if (it.cls.contains("EditText") || it.cls.contains("Button")) continue;
            // 排除选项文本("A."前缀)与 radio 同行文本
            if (P_OPT_PREFIX.matcher(it.t.trim()).find()) continue;
            boolean sameRowAsRadio = false;
            for (Item o : items) {
                if (o.id != null && P_RADIO.matcher(o.id).matches()
                        && Math.abs(o.b.centerY() - it.b.centerY()) <= 60) {
                    sameRowAsRadio = true;
                    break;
                }
            }
            if (sameRowAsRadio) continue;
            boolean numericId = it.id != null && P_STEM.matcher(it.id).matches();
            boolean noId = it.id == null || it.id.isEmpty();
            if (!numericId && !noId) continue;
            if (best == null || it.t.length() > best.t.length()) best = it;
        }
        return best == null ? "" : best.t;
    }

    /** 取与 radio 同行的选项文本(去字母前缀) */
    private String optionTextOf(Item radio, List<Item> items) {
        int cy = radio.b.centerY();
        for (Item it : items) {
            if (it.n.equals(radio.n) || it.t.length() == 0) continue;
            if (it.id != null && P_RADIO.matcher(it.id).matches()) continue;
            if (Math.abs(it.b.centerY() - cy) <= 60 && it.b.left >= radio.b.left) {
                String s = it.t.trim();
                Matcher m = P_OPT_PREFIX.matcher(s);
                if (m.find()) s = s.substring(m.end()).trim();
                return s.replaceAll("[\\s\\u00A0\\u3000]+", "");
            }
        }
        return "";
    }

    /** 按文本/语义找目标 radio */
    private Item findRadioByText(List<Item> radios, List<Item> items, String optText, boolean isTrue) {
        for (Item r : radios) {
            String ot = optionTextOf(r, items);
            if (optText != null && !optText.isEmpty()) {
                if (ot.equals(optText) || (!ot.isEmpty() && (ot.contains(optText) || optText.contains(ot)))) return r;
            } else {
                boolean t = containsAny(ot, "正确", "对", "√", "是");
                boolean f = containsAny(ot, "错误", "错", "×", "否");
                if (isTrue && t && !f) return r;
                if (!isTrue && f) return r;
            }
        }
        return null;
    }

    private Item findButton(List<Item> items, String regex, int minY, int maxY) {
        Pattern p = Pattern.compile(regex);
        for (Item it : items) {
            if (it.t.length() > 0 && it.t.length() <= 6 && p.matcher(it.t).find()
                    && it.b.centerY() >= minY && it.b.centerY() <= maxY) {
                return it;
            }
        }
        return null;
    }

    // ---------- 题库查询 ----------

    private String[] lookup(String q) {
        if (q == null || q.isEmpty()) return null;
        String[] r = memExact.get(q);
        if (r != null) return r;
        // 屏幕文本与库内题干仅标点/空白差异时的归一化命中
        r = memNorm.get(normKey(q));
        if (r != null) return r;
        if (q.length() > 14) {
            r = query("question LIKE ?", new String[]{q.substring(0, 14) + "%"});
            if (r != null) return r;
        }
        return null;
    }

    private String[] query(String where, String[] args) {
        Cursor c = null;
        try {
            c = bank.query("bank", new String[]{"qtype", "options", "answer"}, where, args, null, null, null);
            if (c.moveToFirst()) {
                return new String[]{c.getString(0), c.getString(1), c.getString(2)};
            }
            return null;
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.close();
        }
    }

    private String jsonOpt(String json, String key) {
        try {
            org.json.JSONObject o = new org.json.JSONObject(json);
            return o.optString(key, "").replaceAll("[\\s\\u00A0\\u3000]+", "");
        } catch (Exception e) {
            return "";
        }
    }

    // ---------- 文本规范化 ----------

    private String normalizeStem(String s) {
        if (s == null) return "";
        s = s.replaceAll("[\\s\\u00A0\\u3000]+", "");
        Matcher m = P_LEADING_NUM.matcher(s);
        if (m.find()) s = s.substring(m.end());
        return s;
    }

    private boolean containsAny(String s, String... kws) {
        for (String k : kws) if (s.contains(k)) return true;
        return false;
    }

    // ---------- 点击 ----------

    private void click(AccessibilityNodeInfo n) {
        if (n == null) return;
        Rect b = new Rect();
        n.getBoundsInScreen(b);
        Log.i(TAG, "click(" + b.centerX() + "," + b.centerY() + ") cls=" + n.getClassName());
        clickedNode = n;
        gesture(b.centerX(), b.centerY());
    }

    /** H5 页面只响应真实触摸, 统一用手势点击 */
    private void gesture(int x, int y) {
        try {
            Path path = new Path();
            path.moveTo(x, y);
            GestureDescription.Builder g = new GestureDescription.Builder();
            g.addStroke(new GestureDescription.StrokeDescription(path, 0, 80));
            dispatchGesture(g.build(), new AccessibilityService.GestureResultCallback() {
                @Override public void onCompleted(GestureDescription gestureDescription) {
                    Log.i(TAG, "gesture 完成 (" + x + "," + y + ")");
                }
                @Override public void onCancelled(GestureDescription gestureDescription) {
                    Log.w(TAG, "gesture 取消 (" + x + "," + y + "), 回退 ACTION_CLICK");
                    if (clickedNode != null) {
                        clickedNode.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    }
                }
            }, null);
        } catch (Exception e) {
            Log.e(TAG, "gesture", e);
        }
    }

    private void log(String s) {
        Log.i(TAG, s);
    }

    /** 诊断日志, 5秒节流 */
    private void dbg(String s) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastDbg >= 5000) {
            lastDbg = now;
            Log.i(TAG, s);
        }
    }

    @Override public void onInterrupt() { }
}
