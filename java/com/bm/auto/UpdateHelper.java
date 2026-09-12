package com.bm.auto;

import android.os.Environment;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
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
 */
public final class UpdateHelper {

    // GitHub 仓库
    private static final String GH_REPO = "LastTime-zt/binmei-365";

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
                        + GH_REPO + "/releases/latest");
                if (latest == null) { postMain(act, () -> cb.onResult(null)); return; }
                String tagName = latest.optString("tag_name", "");
                int remoteVc = 0;
                try {
                    String num = tagName.replaceFirst("^v", "").replaceAll("[^0-9]", "");
                    remoteVc = Integer.parseInt(num);
                } catch (Exception ignore) {}
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
                final UpdateInfo info = new UpdateInfo(remoteVc, verName, size, dlUrl, body,
                        remoteVc > localVc);
                postMain(act, () -> cb.onResult(info));
            } catch (Exception e) {
                postMain(act, () -> cb.onResult(null));
            }
        }).start();
    }

    /** 在主线程执行 runnable */
    private static void postMain(Activity act, Runnable r) {
        new Handler(act.getMainLooper()).post(r);
    }

    /** 下载 APK 到 Download 目录并返回 file (调用方负责安装)。
     *  国内直连 GitHub 下载 CDN 常被墙, 依次尝试加速镜像 */
    public static File downloadApk(Context ctx, String url, Progress cb) {
        String[] candidates = new String[] {
                url,
                "https://ghfast.top/" + url,
                "https://mirror.ghproxy.com/" + url,
                "https://gh-proxy.com/" + url
        };
        File out = null;
        String err = null;
        for (String u : candidates) {
            out = tryDownload(ctx, u, cb);
            if (out != null) return out;
            err = "all mirrors failed";
        }
        if (cb != null) {
            final String e = err;
            new Handler(ctx.getMainLooper()).post(() -> cb.onFail(e));
        }
        return null;
    }

    /** 单个地址尝试下载 */
    private static File tryDownload(Context ctx, String url, Progress cb) {
        // 写入公开 Download 目录，Android 10+ 需要 WRITE_EXTERNAL_STORAGE
        File downloadDir = new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "");
        if (!downloadDir.exists() && !downloadDir.mkdirs()) {
            downloadDir = ctx.getExternalFilesDir(null);
        }
        File out = new File(downloadDir, "AutoAnswer_update.apk");
        try {
            HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
            con.setRequestProperty("Accept", "*/*");
            con.setRequestProperty("User-Agent", "Mozilla/5.0");
            con.setConnectTimeout(15000);
            con.setReadTimeout(60000);
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
            if (written <= 0) return null;   // 空文件视为失败, 尝试下一镜像
            if (cb != null) new Handler(ctx.getMainLooper()).post(cb::onDone);
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    /** 下载完成后启动安装 Intent */
    public static void installApk(Context ctx, File apk) {
        if (apk == null || !apk.exists()) return;
        Uri uri = Uri.fromFile(apk);
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, "application/vnd.android.package-archive");
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(intent);
    }

    // ---- 内部辅助 ----

    private static JSONObject fetchJson(String urlStr) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(urlStr).openConnection();
        con.setRequestProperty("Accept", "application/vnd.github.v3+json");
        con.setConnectTimeout(10000);
        con.connect();
        int code = con.getResponseCode();
        if (code != 200) return null;
        InputStream is = con.getInputStream();
        String enc = con.getContentEncoding();
        if ("gzip".equalsIgnoreCase(enc)) is = new GZIPInputStream(is);
        byte[] b = new byte[8192];
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        int n;
        while ((n = is.read(b)) != -1) baos.write(b, 0, n);
        is.close();
        return new JSONObject(baos.toString("UTF-8"));
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
