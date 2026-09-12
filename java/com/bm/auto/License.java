package com.bm.auto;

import android.content.Context;
import android.provider.Settings;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 注册码授权(纯云端): APK 不内置激活密钥。
 * 云端 license-db 的 licenses.json 为唯一权威:
 * {code, device, expire(yyMMdd), status, account, pwd}。
 * 激活: 联网查询注册码 -> 匹配设备码 + status=active + 未过期 -> 本地缓存到期日。
 * 账号绑定: 首次登录时上报 account/pwd 到云端(双向绑定);
 *           云端已有绑定且与本机账号不一致 -> 拒绝绑定/清除授权。
 * 启动复查: active 刷新缓存到期日 + 校验云端绑定账号与本机登录账号一致;
 *           blocked/missing/绑定的账号不一致 清除授权;
 *           不可达(断网/被墙)时用本地缓存兜底, 不影响已激活用户。
 */
public final class License {

    public static final String RS_ACTIVE = "active";
    public static final String RS_BLOCKED = "blocked";
    public static final String RS_MISSING = "missing";
    public static final String RS_UNREACHABLE = "unreachable";

    /** 云端记录地址: raw 直连 + 国内镜像回退 (?t= 破 CDN 缓存, 吊销/续期及时生效) */
    private static final String[] REMOTE_URLS = {
            "https://raw.githubusercontent.com/LastTime-zt/license-db/main/licenses.json",
            "https://ghfast.top/https://raw.githubusercontent.com/LastTime-zt/license-db/main/licenses.json",
            "https://gh-proxy.com/https://raw.githubusercontent.com/LastTime-zt/license-db/main/licenses.json"
    };

    /** 绑定上报用的 GitHub Contents API(PUT 必须走这里, raw 只读) */
    private static final String DB_API =
            "https://api.github.com/repos/LastTime-zt/license-db/contents/licenses.json";

    /**
     * 绑定上报用的写权限 PAT(仅 license-db 仓库 Contents Read&Write)。
     * 实际值在本地文件 PatConfig.java(不入 git, 见 .gitignore);
     * 缺失时构建脚本自动生成空 PAT 版本 = 上报禁用, 仅本地绑定。
     */
    private static final String BMDB_PAT = PatConfig.BMDB_PAT;

