package com.bm.auto;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.content.Context;

import java.io.File;

/** 极简APK文件Provider(替代androidx.FileProvider, 项目无androidx依赖)。
 *  content://com.bm.auto.apk/download/<文件名> -> 公共Download目录(回退应用私有外部目录) */
public class ApkProvider extends ContentProvider {

    @Override public boolean onCreate() { return true; }

    private File resolve(String name) {
        File f = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), name);
        if (!f.exists()) {
            f = new File(getContext().getExternalFilesDir(null), name);
        }
        return f;
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws java.io.FileNotFoundException {
        String name = new File(uri.getPath()).getName();   // 防路径穿越, 只取文件名
        File f = resolve(name);
        if (!f.exists()) throw new java.io.FileNotFoundException(name);
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public String getType(Uri uri) { return "application/vnd.android.package-archive"; }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                  String[] selectionArgs, String sortOrder) { return null; }

    @Override public Uri insert(Uri uri, ContentValues values) { return null; }

    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }

    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
}
