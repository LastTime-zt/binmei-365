package com.bm.auto;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.PowerManager;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 题库直连更新器(免登录)。
 * 关键: 平台接口仅凭持久 cookie xxidnumber(身份证) + xxpid(person guid) 即可访问,
 * 练习库 OnlineStuday 的 vData.AllQuestionArray 已含全量 208 题及标准答案,
 * 不需要模拟登录、不需要验证码。
 */
public class BankUpdater {

    private static final String TAG = "BankUpdater";

    /** 应用上下文(答题时题库兜底查询用), 由入口方法设置 */
    private static volatile android.content.Context sCtx;

    // ---- 答题日志(界面底部显示, 最近 30 条) ----
    private static final java.util.List<String> examLog =
            java.util.Collections.synchronizedList(new ArrayList<String>());
    private static final java.util.List<String> lastScores =
            java.util.Collections.synchronizedList(new ArrayList<String>());

    /** 追加一条日志(带时间), 超过30条移除最旧 */
    public static void appendLog(String msg) {
        String t = new java.text.SimpleDateFormat("HH:mm:ss",
                java.util.Locale.US).format(new java.util.Date());
        synchronized (examLog) {
            examLog.add(t + " " + msg);
            while (examLog.size() > 30) examLog.remove(0);
        }
    }