    private static android.content.SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE);
    }

    /** 是否已激活且在有效期内(基于本地缓存的到期日) */
    public static boolean isActive(Context ctx) {
        return expires(ctx) != null;
    }

    /** 本地缓存到期日 yyMMdd; 未激活或已过期返回 null */
    public static String expires(Context ctx) {
        String exp = prefs(ctx).getString("license_exp", "");
        if (exp == null || exp.length() != 6) return null;
        return exp.compareTo(today()) >= 0 ? exp : null;
    }

    /** 本机设备码: AndroidID 的 SHA256 前 8 位 hex 大写 */
    public static String deviceId(Context ctx) {
        try {
            String aid = Settings.Secure.getString(ctx.getContentResolver(),
                    Settings.Secure.ANDROID_ID);
            if (aid == null) aid = "unknown";
            byte[] d = MessageDigest.getInstance("SHA-256")
                    .digest(aid.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4; i++) sb.append(String.format("%02X", d[i]));
            return sb.toString();
        } catch (Exception e) {
            return "00000000";
        }
    }

    /**
     * 云端查询注册码。阻塞网络, 必须子线程调用。
     * @return [0]=状态(RS_*) [1]=到期yyMMdd(未知为"") [2]=绑定设备码(未知为"")
     *         [3]=绑定账号(未知为"") [4]=绑定密码(未知为"")
     */
    public static String[] cloudLookup(String code) {
        String norm = normalize(code);
        if (norm.isEmpty()) return new String[]{RS_MISSING, "", "", "", ""};
        for (String u : REMOTE_URLS) {
            try {
                javax.net.ssl.HttpsURLConnection c =
                        (javax.net.ssl.HttpsURLConnection) new java.net.URL(
                                u + "?t=" + System.currentTimeMillis()).openConnection();
                c.setConnectTimeout(6000);
                c.setReadTimeout(6000);
                int resp = c.getResponseCode();
                android.util.Log.d("License", "GET " + u.split("/", 3)[2].split("/", 2)[0]
                        + " -> " + resp);
                if (resp != 200) continue;
                java.io.BufferedReader r = new java.io.BufferedReader(
                        new java.io.InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
                r.close();
                org.json.JSONArray arr = new org.json.JSONObject(sb.toString())
                        .optJSONArray("licenses");
                if (arr == null) continue;
                for (int i = 0; i < arr.length(); i++) {
                    org.json.JSONObject o = arr.getJSONObject(i);
                    if (norm.equals(normalize(o.optString("code", "")))) {
                        String st = RS_BLOCKED.equals(o.optString("status", ""))
                                ? RS_BLOCKED : RS_ACTIVE;
                        return new String[]{st, o.optString("expire", ""),
                                o.optString("device", ""),
                                o.optString("account", ""), o.optString("pwd", "")};
                    }
                }
                return new String[]{RS_MISSING, "", "", "", ""};
            } catch (Exception e) {
                // 尝试下一个镜像
            }
        }
        return new String[]{RS_UNREACHABLE, "", "", "", ""};
    }

    /** 保存授权(云端校验通过后调用): 存注册码 + 到期日缓存 */
    public static void save(Context ctx, String code, String expire) {
        prefs(ctx).edit()
                .putString("license", normalize(code))
                .putString("license_exp", expire)
                .apply();
    }

    /** 清除本地授权 */
    public static void clear(Context ctx) {
        prefs(ctx).edit().remove("license").remove("license_exp").apply();
    }

    /**
     * 启动复查(子线程调用): active 刷新缓存到期日(云端续期免重输);
     * blocked/missing/云端绑定账号与本机不一致 -> 清除授权;
     * 不可达时保留缓存(断网兜底)。
     * 清除原因写入 unbind_reason 供激活界面显示一次。
     */
    public static void revalidate(Context ctx) {
        String lic = prefs(ctx).getString("license", "");
        if (lic == null || lic.isEmpty()) return;
        String[] r = cloudLookup(lic);
        android.util.Log.d("License", "revalidate: status=" + r[0]
                + " expire=" + r[1] + " device=" + r[2]
                + " account=" + mask(r[3]));
        if (RS_ACTIVE.equals(r[0]) && r[1].length() == 6) {
            // 账号一致性: 云端绑定了账号 且 本机已绑定账号 且 不一致 -> 吊销
            String cloudAcc = r[3];
            String localAcc = prefs(ctx).getString("bind_idcard", "");
            if (!cloudAcc.isEmpty() && !localAcc.isEmpty()
                    && !normalize(cloudAcc).equals(normalize(localAcc))) {
                prefs(ctx).edit().putString("unbind_reason",
                        "该注册码已在云端绑定其他账号 ("
                                + mask(cloudAcc) + "), 如有疑问请联系管理员").apply();
                android.util.Log.d("License", "revalidate: 账号不一致, 清除本地授权");
                clear(ctx);
                return;
            }
            prefs(ctx).edit().putString("license_exp", r[1]).apply();
        } else if (!RS_UNREACHABLE.equals(r[0])) {
            prefs(ctx).edit().putString("unbind_reason",
                    RS_BLOCKED.equals(r[0])
                            ? "该注册码已被管理员停用" : "该注册码未在服务器登记").apply();
            android.util.Log.d("License", "revalidate: 清除本地授权");
            clear(ctx);
        }
    }

    /**
     * 云端绑定(双向绑定上报): 把本机登录的账号/密码登记到注册码记录上。
     * 规则: 云端已有绑定且账号不一致 -> 拒绝(CONFLICT);
     *       未配置上报 PAT / 网络失败 -> 降级(仅本地绑定, 不阻塞登录)。
     * 阻塞网络, 必须子线程调用。
     * @return null=绑定完成(含降级); "CONFLICT"=云端已绑定其他账号; 其他=错误描述
     */
    public static String cloudBind(String code, String account, String pwd) {
        String norm = normalize(code);
        if (norm.isEmpty()) return "注册码为空";
        try {
            // GET: 拿当前记录 + blob sha (public 仓库匿名即可, 有 PAT 更稳)
            javax.net.ssl.HttpsURLConnection c =
                    (javax.net.ssl.HttpsURLConnection) new java.net.URL(DB_API).openConnection();
            if (!BMDB_PAT.isEmpty())
                c.setRequestProperty("Authorization", "Bearer " + BMDB_PAT);
            c.setRequestProperty("Accept", "application/vnd.github+json");
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            int rc = c.getResponseCode();
            if (rc != 200) return "云端连接失败 HTTP " + rc;
            String body = readAll(c.getInputStream());
            c.disconnect();
            org.json.JSONObject info = new org.json.JSONObject(body);
            String raw = new String(android.util.Base64.decode(
                    info.getString("content"), android.util.Base64.DEFAULT),
                    StandardCharsets.UTF_8);
            org.json.JSONArray arr = new org.json.JSONObject(raw)
                    .optJSONArray("licenses");
            org.json.JSONObject target = null;
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    org.json.JSONObject o = arr.getJSONObject(i);
                    if (norm.equals(normalize(o.optString("code", "")))) {
                        target = o;
                        break;
                    }
                }
            }
            if (target == null) return "注册码未在服务器登记";
            // 冲突检查: 云端已绑定其他账号
            String exist = target.optString("account", "");
            if (!exist.isEmpty() && !normalize(exist).equals(normalize(account)))
                return "CONFLICT";
            if (BMDB_PAT.isEmpty()) return null;   // 未配置 PAT: 降级为仅本地绑定
            // 无变化则不写
            if (exist.equals(account) && target.optString("pwd", "").equals(pwd))
                return null;
            target.put("account", account);
            target.put("pwd", pwd);
            target.put("updated_at", new java.text.SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss").format(new java.util.Date()));
            // PUT
            org.json.JSONObject put = new org.json.JSONObject();
            put.put("message", "bind account");
            put.put("content", android.util.Base64.encodeToString(
                    new org.json.JSONObject().put("licenses", arr).toString()
                            .getBytes(StandardCharsets.UTF_8),
                    android.util.Base64.NO_WRAP));
            put.put("sha", info.optString("sha", ""));
            javax.net.ssl.HttpsURLConnection w =
                    (javax.net.ssl.HttpsURLConnection) new java.net.URL(DB_API).openConnection();
            w.setRequestMethod("PUT");
            w.setRequestProperty("Authorization", "Bearer " + BMDB_PAT);
            w.setRequestProperty("Accept", "application/vnd.github+json");
            w.setDoOutput(true);
            w.setConnectTimeout(8000);
            w.setReadTimeout(15000);
            w.getOutputStream().write(put.toString().getBytes(StandardCharsets.UTF_8));
            int wc = w.getResponseCode();
            w.getInputStream().close();
            if (wc >= 300) return "云端写入失败 HTTP " + wc;
            android.util.Log.d("License", "cloudBind OK");
            return null;
        } catch (Exception e) {
            return "网络异常: " + (e.getMessage() != null
                    ? e.getMessage() : e.getClass().getSimpleName());
        }
    }

    /** 账号脱敏: 前4 + **** + 后4 */
    private static String mask(String id) {
        if (id == null || id.length() <= 8) return id;
        return id.substring(0, 4) + "****" + id.substring(id.length() - 4);
    }

    private static String readAll(java.io.InputStream in) throws Exception {
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        return new String(bo.toByteArray(), StandardCharsets.UTF_8);
    }

    /** "yyMMdd" -> 当天 23:59:59 的毫秒时间戳 (UI 算剩余天数) */
    public static long ymdPublic(String s) {
        try {
            java.util.Calendar c = java.util.Calendar.getInstance();
            c.clear();
            int yy = Integer.parseInt(s.substring(0, 2));
            c.set(2000 + yy, Integer.parseInt(s.substring(2, 4)) - 1,
                    Integer.parseInt(s.substring(4, 6)), 23, 59, 59);
            return c.getTimeInMillis();
        } catch (Exception e) {
            return 0L;
        }
    }

    /** 去掉分隔符和空白, 转大写 */
    static String normalize(String s) {
        return s == null ? "" : s.replace("-", "").replace(" ", "").toUpperCase();
    }

    private static String today() {
        return new java.text.SimpleDateFormat("yyMMdd")
                .format(new java.util.Date());
    }
}
