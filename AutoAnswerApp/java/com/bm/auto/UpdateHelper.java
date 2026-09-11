package com.bm.auto;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.zip.GZIPInputStream;

/**
 * GitHub 自动更新器:
 * - 查最新 Release 的 version_code(与 AndroidManifest versionCode 对齐)
 * - 对比本地 versionCode, 有新版本时下载 APK 并提示安装
 * - Token 放在常量里, 首次上传 APK 时需手动 push 一次 Release, 之后全自动
 */
public final class UpdateHelper {

    // GitHub 仓库 & Token
    private static final String GH_REPO = "LastTime-zt/binmei-365";
    private static final String GH_TOKEN =
            "YOUR_GH_TOKEN_HERE";

    /** 回调 */
    public interface Callback {
        void onResult(UpdateInfo info);
    }

    /** 版本信息 */
    public static class UpdateInfo {
        public final int remoteVersion;   // 远程 versionCode
        public final String remoteVerName;
        public final long apkSize;        // bytes
        public final String downloadUrl;  // direct link
        public final String changelog;
        public final boolean newer;       // true=需要更新

        UpdateInfo(int remote, String verName, long size, String url, String log, boolean newer) {
            this.remoteVersion = remote;
            this.remoteVerName = verName;
            this.apkSize = size;
            this.downloadUrl = url;
            this.changelog = log;
            this.newer = newer;
        }
    }

    /**
     * 后台请求 GitHub 最新 Release, 回调返回 UpdateInfo
     */
    public static void checkLatest(Activity act, Callback cb) {
        new Thread(() -> {
            try {
                JSONObject latest = fetchJson("https://api.github.com/repos/"
                        + GH_REPO + "/releases/latest", true);
                if (latest == null) { cb.onResult(null); return; }
                String tagName = latest.optString("tag_name", "");
                int remoteVc = 0;
                try { remoteVc = Integer.parseInt(tagName.replaceFirst("^v", "")); } catch (Exception ignore) {}
                String verName = latest.optString("name", "");
                String body = latest.optString("body", "");
                JSONArray assets = latest.optJSONArray("assets");
                String dlUrl = "";
                long size = 0;
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject a = assets.getJSONObject(i);
                    if (a.optString("name", "").endsWith(".apk")) {
                        dlUrl = a.optString("browser_download_url");
                        size = a.optLong("size", 0);
                        break;
                    }
                }
                int localVc = getLocalVersionCode(act.getApplicationContext());
                cb.onResult(new UpdateInfo(remoteVc, verName, size, dlUrl, body,
                        remoteVc > localVc));
            } catch (Exception e) {
                cb.onResult(null);
            }
        }).start();
    }

    /** 下载 APK 到外部存储并返回 file (调用方负责安装) */
    public static File downloadApk(Context ctx, String url, Progress cb) {
        File out = new File(ctx.getExternalFilesDir(null), "AutoAnswer_update.apk");
        try {
            HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
            con.setRequestProperty("Accept", "*/*");
            con.setRequestProperty("User-Agent", "Mozilla/5.0");
            con.setConnectTimeout(30000);
            con.setReadTimeout(120000);
            con.connect();
            InputStream is = con.getInputStream();
            // 处理 gzip
            String enc = con.getContentEncoding();
            if ("gzip".equalsIgnoreCase(enc)) {
                is = new GZIPInputStream(is);
            }
            long total = con.getContentLengthLong();
            byte[] buf = new byte[65536];
            long written = 0;
            try (FileOutputStream fos = new FileOutputStream(out)) {
                int n;
                while ((n = is.read(buf)) != -1) {
                    fos.write(buf, 0, n);
                    written += n;
                    if (total > 0 && cb != null) {
                        final long w = written;
                        final long t = total;
                        new Handler(ctx.getMainLooper()).post(() -> cb.onProgress((int) (w * 100 / t)));
                    }
                }
            }
            is.close();
            if (cb != null) new Handler(ctx.getMainLooper()).post(cb::onDone);
            return out;
        } catch (Exception e) {
            if (cb != null) new Handler(ctx.getMainLooper()).post(() -> cb.onFail(e.getMessage()));
            return null;
        }
    }

    /** 下载完成后启动安装 Intent */
    public static void installApk(Context ctx, File apk) {
        if (apk == null || !apk.exists()) return;
        Uri uri = Uri.fromFile(apk);
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, "application/vnd.android.package-archive");
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        ctx.startActivity(intent);
    }

    // ---- 内部辅助 ----

    private static JSONObject fetchJson(String urlStr, boolean auth) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(urlStr).openConnection();
        con.setRequestProperty("Accept", "application/vnd.github.v3+json");
        if (auth) con.setRequestProperty("Authorization", "Bearer " + GH_TOKEN);
        con.setConnectTimeout(15000);
        con.connect();
        InputStream is = con.getInputStream();
        String enc = con.getContentEncoding();
        if ("gzip".equalsIgnoreCase(enc)) is = new GZIPInputStream(is);
        byte[] b = new byte[8192];
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        int n;
        while ((n = is.read(b)) != -1) baos.write(b, 0, n);
        is.close();
        if (con.getResponseCode() == 200) {
            return new JSONObject(baos.toString("UTF-8"));
        }
        return null;
    }

    private static int getLocalVersionCode(Context ctx) {
        try {
            return ctx.getPackageManager()
                    .getPackageInfo(ctx.getPackageName(), 0).versionCode;
        } catch (Exception e) { return 0; }
    }

    /** 进度回调 */
    public interface Progress {
        void onProgress(int pct);
        void onDone();
        void onFail(String msg);
    }
}