    /** 当前日志快照(倒序, 最新在上) */
    public static String logText() {
        StringBuilder sb = new StringBuilder();
        synchronized (examLog) {
            for (int i = examLog.size() - 1; i >= 0; i--) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(examLog.get(i));
            }
        }
        return sb.length() == 0 ? "(暂无日志)" : sb.toString();
    }

    /** 最近答卷成绩快照("试卷名 分数"列表, 倒序) */
    public static String lastScoresText() {
        StringBuilder sb = new StringBuilder();
        synchronized (lastScores) {
            for (int i = lastScores.size() - 1; i >= 0; i--) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(lastScores.get(i));
            }
        }
        return sb.length() == 0 ? "(暂无)" : sb.toString();
    }

    private static void recordScore(String paperName, String score) {
        String t = new java.text.SimpleDateFormat("HH:mm",
                java.util.Locale.US).format(new java.util.Date());
        synchronized (lastScores) {
            lastScores.add(t + "  " + paperName + "  得分: " + score);
            while (lastScores.size() > 5) lastScores.remove(0);
        }
    }

    static final String BASE = "http://61.185.41.209:8888";
    // 试卷类别Id(平台固定分类, 与账号无关)
    private static final String ETYPE = "29827a37-50af-4e6d-b25b-e79d356b4ed7";

    private static final Pattern P_VDATA =
            Pattern.compile("var vData=(\\{.*?\\});\\r?\\n", Pattern.DOTALL);
    private static final Pattern P_OPT =
            Pattern.compile("^([A-Z])[.、．:：]?(.*)$");

    public static class Result {
        public final boolean ok;
        public final int count;
        public final String message;
        Result(boolean ok, int count, String message) {
            this.ok = ok; this.count = count; this.message = message;
        }
    }

    /** 是否正在更新(界面与服务共用, 防并发) */
    public static volatile boolean running = false;

    /** 知识阅读: 是否进行中 / 进度描述(界面轮询显示) */
    public static volatile boolean studying = false;
    public static volatile String studyInfo = "";
    private static final String KNOWLEDGE_TYPE_ID =
            "f19b4a76-9d1c-4f53-a728-2b007db6f141";

    /**
     * 知识阅读(免登录, 与题库更新同一套 cookie 会话):
     * 拉知识列表 -> 逐篇打开详情(服务端要求停留约60秒) -> StudyKnowledgeOne 上报阅读。
     * 每篇计1积分; 返回"已达到每日最多积分"时提前结束。
     */
    public static Result study(Context ctx, int count) {
        if (studying) return new Result(false, 0, "阅读已在进行中");
        studying = true;
        try {
            Map<String, String> jar = new HashMap<>();
            ensureLogin(ctx, jar);
            String pid = jar.get("xxpid");
            String ref = BASE + "/PersonWap/Index0018?wx=";

            // 1. 拉知识列表
            String listJson = http(jar,
                    "/LearnManger/L_KnowledgeSubject/GetStudyOne?rows=50&page=1&typeid="
                            + KNOWLEDGE_TYPE_ID + "&DepartmentId=1&pid=" + pid + "&style=0",
                    null, ref, "GET");
            JSONArray items = new JSONObject(listJson).optJSONArray("data");
            if (items == null || items.length() == 0) {
                return new Result(false, 0, "没有可阅读的知识");
            }
            // 未读(ReadCount小)的排前面
            List<JSONObject> list = new ArrayList<>();
            for (int i = 0; i < items.length(); i++) list.add(items.getJSONObject(i));
            Collections.sort(list, new Comparator<JSONObject>() {
                @Override public int compare(JSONObject a, JSONObject b) {
                    return Integer.compare(a.optInt("ReadCount", 0),
                            b.optInt("ReadCount", 0));
                }
            });

            int done = 0;
            for (JSONObject it : list) {
                if (done >= count) break;
                String zsid = it.optString("KnowledgeSubjectId");
                String title = it.optString("Title");
                studyInfo = "阅读 " + (done + 1) + "/" + count + ": "
                        + title.substring(0, Math.min(14, title.length())) + "...";
                // 2. 打开详情页
                Map<String, String> form = new HashMap<>();
                form.put("pid", pid);
                form.put("id", zsid);
                form.put("typeid", it.optString("KnowledgeTypeId", KNOWLEDGE_TYPE_ID));
                form.put("s", "0");
                form.put("pdf", "1");
                form.put("Title", title);
                form.put("ct", it.optString("CreateTime", "").replace("T", " "));
                String detail = http(jar, "/PersonWap/StudyDetailPDF",
                        encodeForm(form), ref, "POST");
                if (!detail.contains("KnowledgeSubjectId")) continue;

                // 3. 停留60秒(服务端计时要求)
                Thread.sleep(61000);

                // 4. 上报阅读
                String rep = http(jar,
                        "/ArchiveManger/D_PersonAccumulate/StudyKnowledgeOne?pid="
                                + URLEncoder.encode(esdt(pid), "UTF-8")
                                + "&zsid=" + URLEncoder.encode(esdt(zsid), "UTF-8"),
                        null, ref, "GET");
                JSONObject js = new JSONObject(rep);
                Log.i(TAG, "阅读上报[" + zsid + "] success=" + js.optBoolean("success")
                        + " msg=" + js.optString("message"));
                if (js.optBoolean("success")) {
                    done++;
                } else {
                    String msg = js.optString("message", "");
                    if (msg.contains("最多积分")) {
                        studyInfo = "今日阅读积分已达上限";
                        return new Result(true, done,
                                "今日阅读积分已达上限, 完成 " + done + " 篇");
                    }
                }
                Thread.sleep(3000);
            }
            return new Result(true, done, "阅读完成 " + done + "/" + count + " 篇");
        } catch (Exception e) {
            Log.e(TAG, "阅读失败", e);
            return new Result(false, 0, "阅读失败: " + e.getClass().getSimpleName());
        } finally {
            studying = false;
        }
    }

    /** 日常任务链(签到->随机练习->阅读15篇): 进行中 / 进度描述(界面轮询显示) */
    public static volatile boolean dailyRunning = false;
    public static volatile String dailyInfo = "";

    /**
     * 一键日常任务: 每日签到 -> 随机练习10题(签到前置需先练题) -> 阅读15篇(浏览任务)。
     * 每步独立容错, 前一步失败不影响后续。
     */
    public static Result dailyTasks(Context ctx) {
        if (dailyRunning) return new Result(false, 0, "日常任务已在进行中");
        dailyRunning = true;
        acquireWakeLock(ctx);
        StringBuilder log = new StringBuilder();
        try {
            appendLog("一键日常开始执行");
            // 0. 查今日积分明细, 已完成的部分智能跳过
            dailyInfo = "查询今日完成情况...";
            JSONObject done = todayPoints(ctx);
            int pPractice = done.optInt("手机练习", 0);
            int pStudy = done.optInt("知识学习", 0);
            boolean signed = done.optInt("签到", 0) > 0;
            Log.i(TAG, "今日已完成: 练习" + pPractice + " 阅读" + pStudy
                    + " 签到" + signed);
            appendLog("今日进度: 练习" + pPractice + "/15  阅读" + pStudy + "/15  签到" + (signed ? "✓" : "✗"));

            // 1. 随机练习(服务端要求: 练1题后才能签到; 记录上限15条/日, 计分上限10/日)
            int nPractice = pPractice >= 15 ? 0 : Math.min(10, 15 - pPractice);
            Result r1;
            if (nPractice <= 0) {
                r1 = new Result(true, 0, "今日已满, 跳过");
                Log.i(TAG, "随机练习 -> 跳过(已有" + pPractice + "条)");
                appendLog("随机练习: 已满跳过");
            } else {
                dailyInfo = "随机练习中...";
                appendLog("随机练习中(" + nPractice + "题)...");
                r1 = practice(ctx, nPractice, true);
                Log.i(TAG, "随机练习 -> " + r1.message);
                appendLog("随机练习: " + r1.message);
            }
            log.append("随机练习: ").append(r1.message).append("\n");

            // 2. 签到
            Result r2;
            if (signed) {
                r2 = new Result(true, 1, "今日已签, 跳过");
                Log.i(TAG, "签到 -> 跳过(已签)");
                appendLog("签到: 已签跳过");
            } else {
                dailyInfo = "正在签到...";
                appendLog("签到中...");
                r2 = checkin(ctx);
                Log.i(TAG, "签到 -> " + r2.message);
                appendLog("签到: " + r2.message);
            }
            log.append("签到: ").append(r2.message).append("\n");

            // 3. 阅读(知识学习, 每日上限15篇, 每篇约65秒)
            int nStudy = Math.max(0, 15 - pStudy);
            Result r3;
            if (nStudy <= 0) {
                r3 = new Result(true, 0, "今日已满15篇, 跳过");
                Log.i(TAG, "阅读 -> 跳过(已有" + pStudy + "分)");
                appendLog("阅读: 已满跳过");
            } else {
                dailyInfo = "阅读浏览中...(还差" + nStudy + "篇)";
                appendLog("阅读浏览中(" + nStudy + "篇, 约" + (nStudy * 65 / 60) + "分钟)...");
                r3 = study(ctx, nStudy);
                Log.i(TAG, "阅读 -> " + r3.message);
                appendLog("阅读: " + r3.message);
            }
            log.append("阅读: ").append(r3.message);

            appendLog("一键日常完成: 练习" + r1.count + "题  阅读" + r3.count + "篇");
            return new Result(true, r1.count + r3.count, log.toString().trim());
        } catch (Exception e) {
            Log.e(TAG, "日常任务失败", e);
            appendLog("日常任务失败: " + e.getClass().getSimpleName()
                    + (e.getMessage() != null ? " " + e.getMessage() : ""));
            return new Result(false, 0, "日常任务失败: " + log + e.getClass().getSimpleName());
        } finally {
            releaseWakeLock();
            dailyRunning = false;
        }
    }

    /**
     * 今日积分明细统计: GET GetMyAllAccumulateListOne?rows=200,
     * 按类型汇总今日条数(知识学习/手机练习/签到)。
     */
    public static JSONObject todayPoints(Context ctx) {
        JSONObject out = new JSONObject();
        try {
            Map<String, String> jar = new HashMap<>();
            ensureLogin(ctx, jar);
            String pid = jar.get("xxpid");
            if (pid == null || pid.isEmpty()) return out;
            String rep = http(jar,
                    "/ArchiveManger/D_PersonAccumulate/GetMyAllAccumulateListOne?pid="
                            + URLEncoder.encode(esdt(pid), "UTF-8")
                            + "&page=1&rows=200",
                    null, BASE + "/PersonWap/Index0018?wx=", "GET");
            JSONObject js = new JSONObject(rep);
            org.json.JSONArray arr = js.optJSONArray("data");
            if (arr == null) return out;
            String today = new java.text.SimpleDateFormat("yyyy-MM-dd",
                    java.util.Locale.US).format(new java.util.Date());
            for (int i = 0; i < arr.length(); i++) {
                JSONObject it = arr.optJSONObject(i);
                if (it == null) continue;
                if (!it.optString("CreateDate", "").startsWith(today)) continue;
                String name = it.optString("AccumulateName", "");
                out.put(name, out.optInt(name, 0) + 1);
            }
        } catch (Exception e) {
            Log.e(TAG, "查询今日积分失败", e);
        }
        return out;
    }

    /** 签到信息: GetSignInInfoOne 管道分隔字段解析 */
    public static class SignInInfo {
        public String total;       // [0] 当前积分总值
        public String nextPoint;   // [1] 明日签到可得
        public String streak;      // [2] 已连续签到天数
        public String maxStreak;   // [3] 连签上限(加分封顶)
        public String step;        // [4] 每日递增
        public String practiced;   // [6] 今日已练习题数
        public boolean signed;     // 倒数第2位 != 0 已签
        public int todayPoints;   // 今日总积分(FirstIndexOne 末字段, 与网页一致)
    }

    /** 拉取签到/积分概况(与平台签到页字段一致) */
    public static SignInInfo signInInfo(Context ctx) {
        try {
            Map<String, String> jar = new HashMap<>();
            ensureLogin(ctx, jar);
            String pid = jar.get("xxpid");
            if (pid == null || pid.isEmpty()) return null;
            String rep = http(jar,
                    "/ArchiveManger/D_PersonAccumulate/GetSignInInfoOne?pid="
                            + URLEncoder.encode(esdt(pid), "UTF-8"),
                    null, BASE + "/PersonWap/SignIn", "GET");
            JSONObject js = new JSONObject(rep);
            if (!js.optBoolean("success")) return null;
            String[] s = js.optString("data", "").split("\\|");
            if (s.length < 7) return null;
            SignInInfo info = new SignInInfo();
            info.total = s[0];
            info.nextPoint = s[1];
            info.streak = s[2];
            info.maxStreak = s[3];
            info.step = s[4];
            info.practiced = s[6];
            info.signed = !"0".equals(s[s.length - 2]);
            info.todayPoints = todayPointsFromFirstIndex(ctx, jar, pid);
            return info;
        } catch (Exception e) {
            Log.e(TAG, "拉取签到信息失败", e);
            return null;
        }
    }

    /**
     * 今日总积分: POST /PersonWap/FirstIndexOne (pid), 响应管道分隔,
     * 末字段=今日获得总积分(与网页显示一致; GetSignInInfoOne 末字段的练习
     * 按计分上限算会偏小, 不采用)。
     */
    private static int todayPointsFromFirstIndex(Context ctx,
            Map<String, String> jar, String pid) {
        try {
            Map<String, String> form = new HashMap<>();
            form.put("pid", pid);
            String rep = http(jar, "/PersonWap/FirstIndexOne", encodeForm(form),
                    BASE + "/PersonWap/Index", "POST");
            String[] s = rep.trim().split("\\|");
            return Integer.parseInt(s[s.length - 1].trim());
        } catch (Exception e) {
            Log.e(TAG, "FirstIndexOne 解析失败", e);
            return 0;
        }
    }

    /** 每日签到: GET CheckInOne?pid=esdt(pid), 首次登录得1积分 */
    public static Result checkin(Context ctx) {
        try {
            Map<String, String> jar = new HashMap<>();
            ensureLogin(ctx, jar);
            String pid = jar.get("xxpid");
            if (pid == null || pid.isEmpty()) return new Result(false, 0, "未取得 pid");
            String rep = http(jar,
                    "/ArchiveManger/D_PersonAccumulate/CheckInOne?pid="
                            + URLEncoder.encode(esdt(pid), "UTF-8"),
                    null, BASE + "/PersonWap/Index0018?wx=", "GET");
            JSONObject js = new JSONObject(rep);
            if (js.optBoolean("success")) {
                return new Result(true, 1, "成功 " + js.optString("data", ""));
            }
            return new Result(false, 0, js.optString("message", "失败"));
        } catch (Exception e) {
            Log.e(TAG, "签到失败", e);
            return new Result(false, 0, "失败: " + e.getClass().getSimpleName());
        }
    }

    /**
     * 在线练习 n 题: SetOnlineStuday(style 0=顺序 1=随机) -> OnlineStuday 页 vData
     * -> 逐题 SetBatQuestionOne 上报(带模拟答题耗时)。
     */
    public static Result practice(Context ctx, int n, boolean random) {
        try {
            Map<String, String> jar = new HashMap<>();
            ensureLogin(ctx, jar);
            String pid = jar.get("xxpid");
            if (pid == null || pid.isEmpty()) return new Result(false, 0, "未取得 pid");

            // 1. 初始化练习会话(随机练习 style=1)
            Map<String, String> form = new HashMap<>();
            form.put("Id", esdt(ETYPE));
            form.put("Id2", esdt(""));
            form.put("style", esdt(random ? "1" : "0"));
            form.put("style2", esdt(""));
            form.put("txid", esdt(""));
            form.put("pid", esdt(pid));
            form.put("wx", esdt(""));
            http(jar, "/P_ExamDetail/SetOnlineStuday", encodeForm(form),
                    BASE + "/PersonWap/Index", "POST");

            // 2. 拉练习页, 解析 vData
            String html = http(jar, "/P_ExamDetail/OnlineStuday", null,
                    BASE + "/PersonWap/Index", "GET");
            Matcher m = P_VDATA.matcher(html);
            if (!m.find()) return new Result(false, 0, "练习页无题库数据");
            JSONObject v = new JSONObject(m.group(1));
            String arr = v.optString("AllQuestionArray", "");
            if (arr.isEmpty()) return new Result(false, 0, "练习题为空");
            String[] qs = arr.split("\\|");
            int total = Math.min(n, qs.length);

            // 3. 逐题上报答案(题库 vData 自带标准答案)
            int done = 0;
            for (int i = 0; i < total; i++) {
                String[] f = qs[i].split(",", -1);
                if (f.length < 14) continue;
                String ans = f[13].trim();
                long elapsed = 3000 + (long) (Math.random() * 9000);
                Map<String, String> d = new HashMap<>();
                d.put("questionids", f[2] + "," + f[1] + "," + ans + "," + elapsed + "|");
                d.put("pid", pid);
                d.put("examtypeid", v.optString("ExamTypeId"));
                d.put("code", v.optString("Code"));
                d.put("pparms",
                        v.optString("StudyTimeAccumulate") + "|"
                        + v.optString("StudyTimeOneMaxAccumulate") + "|"
                        + v.optString("StudyTimeMaxAccumulate") + "|"
                        + v.optString("MobileExercisesAccumulate") + "|"
                        + v.optString("MobileExercisesOneMaxAccumulate") + "|"
                        + v.optString("MobileExercisesMaxAccumulate") + "|");
                d.put("mystyle", v.optString("mystyle"));
                d.put("StudyTimeAccumulateId", v.optString("StudyTimeAccumulateId"));
                d.put("MobileExercisesAccumulateId", v.optString("MobileExercisesAccumulateId"));
                String rep = http(jar, "/ArchiveManger/D_PersonStuday/SetBatQuestionOne",
                        encodeForm(d), BASE + "/P_ExamDetail/OnlineStuday", "POST");
                JSONObject js = new JSONObject(rep);
                Log.i(TAG, "练习[" + (i + 1) + "/" + total + "] " + js.optString("message"));
                done++;
                dailyInfo = "练习 " + done + "/" + total;
                Thread.sleep(2000 + (long) (Math.random() * 2000));
            }
            return new Result(true, done, "完成 " + done + "/" + total + " 题");
        } catch (Exception e) {
            Log.e(TAG, "练习失败", e);
            return new Result(false, 0, "失败: " + e.getClass().getSimpleName());
        }
    }

    /** 前端 Esdt 编码: 每字符 ASCII 十进制拼接 + '^' + 每字符位数列表 */
    static String esdt(String s) {
        StringBuilder codes = new StringBuilder();
        StringBuilder lens = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            String c = String.valueOf((int) s.charAt(i));
            codes.append(c);
            if (lens.length() > 0) lens.append(',');
            lens.append(c.length());
        }
        return codes + "^" + lens;
    }

    /** 执行更新: 直连平台拉练习库全量题目, 合并写入 filesDir/bank.db */
    public static Result update(Context ctx) {
        if (running) return new Result(false, 0, "已在更新中");
        running = true;
        File f = new File(ctx.getFilesDir(), "bank.db");
        SQLiteDatabase db = null;
        try {
            // 首次运行库文件可能尚未由服务复制, 这里兜底从 assets 拷贝
            ensureBankFile(ctx);
            // cookie jar: 登录态 + POST 后服务器下发的练习会话(lxpid/lxId/...), 必须回传
            Map<String, String> jar = new HashMap<>();
            ensureLogin(ctx, jar);
            String lpid = jar.get("xxpid");

            // 1. POST SetOnlineStuday 初始化练习会话
            Map<String, String> form = new HashMap<>();
            form.put("Id", esdt(ETYPE));
            form.put("Id2", esdt(""));
            form.put("style", esdt("0"));
            form.put("style2", esdt(""));
            form.put("txid", esdt(""));
            form.put("pid", esdt(lpid));
            form.put("wx", esdt(""));
            http(jar, "/P_ExamDetail/SetOnlineStuday", encodeForm(form),
                    BASE + "/PersonWap/Index0018", "POST");

            // 2. GET OnlineStuday 拉页面(回传 POST 下发的会话 cookie)
            String html = http(jar, "/P_ExamDetail/OnlineStuday", null,
                    BASE + "/PersonWap/Index0018", "GET");

            Matcher m = P_VDATA.matcher(html);
            if (!m.find()) {
                return new Result(false, 0, "更新失败: 页面无题库数据");
            }
            JSONObject v = new JSONObject(m.group(1));
            String arr = v.optString("AllQuestionArray", "");
            if (arr.isEmpty()) {
                return new Result(false, 0, "更新失败: 题库为空(" + v.optString("Title", "") + ")");
            }

            // 3. 解析入库
            db = SQLiteDatabase.openDatabase(f.getPath(), null, SQLiteDatabase.OPEN_READWRITE);
            db.beginTransaction();
            int n = 0, judge = 0, single = 0, skipped = 0;
            try {
                db.execSQL("CREATE TABLE IF NOT EXISTS bank("
                        + "question TEXT PRIMARY KEY, options TEXT, answer TEXT, "
                        + "qtype TEXT, guid TEXT)");
                for (String q : arr.split("\\|")) {
                    String[] ff = q.split(",", -1);
                    if (ff.length < 17) { skipped++; continue; }
                    String guid = ff[1];
                    String answer = ff[13].trim().toUpperCase();
                    String stem = clean(ff[15]);
                    if (stem.isEmpty()) { skipped++; continue; }
                    String qtype;
                    String optionsJson;
                    if ("Y".equals(answer) || "N".equals(answer)) {
                        qtype = "judge";
                        optionsJson = "{}";
                        judge++;
                    } else if (answer.length() == 1 && answer.charAt(0) >= 'A'
                            && answer.charAt(0) <= 'D') {
                        Map<String, String> opts = parseOptions(ff[16]);
                        if (!opts.containsKey(answer)) { skipped++; continue; }
                        qtype = "single";
                        optionsJson = new JSONObject(opts).toString();
                        single++;
                    } else {
                        skipped++;
                        continue;
                    }
                    db.execSQL("INSERT OR REPLACE INTO bank VALUES (?,?,?,?,?)",
                            new Object[]{stem, optionsJson, answer, qtype, guid});
                    n++;
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            Log.i(TAG, "题库更新完成: " + n + " (判断" + judge + " 单选" + single
                    + " 跳过" + skipped + ")");
            // 库内实际唯一题数(题干为主键, 重复题干会被覆盖)
            long total = db.compileStatement(
                    "SELECT COUNT(*) FROM bank").simpleQueryForLong();
            return new Result(true, n,
                    "已更新 " + n + " 题(判断" + judge + "/单选" + single
                            + "), 题库共 " + total + " 题");
        } catch (Exception e) {
            Log.e(TAG, "题库更新失败", e);
            return new Result(false, 0, "更新失败: " + e.getClass().getSimpleName()
                    + ", 使用本地题库");
        } finally {
            if (db != null) db.close();
            running = false;
        }
    }

    private static Map<String, String> parseOptions(String raw) throws Exception {
        Map<String, String> out = new HashMap<>();
        for (String part : raw.split("#")) {
            String d = urldec(part).trim();
            Matcher m = P_OPT.matcher(d);
            if (m.find()) {
                out.put(m.group(1), m.group(2).replaceAll("[\\s\\u00A0\\u3000]+", ""));
            }
        }
        return out;
    }

    /** URL解码(先保护字面加号), 再做HTML实体清理, 最后去空白 */
    private static String clean(String s) {
        String t = urldec(s);
        t = t.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
             .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'");
        return t.replaceAll("[\\s\\u00A0\\u3000]+", "");
    }

    private static String urldec(String s) {
        if (s == null || s.isEmpty()) return "";
        try {
            return java.net.URLDecoder.decode(s.replace("+", "%2B"), "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private static String encodeForm(Map<String, String> kv) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : kv.entrySet()) {
            if (sb.length() > 0) sb.append('&');
            sb.append(URLEncoder.encode(e.getKey(), "UTF-8"))
              .append('=')
              .append(URLEncoder.encode(e.getValue(), "UTF-8"));
        }
        return sb.toString();
    }

    private static String http(Map<String, String> jar, String path, String body,
                               String referer, String method) throws Exception {
        HttpURLConnection c = null;
        try {
            URL u = new URL(BASE + path);
            c = (HttpURLConnection) u.openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            c.setInstanceFollowRedirects(true);
            c.setRequestMethod(method);
            // 拼接当前 jar 中的所有 cookie
            StringBuilder ck = new StringBuilder();
            for (Map.Entry<String, String> e : jar.entrySet()) {
                if (ck.length() > 0) ck.append("; ");
                ck.append(e.getKey()).append('=').append(e.getValue());
            }
            c.setRequestProperty("Cookie", ck.toString());
            c.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 13) Chrome/150 Mobile Safari/537.36 Html5Plus/1.0");
            c.setRequestProperty("X-Requested-With", "XMLHttpRequest");
            c.setRequestProperty("Referer", referer);
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type",
                        "application/x-www-form-urlencoded; charset=UTF-8");
                OutputStream os = c.getOutputStream();
                os.write(body.getBytes("UTF-8"));
                os.close();
            }
            int code = c.getResponseCode();
            // 回收服务器下发/刷新的 cookie(如 lxpid/lxId/lxstyle 等练习会话)
            java.util.List<String> setCk = c.getHeaderFields().get("Set-Cookie");
            if (setCk != null) {
                for (String sc : setCk) {
                    int semi = sc.indexOf(';');
                    String pair = semi >= 0 ? sc.substring(0, semi) : sc;
                    int eq = pair.indexOf('=');
                    if (eq > 0) {
                        String name = pair.substring(0, eq).trim();
                        String val = pair.substring(eq + 1).trim();
                        jar.put(name, val);
                    }
                }
            }
            InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            is.close();
            if (code >= 400) throw new RuntimeException("HTTP " + code);
            return new String(bos.toByteArray(), "UTF-8");
        } finally {
            if (c != null) c.disconnect();
        }
    }

    // ==================== 登录与自动考试 ====================

    /** 登录/考试: 是否进行中 / 进度描述(界面轮询显示) */
    public static volatile boolean examRunning = false;
    public static volatile String examInfo = "";
    /** 答题实时状态(界面显示): 当前试卷 / 第几题 / 本题提取到的答案 */
    public static volatile String examPaper = "";
    public static volatile int examQNum = 0;
    public static volatile int examQTotal = 0;
    public static volatile String examAnswer = "";
    /** 每题作答延迟(毫秒), 界面"答题延迟(秒/题)"设置, 默认1秒 */
    public static volatile int answerDelayMs = 1000;
    /** 保持 CPU 唤醒, 防止锁屏/后台时答题中断 (PARTIAL_WAKE_LOCK, 限时30min) */
    private static volatile PowerManager.WakeLock sWakeLock;

    /** 持有 WakeLock, 防止锁屏/后台时 CPU 休眠导致请求超时; 最多持有时长 30min */
    private static void acquireWakeLock(Context ctx) {
        if (sWakeLock != null) return;
        PowerManager pm = (PowerManager) ctx.getApplicationContext()
                .getSystemService(Context.POWER_SERVICE);
        sWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BankUpdater:Exam");
        sWakeLock.acquire(30 * 60 * 1000L);
    }

    /** 释放 WakeLock */
    private static void releaseWakeLock() {
        if (sWakeLock != null) {
            try { sWakeLock.release(); } catch (Exception ignore) { }
            sWakeLock = null;
        }
    }

    /** 个人信息: MyBaseInfo 页 vData 解析结果 */
    public static class PersonInfo {
        public String name;    // 姓名
        public String tel;     // 联系方式
        public String idcard;  // 身份证号
        public String unit;    // 工作单位(如 陕西润中清洁能源有限公司/消防队)
    }

    private static volatile String cachedUnit = null;

    /**
     * 拉取个人信息: POST /PersonWap/MyBaseInfo (pid + sstype=0),
     * 响应 HTML 内嵌 var vData={...} JSON, 提取 pname/tel/idcard/punit。
     */
    public static PersonInfo personInfo(Context ctx) {
        try {
            Map<String, String> jar = new HashMap<>();
            ensureLogin(ctx, jar);
            String pid = jar.get("xxpid");
            if (pid == null || pid.isEmpty()) return null;
            Map<String, String> form = new HashMap<>();
            form.put("pid", esdt(pid));
            form.put("sstype", "0");
            String html = http(jar, "/PersonWap/MyBaseInfo", encodeForm(form),
                    BASE + "/PersonWap/Index", "POST");
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("var vData=(\\{.*?\\});")
                    .matcher(html);
            if (!m.find()) return null;
            JSONObject v = new JSONObject(m.group(1));
            PersonInfo p = new PersonInfo();
            p.name = v.optString("pname", "");
            p.tel = v.optString("tel", "");
            p.idcard = v.optString("idcard", "");
            p.unit = v.optString("punit", "");
            // 工作单位取 "/" 前的公司部分, 缓存给标题用
            String company = p.unit;
            int slash = company.indexOf('/');
            if (slash > 0) company = company.substring(0, slash);
            cachedUnit = company;
            return p;
        } catch (Exception e) {
            Log.e(TAG, "拉取个人信息失败", e);
            return null;
        }
    }

    /** 标题用公司名(上次 personInfo 缓存, 未拉取过返回 null) */
    public static String cachedCompany() {
        return cachedUnit;
    }

    private static String acct(Context ctx, String key, String def) {
        String v = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
                .getString(key, "");
        return (v == null || v.isEmpty()) ? def : v;
    }

    /** 用试卷列表接口校验当前 cookie 是否有效(需已取得 pid 且 data 非 null) */
    private static boolean checkLogin(Map<String, String> jar) {
        String pid = jar.get("xxpid");
        if (pid == null || pid.isEmpty()) return false;
        try {
            String r = http(jar,
                    "/ExamManger/P_Paper/SelectCanRunListOne?rows=1&page=1&pid="
                            + pid + "&examtypeid=" + ETYPE,
                    null, BASE + "/PersonWap/Index0018", "GET");
            JSONObject js = new JSONObject(r);
            return js.has("data") && !js.isNull("data");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 确保登录态: cookie 有效直接用; 失效则自动登录。
     * 实测服务端不校验验证码(yzm 任意值可通过), 纯 HTTP 即可登录。
     * 成功后把 pid 存入 prefs, 下次免登录。
     */
    public static void ensureLogin(Context ctx, Map<String, String> jar) throws Exception {
        String idcard = acct(ctx, "idcard", "");
        String pwd = acct(ctx, "password", "");
        if (idcard.isEmpty() || pwd.isEmpty()) {
            throw new RuntimeException("请先在\"账号登录\"中输入账号和密码");
        }
        jar.put("xxidnumber", idcard);
        String pid = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
                .getString("login_pid", "");
        if (!pid.isEmpty()) jar.put("xxpid", pid);
        if (checkLogin(jar)) return;   // cookie 仍有效

        examInfo = "正在登录...";
        http(jar, "/Home/LoginWap2", null, BASE, "GET");
        Map<String, String> form = new HashMap<>();
        form.put("idcard", esdt(idcard));
        form.put("openid", "");
        form.put("yzm", "0");
        form.put("pwd", esdt(pwd));
        form.put("style", "0");
        form.put("auto", "true");
        String resp = http(jar, "/PersonWap/GetPersonInfo", encodeForm(form),
                BASE + "/Home/LoginWap2", "POST");
        String[] parts = resp.trim().split("\\|");
        if (parts.length < 3 || parts[1].isEmpty()) {
            throw new RuntimeException("登录失败: " + resp.substring(0,
                    Math.min(80, resp.length())));
        }
        jar.put("xxidnumber", idcard);
        jar.put("xxpid", parts[1]);
        ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
                .edit().putString("login_pid", parts[1]).apply();
        Log.i(TAG, "登录成功 pid=" + parts[1] + " name=" + parts[2]);
    }

    /**
     * 供界面拉取试卷清单(登录 -> 所有类别试卷合并)。
     * 返回 "PaperId|PaperName" 数组; 失败返回 null。
     */
    public static String[] paperListForUi(Context ctx) {
        try {
            Map<String, String> jar = new HashMap<>();
            ensureLogin(ctx, jar);
            JSONArray arr = listPapers(jar, jar.get("xxpid"));
            String[] out = new String[arr.length()];
            for (int i = 0; i < arr.length(); i++) {
                JSONObject p = arr.getJSONObject(i);
                out[i] = p.optString("PaperId") + "|"
                        + p.optString("PaperName", "未命名试卷");
            }
            return out;
        } catch (Exception e) {
            Log.e(TAG, "拉取试卷清单失败", e);
            return null;
        }
    }

    /**
     * 自动答题(模拟考试): 先完成所有未交卷的考试, 再按指定试卷/张数开新卷。
     * paperId 非空时只考这张(忽略 limit); limit=0 表示全部试卷。
     */
    public static Result runExams(Context ctx, int limit, String paperId) {
        if (examRunning) return new Result(false, 0, "答题已在进行中");
        examRunning = true;
        acquireWakeLock(ctx);
        sCtx = ctx.getApplicationContext();
        String desc = paperId != null
                ? "指定试卷模式"
                : "全部试卷模式";
        examInfo = desc;
        SharedPreferences sp = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE);
        examPaper = paperId != null ? sp.getString("exam_paper_name", "") : "";
        examQNum = 0; examQTotal = 0; examAnswer = "";
        answerDelayMs = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
                .getInt("answer_delay", 1) * 1000;
        Result ret = null;
        try {
            Map<String, String> jar = new HashMap<>();
            ensureLogin(ctx, jar);
            String pid = jar.get("xxpid");
            if (pid == null || pid.isEmpty()) throw new RuntimeException("未取得 pid");

            StringBuilder scores = new StringBuilder();
            int done = 0;

            // 0. 优先完成未交卷的考试(否则服务端可能不允许开新卷)
            JSONArray un = listUnfinished(jar, pid);
            if (un != null && un.length() > 0) {
                for (int i = 0; i < un.length(); i++) {
                    JSONObject u = un.getJSONObject(i);
                    String ksmxid = u.getString("ExamDetailId");
                    String uname = u.optString("ExamName", "未完成考试");
                    examInfo = "完成未交卷 " + (i + 1) + "/" + un.length() + ": "
                            + uname.substring(0, Math.min(12, uname.length())) + "...";
                    examPaper = uname;
                    String score = doPaper(jar, pid, u.optString("PaperId", ""), ksmxid);
                    Log.i(TAG, "未交卷[" + ksmxid + "] -> " + score);
                    appendLog("补交未交卷: " + uname);
                    if (score != null) {
                        done++;
                        if (scores.length() > 0) scores.append("/");
                        scores.append(score);
                        addExamStat(ctx, score);
                        recordScore(uname, score);
                    } else {
                        appendLog("补交失败: " + uname);
                    }
                    Thread.sleep(3000);
                }
            }

            if (paperId != null && !paperId.isEmpty()) {
                // 用户指定了试卷: 只考这张
                appendLog("指定试卷开始答题: " + examPaper);
                String score = doPaper(jar, pid, paperId, null);
                if (score != null) {
                    done++;
                    if (scores.length() > 0) scores.append("/");
                    scores.append(score);
                    addExamStat(ctx, score);
                    recordScore("指定试卷", score);
                } else {
                    appendLog("指定试卷交卷失败: " + paperId);
                }
                ret = new Result(true, done,
                        done > 0 ? "完成 " + done + " 张, 得分: " + scores
                                : "该试卷交卷失败");
            } else {
                // 1. 可考试卷列表(按账号分配的所有类别)
                examInfo = "获取试卷列表...";
                JSONArray papers = listPapers(jar, pid);
                Log.i(TAG, "可考试卷 " + (papers == null ? 0 : papers.length())
                        + " 张, limit=" + limit);
                if (papers == null || papers.length() == 0) {
                    ret = new Result(true, done,
                            done > 0 ? "完成未交卷 " + done + " 张, 得分: " + scores
                                    : "没有可考试的试卷");
                } else {
                    int total = (limit > 0) ? Math.min(limit, papers.length())
                            : papers.length();
                    for (int i = 0; i < total; i++) {
                        JSONObject p = papers.getJSONObject(i);
                        String pid2 = p.getString("PaperId");
                        String paperName = p.optString("PaperName", "");
                        examInfo = "答题 " + (i + 1) + "/" + total + ": "
                                + paperName.substring(0, Math.min(14, paperName.length())) + "...";
                        examPaper = paperName;
                        appendLog("开始答题 " + (i + 1) + "/" + total + ": " + paperName);
                        String score = doPaper(jar, pid, pid2, null);
                        if (score != null) {
                            done++;
                            if (scores.length() > 0) scores.append("/");
                            scores.append(score);
                            addExamStat(ctx, score);
                            recordScore(paperName, score);
                        }
                        Thread.sleep(3000);
                    }
                    ret = new Result(true, done,
                            done > 0 ? "完成 " + done + " 张, 得分: " + scores
                                    : "没有成功交卷的试卷");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "自动答题失败", e);
            appendLog("答题失败: " + e.getClass().getSimpleName()
                    + (e.getMessage() != null ? " " + e.getMessage() : ""));
            ret = new Result(false, 0, "答题失败: " + e.getClass().getSimpleName()
                    + (e.getMessage() != null ? " " + e.getMessage() : ""));
        } finally {
            releaseWakeLock();
            examRunning = false;
            examPaper = ""; examQNum = 0; examQTotal = 0; examAnswer = "";
        }
        examInfo = "已结束 · " + ret.message;
        return ret;
    }

    /** 未交卷考试列表(GetMyNotExamListOne type=3) */
    private static JSONArray listUnfinished(Map<String, String> jar,
                                            String pid) throws Exception {
        try {
            String r = http(jar,
                    "/ExamManger/P_ExamDetail/GetMyNotExamListOne?pid=" + pid
                            + "&type=3&rows=100&page=1",
                    null, BASE + "/PersonWap/MyNotFinish", "GET");
            return new JSONObject(r).optJSONArray("data");
        } catch (Exception e) {
            Log.w(TAG, "拉取未完成考试失败", e);
            return null;
        }
    }

    /** 查询未交卷考试数量(不清卷, 仅供界面提示) */
    public static int countUnfinished(Context ctx) {
        try {
            Map<String, String> jar = new HashMap<>();
            ensureLogin(ctx, jar);
            String pid = jar.get("xxpid");
            if (pid == null || pid.isEmpty()) return -1;
            JSONArray un = listUnfinished(jar, pid);
            return un == null ? -1 : un.length();
        } catch (Exception e) {
            Log.w(TAG, "查询未交卷数量失败", e);
            return -1;
        }
    }

    /** 独立清理未完成考试: 有未交卷的直接逐张答题交卷清除, 不开新卷 */
    public static Result cleanUnfinished(Context ctx) {
        if (examRunning) return new Result(false, 0, "答题已在进行中");
        examRunning = true;
        acquireWakeLock(ctx);
        sCtx = ctx.getApplicationContext();
        examInfo = "";
        Result ret = null;
        try {
            Map<String, String> jar = new HashMap<>();
            ensureLogin(ctx, jar);
            String pid = jar.get("xxpid");
            if (pid == null || pid.isEmpty()) throw new RuntimeException("未取得 pid");
            examInfo = "检查未完成考试...";
            appendLog("开始检查未交卷考试...");
            JSONArray un = listUnfinished(jar, pid);
            if (un == null || un.length() == 0) {
                appendLog("没有未交卷考试");
                ret = new Result(true, 0, "没有未完成的考试");
            } else {
                appendLog("发现 " + un.length() + " 张未交卷考试, 开始清理");
                int done = 0;
                StringBuilder scores = new StringBuilder();
                for (int i = 0; i < un.length(); i++) {
                    JSONObject u = un.getJSONObject(i);
                    String ksmxid = u.getString("ExamDetailId");
                    String uname = u.optString("ExamName", "未完成考试");
                    examInfo = "清理未交卷 " + (i + 1) + "/" + un.length()
                            + ": " + uname.substring(0, Math.min(12, uname.length())) + "...";
                    examPaper = uname;
                    appendLog("清理 " + (i + 1) + "/" + un.length() + ": " + uname);
                    String score = doPaper(jar, pid, u.optString("PaperId", ""), ksmxid);
                    Log.i(TAG, "清理未交卷[" + ksmxid + "] -> " + score);
                    if (score != null) {
                        done++;
                        if (scores.length() > 0) scores.append("/");
                        scores.append(score);
                        addExamStat(ctx, score);
                        recordScore(uname, score);
                    } else {
                        appendLog("答卷失败: " + uname);
                    }
                    Thread.sleep(3000);
                }
                // 清理后再确认是否还有残留
                JSONArray rest = listUnfinished(jar, pid);
                int restN = rest == null ? 0 : rest.length();
                ret = new Result(true, done,
                        "清理完成 " + done + " 张, 得分: " + scores
                                + (restN > 0 ? " (仍剩 " + restN + " 张未清完)" : ""));
            }
        } catch (Exception e) {
            Log.e(TAG, "清理未完成考试失败", e);
            appendLog("清理失败: " + e.getClass().getSimpleName());
            ret = new Result(false, 0, "清理失败: " + e.getClass().getSimpleName()
                    + (e.getMessage() != null ? " " + e.getMessage() : ""));
        } finally {
            releaseWakeLock();
            examRunning = false;
            examPaper = ""; examQNum = 0; examQTotal = 0; examAnswer = "";
        }
        examInfo = "已结束 · " + ret.message;
        return ret;
    }

    // ---- 答卷统计(今日答卷张数/得分, 供积分概况显示) ----

    /** 累计今日答卷统计: score 形如 "98" 或 "98/100" */
    private static void addExamStat(Context ctx, String score) {
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("(\\d+)").matcher(score == null ? "" : score);
            int pts = m.find() ? Integer.parseInt(m.group(1)) : 0;
            android.content.SharedPreferences sp =
                    ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE);
            String today = new java.text.SimpleDateFormat("yyyy-MM-dd",
                    java.util.Locale.US).format(new java.util.Date());
            if (!today.equals(sp.getString("exam_stat_date", ""))) {
                sp.edit().putString("exam_stat_date", today)
                        .putInt("exam_stat_count", 0)
                        .putInt("exam_stat_pts", 0).apply();
            }
            sp.edit()
              .putInt("exam_stat_count", sp.getInt("exam_stat_count", 0) + 1)
              .putInt("exam_stat_pts", sp.getInt("exam_stat_pts", 0) + pts)
              .apply();
        } catch (Exception e) {
            Log.w(TAG, "答卷统计失败", e);
        }
    }

    /** 今日答卷统计(实时积分明细): [0]=答卷次数(模拟考试条数) [1]=获得积分合计 */
    public static int[] examStatToday(Context ctx) {
        int[] out = new int[]{0, 0};
        try {
            Map<String, String> jar = new HashMap<>();
            ensureLogin(ctx, jar);
            String pid = jar.get("xxpid");
            if (pid == null || pid.isEmpty()) return out;
            String rep = http(jar,
                    "/ArchiveManger/D_PersonAccumulate/GetMyAllAccumulateListOne?pid="
                            + URLEncoder.encode(esdt(pid), "UTF-8")
                            + "&page=1&rows=200",
                    null, BASE + "/PersonWap/Index0018?wx=", "GET");
            org.json.JSONArray arr = new JSONObject(rep).optJSONArray("data");
            if (arr == null) return out;
            String today = new java.text.SimpleDateFormat("yyyy-MM-dd",
                    java.util.Locale.US).format(new java.util.Date());
            for (int i = 0; i < arr.length(); i++) {
                JSONObject it = arr.optJSONObject(i);
                if (it == null) continue;
                if (!"模拟考试".equals(it.optString("AccumulateName", ""))) continue;
                if (!it.optString("CreateDate", "").startsWith(today)) continue;
                out[0]++;
                out[1] += it.optInt("Accumulate", 0);
            }
        } catch (Exception e) {
            Log.e(TAG, "查询今日答卷统计失败", e);
        }
        return out;
    }

    /**
     * 可考试卷列表: SelectPaper 页取账号分配的全部类别(ExamTypeIds, 逗号分隔),
     * 再逐类 SelectCanRunListOne 合并去重。
     */
    static JSONArray listPapers(Map<String, String> jar, String pid) throws Exception {
        String ids = ETYPE;
        try {
            String page = http(jar, "/PersonWap/SelectPaper", "pid=" + pid,
                    BASE + "/PersonWap/PersonMain", "POST");
            Matcher mm = Pattern.compile(
                    "ExamTypeIds\"\\s*:\\s*\"([^\"]+)\"").matcher(page);
            if (mm.find()) ids = mm.group(1);
        } catch (Exception e) {
            Log.w(TAG, "SelectPaper页拉取失败, 用默认类别", e);
        }
        JSONArray merged = new JSONArray();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String tid : ids.split(",")) {
            tid = tid.trim();
            if (tid.isEmpty()) continue;
            try {
                String listJson = http(jar,
                        "/ExamManger/P_Paper/SelectCanRunListOne?rows=50&page=1&pid="
                                + pid + "&examtypeid=" + tid,
                        null, BASE + "/PersonWap/Index0018", "GET");
                JSONArray arr = new JSONObject(listJson).optJSONArray("data");
                if (arr == null) continue;
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject p = arr.getJSONObject(i);
                    String key = p.optString("PaperId");
                    if (seen.add(key)) merged.put(p);
                }
            } catch (Exception e) {
                Log.w(TAG, "类别 " + tid + " 拉试卷失败", e);
            }
        }
        return merged;
    }

    /** 单张试卷完整流程, 返回得分文本(失败返回 null) */
    private static String doPaper(Map<String, String> jar, String pid,
                                  String paperId, String ksmxid) throws Exception {
        String ref = BASE + "/PersonWap/Index0018";
        Map<String, String> form = new HashMap<>();
        if (ksmxid == null || ksmxid.isEmpty()) {
            // 创建考试 -> ksmxid
            form.put("PaperId", paperId);
            form.put("PersonId", pid);
            form.put("IDNumber", "");
            form.put("type", "1");
            String cr = http(jar, "/Home/CreateTempExamOne", encodeForm(form), ref, "POST");
            JSONObject cj = new JSONObject(cr);
            if (!cj.optBoolean("success")) {
                Log.w(TAG, "创建考试失败: " + cj.optString("message"));
                // 服务端失败前可能已插入考试记录, 清理该卷的孤儿未交卷
                JSONArray orphans = listUnfinished(jar, pid);
                if (orphans != null) {
                    for (int i = 0; i < orphans.length(); i++) {
                        JSONObject o = orphans.getJSONObject(i);
                        if (paperId.equals(o.optString("PaperId"))) {
                            finishAttempt(jar, pid, o.getString("ExamDetailId"),
                                    paperId);
                        }
                    }
                }
                return null;
            }
            ksmxid = cj.getString("data");
        }
        return finishAttempt(jar, pid, ksmxid, paperId);
    }

    /** 进入指定考试明细 -> 拉题作答 -> 交卷, 返回得分文本(失败返回 null) */
    private static String finishAttempt(Map<String, String> jar, String pid,
                                        String ksmxid, String paperId) throws Exception {
        String ref = BASE + "/PersonWap/Index0018";
        // 1. 进入考试
        Map<String, String> form = new HashMap<>();
        form.put("ksmxid", esdt(ksmxid));
        form.put("showBZDA", esdt("0"));
        form.put("style", esdt("0"));
        form.put("vErr", esdt("-1"));
        form.put("nc", esdt(""));
        form.put("r", esdt(""));
        form.put("e", esdt(""));
        form.put("b", esdt(""));
        form.put("pid", esdt(pid));
        http(jar, "/P_ExamDetail/SetOnlineTestOne", encodeForm(form), ref, "POST");

        // 2. 拉试卷页(含标准答案)
        String html = http(jar, "/P_ExamDetail/OnlineTestOne", null,
                BASE + "/P_ExamDetail/OnlineTestOne", "GET");
        Matcher m = P_VDATA.matcher(html);
        if (!m.find()) {
            Log.w(TAG, "试卷页无 vData");
            return null;
        }
        JSONObject v = new JSONObject(m.group(1));
        if (v.optString("ErrorMsg", "").length() > 0) {
            Log.w(TAG, "服务端报错: " + v.optString("ErrorMsg"));
            return null;
        }
        String[] qs = v.getString("AllQuestionArray").split("\\|");
        examQTotal = qs.length;
        examQNum = 0;
        examAnswer = "";
        StringBuilder answer = new StringBuilder();
        android.content.Context appCtx = sCtx;
        int libHit = 0, libMiss = 0, srvAns = 0;
        for (String q : qs) {
            try {
                String[] f = q.split(",");
                int n = f.length;
                if (n < 15) {
                    Log.w(TAG, "字段异常(" + n + "): " + q.substring(0, Math.min(40, q.length())));
                }
                // 三种尾部布局按内容自动识别(分值=单选1/判断3):
                //  A. [n-4]=答案 [n-3]=分值 [n-2]=题干 [n-1]=选项
                //  B. [n-3]=题干 [n-2]=分值 [n-1]=选项          (无答案字段)
                //  C. [n-4]=题干 [n-3]=答案 [n-2]=分值 [n-1]=选项
                String curOpts = urlDecode(f[n - 1]);
                String std;
                String stem;
                boolean judge;
                if (!f[n - 2].matches("\\d{1,2}")) {
                    // 布局A: n-2 是题干
                    std = f[n - 4];
                    judge = "3".equals(f[n - 3]);
                    stem = urlDecode(f[n - 2]);
                } else if (f[n - 3].matches("[A-DYN]")) {
                    // 布局C: n-3 是答案
                    std = f[n - 3];
                    judge = "3".equals(f[n - 2]);
                    stem = urlDecode(f[n - 4]);
                } else {
                    // 布局B: 无答案字段
                    std = "-1";
                    judge = "3".equals(f[n - 2]);
                    stem = urlDecode(f[n - 3]);
                }
                // 答案字段必须是合法字母, 否则视为未给答案
                if (!(std.matches("[A-D]") || std.matches("[YN]"))) {
                    std = "-1";
                }
                if ("-1".equals(std)) {
                    // 服务器未给标准答案 -> 本地题库兜底(选项顺序随机, 须按文本映射字母)
                    String[] lib = bankLookup(appCtx, stem);   // [0]=qtype [1]=options [2]=answer
                    if (lib != null) {
                        libHit++;
                        std = mapAnswerByText(lib, curOpts);
                    } else {
                        libMiss++;
                        std = judge ? "Y" : "A";  // 判断题默认对, 单选默认A
                        appendLog("第" + (examQNum + 1) + "题题库未命中, 默认答 "
                                + std + " (题干: "
                                + stem.substring(0, Math.min(24, stem.length())) + "...)");
                    }
                } else {
                    srvAns++;
                }
                f[4] = std;   // 我的答案 <- 标准答案
                // 实时状态: 题号 + 标准答案文本
                examQNum++;
                examAnswer = ansText(std, f[n - 1]);
                for (int i = 0; i < 15; i++) {
                    if (i > 0) answer.append(',');
                    answer.append(f[i]);
                }
                answer.append('|');
                Thread.sleep(answerDelayMs + (long) (Math.random() * 300));
            } catch (InterruptedException ie) {
                throw ie;
            } catch (Exception qe) {
                Log.e(TAG, "第" + (examQNum + 1) + "题解析失败: " + qe, qe);
                // 兜底: 原样追加, 保证总字段数不变
                if (answer.length() > 0 && answer.charAt(answer.length() - 1) != '|') {
                    answer.append('|');
                }
                answer.append(q).append('|');
                examQNum++;
                examAnswer = "(解析失败)";
            }
        }

        // 3. 提交答案
        form = new HashMap<>();
        form.put("answer", answer.toString());
        form.put("ksmxid", ksmxid);
        http(jar, "/Home/DoWriteAnswerAllOne", encodeForm(form),
                BASE + "/P_ExamDetail/OnlineTestOne", "POST");

        // 4. 心跳 + 交卷
        form = new HashMap<>();
        form.put("ksmxid", ksmxid);
        http(jar, "/Home/ExamClockOne", encodeForm(form),
                BASE + "/P_ExamDetail/OnlineTestOne", "POST");
        String raw = http(jar, "/Home/EndTimeOne", encodeForm(form),
                BASE + "/P_ExamDetail/OnlineTestOne", "POST").trim();
        // 响应可能为 "100" 或 "您的分数为：50,不合格！|50|不合格！", 解析出纯数字得分
        java.util.regex.Matcher sm = java.util.regex.Pattern
                .compile("\\d+").matcher(raw);
        String score = sm.find() ? sm.group() : raw;
        Log.i(TAG, "交卷 " + paperId + " -> " + score);
        appendLog("交卷完成, 得分: " + score
                + " (题库命中" + libHit + "/未命中" + libMiss + "/服务器给答" + srvAns + ")");
        examPaper = "";
        examQNum = 0; examQTotal = 0; examAnswer = "";
        return score;
    }

    /**
     * 答案显示文本: options = URL编码的 "A.xxx#B.xxx#C.xxx#D.xxx",
     * ans = 标准答案字母(如 "D", 多选 "A;C") -> "D.事故后果"。
     */
    private static String ansText(String ans, String options) {
        if (ans == null || ans.isEmpty()) return "?";
        try {
            String opt = java.net.URLDecoder.decode(options, "UTF-8");
            StringBuilder sb = new StringBuilder();
            for (String one : ans.split(";")) {
                if (sb.length() > 0) sb.append(" ");
                String letter = one.trim();
                // 找 "letter." 开头的选项
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("(?:^|#)" + letter + "\\.(.*?)(?:#|$)")
                        .matcher(opt);
                sb.append(m.find() ? letter + "." + m.group(1) : letter);
            }
            return sb.toString();
        } catch (Exception e) {
            return ans;
        }
    }

    /** URL 解码(容错) */
    private static String urlDecode(String s) {
        try {
            return java.net.URLDecoder.decode(s == null ? "" : s, "UTF-8");
        } catch (Exception e) {
            return s == null ? "" : s;
        }
    }

    /** 确保 filesDir/bank.db 存在: 缺失时从 assets 拷贝 */
    private static void ensureBankFile(Context ctx) {
        File f = new File(ctx.getFilesDir(), "bank.db");
        if (f.exists() && f.length() > 0) return;
        java.io.InputStream is = null;
        java.io.FileOutputStream os = null;
        try {
            is = ctx.getAssets().open("question_bank.db");
            os = new java.io.FileOutputStream(f);
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
            Log.i(TAG, "bank.db 缺失, 已从 assets 恢复");
        } catch (Exception e) {
            Log.w(TAG, "assets 题库恢复失败", e);
        } finally {
            try { if (is != null) is.close(); } catch (Exception ignore) { }
            try { if (os != null) os.close(); } catch (Exception ignore) { }
        }
    }

    /** 本地题库查询: 题干精确 -> 归一化 -> 前缀, 返回 [qtype, options, answer] 或 null */
    private static String[] bankLookup(Context ctx, String stem) {
        if (ctx == null || stem == null || stem.isEmpty()) return null;
        SQLiteDatabase db = null;
        try {
            ensureBankFile(ctx);
            db = SQLiteDatabase.openDatabase(
                    new File(ctx.getFilesDir(), "bank.db").getPath(),
                    null, SQLiteDatabase.OPEN_READONLY);
            // 与入库侧 clean() 一致: 先解码 HTML 实体再去空白, 否则带 &nbsp; 的题干查不到
            String clean = stem.replace("&nbsp;", " ").replace("&amp;", "&")
                    .replace("&lt;", "<").replace("&gt;", ">")
                    .replace("&quot;", "\"").replace("&#39;", "'")
                    .replaceAll("[\\s\\u00a0\\u3000]+", "");
            String norm = clean.replaceAll(
                    "[\\p{Punct}\\u3000-\\u303F\\uFF00-\\uFFEF]", "").toLowerCase();
            // 1. 精确
            String[] r = bankQuery(db, "question=?", new String[]{clean});
            if (r != null) return r;
            // 2. 前缀(去编号)
            String noNum = clean.replaceFirst("^\\d+[.、．]?", "");
            if (noNum.length() >= 12) {
                r = bankQuery(db, "question LIKE ?",
                        new String[]{noNum.substring(0, 12) + "%"});
                if (r != null) return r;
            }
            // 3. 归一化扫描(题目量有限, 全表可接受)
            if (norm.length() >= 10) {
                Cursor c = null;
                try {
                    c = db.query("bank", new String[]{"question", "qtype", "options", "answer"},
                            null, null, null, null, null);
                    while (c.moveToNext()) {
                        String q = c.getString(0) == null ? "" : c.getString(0);
                        String qn = q.replaceAll(
                                "[\\p{Punct}\\u3000-\\u303F\\uFF00-\\uFFEF]", "")
                                .toLowerCase();
                        if (qn.equals(norm) || (qn.length() > 14
                                && norm.startsWith(qn.substring(0, 14)))) {
                            return new String[]{c.getString(1), c.getString(2),
                                    c.getString(3)};
                        }
                    }
                } finally {
                    if (c != null) c.close();
                }
            }
            return null;
        } catch (Exception e) {
            Log.w(TAG, "题库查询失败", e);
            return null;
        } finally {
            if (db != null) db.close();
        }
    }

    private static String[] bankQuery(SQLiteDatabase db, String where, String[] args) {
        Cursor c = null;
        try {
            c = db.query("bank", new String[]{"qtype", "options", "answer"},
                    where, args, null, null, null);
            return c.moveToFirst()
                    ? new String[]{c.getString(0), c.getString(1), c.getString(2)}
                    : null;
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.close();
        }
    }

    /**
     * 选项顺序随机化处理: 题库答案字母对应的选项文本 -> 当前试卷中同文本的字母。
     * lib: [0]=qtype [1]=optionsJson [2]=answer; curOpts: "A.xxx#B.xxx#..."
     */
    private static String mapAnswerByText(String[] lib, String curOpts) {
        try {
            String ans = lib[2] == null ? "" : lib[2].trim().toUpperCase();
            if (ans.isEmpty()) return "A";
            // 判断题: Y/N 无顺序问题, 直接返回
            if ("Y".equals(ans) || "N".equals(ans)) return ans;
            // 题库答案文本(入库时已解码实体, 只需去空白)
            String bankTxt = "";
            try {
                org.json.JSONObject o = new org.json.JSONObject(lib[1]);
                bankTxt = o.optString(ans, "").replaceAll("[\\s\\u00A0\\u3000]+", "");
            } catch (Exception ignore) { }
            if (bankTxt.isEmpty()) return ans;
            // 在当前试卷选项中按文本查找(先解码实体, 与题库文本口径一致)
            for (String part : curOpts.split("#")) {
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("^([A-Z])[.、．:：]?(.*)$")
                        .matcher(part.trim());
                if (m.find()) {
                    String cur = m.group(2).replace("&nbsp;", " ").replace("&amp;", "&")
                            .replace("&lt;", "<").replace("&gt;", ">")
                            .replace("&quot;", "\"").replace("&#39;", "'")
                            .replaceAll("[\\s\\u00A0\\u3000]+", "");
                    if (cur.equals(bankTxt)) return m.group(1);
                }
            }
            return ans;   // 文本未匹配到(选项内容有差异), 退回字母
        } catch (Exception e) {
            return "A";
        }
    }
}
