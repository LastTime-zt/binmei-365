package com.bm.auto;

import android.content.Context;
import android.provider.Settings;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * 注册码授权: 绑定设备 + 有效期。
 * 注册码 = Base32(AES-128-ECB( "V1|设备码|到期日" )) 格式化为 XXXX-XXXX-XXXX-XXXX
 * 密钥由 PC 端生成器与 APP 共享(Java 层, 防小白级别)。
 */
public final class License {

    // 与 PC 端 keygen_license.py 共享的密钥(16字节)。改这里需同步改脚本。
    private static final byte[] KEY =
            "bM@2026!Lx#Yd$Kz".getBytes(StandardCharsets.UTF_8);

    /** 是否已激活且在有效期内 */
    public static boolean isActive(Context ctx) {
        String exp = expires(ctx);
        return exp != null;
    }

    /** 返回到期日 yyMMdd; 未激活返回 null */
    public static String expires(Context ctx) {
        String lic = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
                .getString("license", "");
        if (lic == null || lic.isEmpty()) return null;
        String plain = decrypt(normalize(lic));
        if (plain == null) return null;
        // 明文: "1" + 设备码(8) + 到期yyMMdd(6) = 15字节
        if (plain.length() != 15 || plain.charAt(0) != '1') return null;
        if (!plain.substring(1, 9).equals(deviceId(ctx))) return null;
        try {
            long exp = ymd(plain.substring(9));
            if (System.currentTimeMillis() > exp) return null;
            return plain.substring(9);
        } catch (Exception e) {
            return null;
        }
    }

    /** 验证并保存注册码; 返回错误信息, null=成功 */
    public static String activate(Context ctx, String code) {
        String norm = normalize(code);
        String plain = decrypt(norm);
        if (plain == null) return "注册码无效";
        if (plain.length() != 15 || plain.charAt(0) != '1') return "注册码格式错误";
        if (!plain.substring(1, 9).equals(deviceId(ctx))) return "注册码与本机不匹配";
        long exp;
        try {
            exp = ymd(plain.substring(9));
        } catch (Exception e) {
            return "注册码格式错误";
        }
        if (System.currentTimeMillis() > exp) return "注册码已过期";
        ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
                .edit().putString("license", norm).apply();
        return null;
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

    // ---------- 内部 ----------

    /** "yyMMdd" -> 当天 23:59:59 的毫秒时间戳 (公开给UI算剩余天数) */
    public static long ymdPublic(String s) {
        try {
            return ymd(s);
        } catch (Exception e) {
            return 0L;
        }
    }

    /** "yyMMdd" -> 当天 23:59:59 的毫秒时间戳 */
    private static long ymd(String s) throws Exception {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.clear();
        int yy = Integer.parseInt(s.substring(0, 2));
        c.set(2000 + yy, Integer.parseInt(s.substring(2, 4)) - 1,
                Integer.parseInt(s.substring(4, 6)), 23, 59, 59);
        return c.getTimeInMillis();
    }

    private static String decrypt(String code) {
        try {
            byte[] data = base32Decode(code);
            if (data == null || data.length != 16) return null;
            Cipher c = Cipher.getInstance("AES/ECB/PKCS5Padding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(KEY, "AES"));
            return new String(c.doFinal(data), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    /** 去掉分隔符和空白, 转大写 */
    static String normalize(String s) {
        return s == null ? "" : s.replace("-", "").replace(" ", "").toUpperCase();
    }

    // RFC4648 Base32 (A-Z 2-7), 无填充
    private static final char[] B32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();

    static byte[] base32Decode(String s) {
        int len = s.length();
        int outLen = len * 5 / 8;
        byte[] out = new byte[outLen];
        int bitBuf = 0, bits = 0, idx = 0;
        for (char ch : s.toCharArray()) {
            int v = val(ch);
            if (v < 0) return null;
            bitBuf = (bitBuf << 5) | v;
            bits += 5;
            if (bits >= 8) {
                out[idx++] = (byte) (bitBuf >> (bits - 8));
                bits -= 8;
            }
        }
        return out;
    }

    private static int val(char c) {
        for (int i = 0; i < B32.length; i++) {
            if (B32[i] == c) return i;
        }
        return -1;
    }
}
