package com.bm.auto;

import android.app.Activity;
import android.content.Intent;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;

public class MainActivity extends Activity {

    private TextView tvA11y, tvOverlay, tvProgress, tvInfo, tvDaily, tvExam, tvLicense, tvTitle;
    private Button bPause, bSubmit, bUpdateCheck;
    private android.widget.ProgressBar pbDownload;
    private TextView tvDownloadStatus;
    private android.app.AlertDialog dlDialog;
    private final Handler h = new Handler(Looper.getMainLooper());
    private boolean uiUpdating = false;
    private boolean autoExamChecked = false;
    private android.content.SharedPreferences sp;
    private LinearLayout modeBlock;   // 方式一(操作界面)专属权限区块
    private LinearLayout httpBlock;   // 方式二(协议答题)专属区块
    private Runnable refreshPtsRef;   // 积分概况刷新器(任务完成后自动刷新)

    /** 方式一专属区块: 权限 + 答题设置; 方式二时整体隐藏。每次重建防累积 */
    private void buildModeBlock(LinearLayout block, float d) {
        block.removeAllViews();   // 修复: 反复切换方式1/方式2时内容累积
        boolean appMode = "app".equals(sp.getString("answer_mode", "http"));
        block.setVisibility(appMode ? View.VISIBLE : View.GONE);
        if (!appMode) return;

        // 权限卡片
        LinearLayout card = mkCard(block, "方式一权限 (操作界面答题)");
        tvA11y = mkStatus("无障碍服务: 检测中...");
        card.addView(tvA11y);
        Button bA11y = mkBtn("去开启无障碍服务");
        bA11y.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            }
        });
        card.addView(bA11y);

        tvOverlay = mkStatus("悬浮窗权限: 检测中...");
        card.addView(tvOverlay);
        Button bOverlay = mkBtn("去授权悬浮窗");
        bOverlay.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + getPackageName())));
                } catch (Exception e) {
                    startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
                }
            }
        });
        card.addView(bOverlay);

        Switch swFloat = new Switch(this);
        swFloat.setTextSize(16);
        swFloat.setText("答题页悬浮控制面板");
        swFloat.setChecked(sp.getBoolean("float", true));
        swFloat.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean c) {
                sp.edit().putBoolean("float", c).apply();
            }
        });
        card.addView(swFloat);

        // 答题设置卡片(仅方式一使用)
        LinearLayout cardSw = mkCard(block, "答题设置");
        Switch swEnabled = new Switch(this);
        swEnabled.setTextSize(14);
        swEnabled.setPadding(0, (int) (4 * d), 0, 0);
        swEnabled.setText("自动答题 (总开关)");
        swEnabled.setChecked(sp.getBoolean("enabled", true));
        swEnabled.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean c) {
                sp.edit().putBoolean("enabled", c).apply();
            }
        });
        cardSw.addView(swEnabled);

        Switch swFast = new Switch(this);
        swFast.setTextSize(14);
        swFast.setText("快速模式 (每题约1秒, 慢速约5秒)");
        swFast.setChecked(sp.getBoolean("fast", true));
        swFast.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean c) {
                sp.edit().putBoolean("fast", c).apply();
            }
        });
        cardSw.addView(swFast);
    }

    /** 方式二专属区块: 积分概况 + 账号登录 + 题库更新 + 阅读/一键日常 + 模拟考试。每次重建防累积 */
    private void buildHttpBlock(final LinearLayout block, float d) {
        block.removeAllViews();
        boolean httpMode = !"app".equals(sp.getString("answer_mode", "http"));
        block.setVisibility(httpMode ? View.VISIBLE : View.GONE);
        if (!httpMode) return;

        // 积分概况卡(与平台签到页字段一致)
        LinearLayout cardPts = mkCard(block, "积分概况");
        final TextView tvPts = new TextView(this);
        tvPts.setTextSize(14);
        tvPts.setTextColor(0xFF444444);
        tvPts.setLineSpacing(0, 1.35f);
        tvPts.setText("加载中...");
        cardPts.addView(tvPts);

        Button bRefreshPts = mkBtn("刷新积分");
        final Runnable[] refreshPts = new Runnable[1];
        refreshPts[0] = new Runnable() {
            @Override public void run() {
                final android.content.Context app = getApplicationContext();
                new Thread(new Runnable() {
                    @Override public void run() {
                        final BankUpdater.SignInInfo info = BankUpdater.signInInfo(app);
                        final JSONObject done = BankUpdater.todayPoints(app);
                        final BankUpdater.PersonInfo me = BankUpdater.personInfo(app);
                        final int pStudy = done.optInt("知识学习", 0);
                        final int pPractice = done.optInt("手机练习", 0);
                        h.post(new Runnable() {
                            @Override public void run() {
                                if (info == null) {
                                    tvPts.setText("积分信息拉取失败 (检查账号登录)");
                                    tvPts.setTextColor(0xFFC62828);
                                } else {
                                    String head = me != null
                                            ? me.name + "    " + maskId(me.idcard)
                                            + "    " + me.tel + "\n"
                                            : "";
                                    tvPts.setText(head
                                            + "总积分: " + info.total
                                            + "    今日: +" + info.todayPoints
                                            + (info.signed ? "    已签到 ✓" : "    未签到")
                                            + "\n连签: " + info.streak + " 天"
                                            + "    明日签到: +" + info.nextPoint
                                            + "\n今日阅读: " + pStudy + "/15 篇"
                                            + "    今日练习: " + pPractice + "/15 次 ("
                                            + info.practiced + " 题)");
                                    tvPts.setTextColor(0xFF444444);
                                }
                                // 标题公司名动态更新
                                String comp = BankUpdater.cachedCompany();
                                if (comp != null && tvTitle != null
                                        && !comp.equals(tvTitle.getText().toString())) {
                                    tvTitle.setText("行藏有度-" + comp);
                                }
                                bRefreshPts.setEnabled(true);
                                bRefreshPts.setText("刷新积分");
                            }
                        });
                    }
                }).start();
            }
        };
        bRefreshPts.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                bRefreshPts.setEnabled(false);
                bRefreshPts.setText("刷新中...");
                refreshPts[0].run();
            }
        });
        cardPts.addView(bRefreshPts);
        refreshPts[0].run();   // 首次加载
        refreshPtsRef = refreshPts[0];

        // 账号登录卡
        LinearLayout cardAcc = mkCard(block, "账号登录");
        final android.widget.EditText etId = mkInput();
        etId.setHint("账号 (身份证号)");
        etId.setText(sp.getString("idcard", ""));
        cardAcc.addView(etId);

        final android.widget.EditText etPwd = mkInput();
        etPwd.setHint("密码");
        etPwd.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        etPwd.setText(sp.getString("password", ""));
        cardAcc.addView(etPwd);

        // 记住密码开关: 关闭时清除已存密码
        Switch swRemember = new Switch(this);
        swRemember.setTextSize(14);
        swRemember.setText("记住密码");
        swRemember.setChecked(sp.getBoolean("remember_pwd", true));
        swRemember.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean c) {
                sp.edit().putBoolean("remember_pwd", c).apply();
                if (!c) sp.edit().remove("password").apply();
            }
        });
        cardAcc.addView(swRemember);

        // 账号实时记忆; 密码仅在"记住密码"开启时保存
        android.text.TextWatcher saveAcc = new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(android.text.Editable s) {
                android.content.SharedPreferences.Editor ed = sp.edit()
                        .putString("idcard", etId.getText().toString().trim());
                if (sp.getBoolean("remember_pwd", true)) {
                    ed.putString("password", etPwd.getText().toString().trim());
                }
                ed.apply();
            }
        };
        etId.addTextChangedListener(saveAcc);
        etPwd.addTextChangedListener(saveAcc);

        // 登录按钮: 校验账号密码 -> 清除旧登录态 -> 重新登录 -> 刷新积分/标题等全部信息
        Button bLogin = mkBtn("登 录", true);
        bLogin.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                final String id = etId.getText().toString().trim();
                final String pwd = etPwd.getText().toString().trim();
                if (id.isEmpty() || pwd.isEmpty()) {
                    Toast.makeText(MainActivity.this, "请输入账号和密码",
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                // 已绑定其他账号时禁止登录新账号
                String bound = sp.getString("bind_idcard", "");
                if (!bound.isEmpty() && !bound.equals(id)) {
                    Toast.makeText(MainActivity.this, "该授权已绑定账号 "
                            + maskId(bound) + ", 不能登录其他账号",
                            Toast.LENGTH_LONG).show();
                    return;
                }
                bLogin.setEnabled(false);
                bLogin.setText("登录中...");
                final android.content.Context app = getApplicationContext();
                new Thread(new Runnable() {
                    @Override public void run() {
                        String err = null;
                        try {
                            // 清旧登录态强制重登, ensureLogin 用界面账号换取新 pid
                            sp.edit().remove("login_pid").apply();
                            java.util.Map<String, String> jar = new java.util.HashMap<>();
                            BankUpdater.ensureLogin(app, jar);
                        } catch (Exception e) {
                            err = e.getMessage() != null
                                    ? e.getMessage()
                                    : e.getClass().getSimpleName();
                        }
                        final String fErr = err;
                        h.post(new Runnable() {
                            @Override public void run() {
                                bLogin.setEnabled(true);
                                bLogin.setText("登 录");
                                if (fErr != null) {
                                    Toast.makeText(MainActivity.this,
                                            "登录失败: " + fErr,
                                            Toast.LENGTH_LONG).show();
                                } else {
                                    // 记录登录成功的账号, 供答题一致性校验
                                    sp.edit().putString("login_idcard", id)
                                            .putString("bind_idcard",
                                                    sp.getString("bind_idcard", "")
                                                            .isEmpty() ? id
                                                            : sp.getString("bind_idcard", ""))
                                            .apply();
                                    Toast.makeText(MainActivity.this,
                                            "登录成功", Toast.LENGTH_SHORT).show();
                                    // 刷新所有信息: 积分概况 + 用户信息 + 标题公司名
                                    if (refreshPtsRef != null) refreshPtsRef.run();
                                }
                            }
                        });
                    }
                }).start();
            }
        });
        cardAcc.addView(bLogin);

        TextView tvSaveHint = new TextView(this);
        tvSaveHint.setTextSize(12);
        tvSaveHint.setTextColor(0xFF8A94A0);
        tvSaveHint.setPadding(0, (int) (4 * d), 0, 0);
        String boundId = sp.getString("bind_idcard", "");
        tvSaveHint.setText(boundId.isEmpty()
                ? "账号自动保存在本机; 开启记住密码后下次免输入\n首次开始答题后将绑定该账号, 不能更换"
                : "已绑定账号: " + maskId(boundId) + " (不可更换)");
        tvSaveHint.setTextColor(boundId.isEmpty() ? 0xFF8A94A0 : 0xFF1B8A3A);
        cardAcc.addView(tvSaveHint);

        // 题库定时更新卡
        LinearLayout cardUpd = mkCard(block, "题库定时更新");
        LinearLayout rowUpd = new LinearLayout(this);
        rowUpd.setOrientation(LinearLayout.HORIZONTAL);
        rowUpd.setGravity(Gravity.CENTER_VERTICAL);
        TextView tvHours = new TextView(this);
        tvHours.setText("间隔(小时,0=关闭)");
        tvHours.setTextSize(14);
        tvHours.setTextColor(0xFF444444);
        rowUpd.addView(tvHours);
        final android.widget.EditText etHours = mkInput();
        etHours.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        etHours.setText(String.valueOf(sp.getLong("update_hours", 24)));
        LinearLayout.LayoutParams lpHours = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lpHours.leftMargin = (int) (10 * d);
        etHours.setLayoutParams(lpHours);
        rowUpd.addView(etHours);
        cardUpd.addView(rowUpd);

        TextView tvUpdHint = new TextView(this);
        tvUpdHint.setTextSize(12);
        tvUpdHint.setTextColor(0xFF8A94A0);
        tvUpdHint.setPadding(0, (int) (4 * d), 0, 0);
        tvUpdHint.setText("自动从培训平台拉取最新题目与标准答案, 无需配置");
        cardUpd.addView(tvUpdHint);

        Button bSaveUpd = mkBtn("保存并立即更新", true);
        bSaveUpd.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!ensureLoginUi()) return;
                long hours;
                try {
                    hours = Long.parseLong(etHours.getText().toString().trim());
                } catch (Exception e) {
                    hours = 24;
                }
                sp.edit().putLong("update_hours", hours).apply();
                triggerBankUpdate();
            }
        });
        cardUpd.addView(bSaveUpd);

        // 一键日常任务(账号登录卡内): 签到 + 随机练习10题 + 阅读15篇(浏览任务)
        Button bDaily = mkBtn("一键日常(签到+随机练习+浏览15篇)", true);
        bDaily.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!ensureLoginUi()) return;
                if (BankUpdater.dailyRunning) {
                    Toast.makeText(MainActivity.this, "日常任务已在进行中",
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                Toast.makeText(MainActivity.this, "开始日常任务: 签到→练习→阅读...",
                        Toast.LENGTH_SHORT).show();
                final android.content.Context app = getApplicationContext();
                new Thread(new Runnable() {
                    @Override public void run() {
                        final BankUpdater.Result r = BankUpdater.dailyTasks(app);
                        h.post(new Runnable() {
                            @Override public void run() {
                                Toast.makeText(MainActivity.this, r.message,
                                        Toast.LENGTH_LONG).show();
                                if (refreshPtsRef != null) refreshPtsRef.run();
                            }
                        });
                    }
                }).start();
            }
        });
        cardAcc.addView(bDaily);

        tvDaily = new TextView(this);
        tvDaily.setTextSize(13);
        tvDaily.setTextColor(0xFF555555);
        tvDaily.setPadding(0, (int) (6 * d), 0, 0);
        cardAcc.addView(tvDaily);

        // 自动答题卡(模拟考试)
        LinearLayout cardExam = mkCard(block, "自动答题 (模拟考试)");
        LinearLayout rowExam = new LinearLayout(this);
        rowExam.setOrientation(LinearLayout.HORIZONTAL);
        rowExam.setGravity(Gravity.CENTER_VERTICAL);
        TextView tvExamCnt = new TextView(this);
        tvExamCnt.setText("答题张数(0=全部)");
        tvExamCnt.setTextSize(14);
        tvExamCnt.setTextColor(0xFF444444);
        rowExam.addView(tvExamCnt);
        final android.widget.EditText etExamCnt = mkInput();
        etExamCnt.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        etExamCnt.setText(String.valueOf(sp.getInt("exam_count", 1)));
        LinearLayout.LayoutParams lpExam = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lpExam.leftMargin = (int) (10 * d);
        etExamCnt.setLayoutParams(lpExam);
        rowExam.addView(etExamCnt);
        cardExam.addView(rowExam);

        // 答题延迟(秒): 每题作答前停留时间, 默认1秒
        LinearLayout rowDelay = new LinearLayout(this);
        rowDelay.setOrientation(LinearLayout.HORIZONTAL);
        rowDelay.setGravity(Gravity.CENTER_VERTICAL);
        TextView tvDelay = new TextView(this);
        tvDelay.setText("答题延迟(秒/题)");
        tvDelay.setTextSize(14);
        tvDelay.setTextColor(0xFF444444);
        rowDelay.addView(tvDelay);
        final android.widget.EditText etDelay = mkInput();
        etDelay.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        etDelay.setText(String.valueOf(sp.getInt("answer_delay", 1)));
        LinearLayout.LayoutParams lpDelay = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lpDelay.leftMargin = (int) (10 * d);
        etDelay.setLayoutParams(lpDelay);
        rowDelay.addView(etDelay);
        cardExam.addView(rowDelay);

        TextView tvDelayHint = new TextView(this);
        tvDelayHint.setTextSize(12);
        tvDelayHint.setTextColor(0xFF8A94A0);
        tvDelayHint.setPadding(0, (int) (4 * d), 0, 0);
        tvDelayHint.setText("每题作答前停留的秒数, 改小答题更快, 改大更拟人");
        cardExam.addView(tvDelayHint);

        LinearLayout rowExamBtn = new LinearLayout(this);
        rowExamBtn.setOrientation(LinearLayout.HORIZONTAL);
        Button bExam = mkBtn("开始自动答题", true);
        bExam.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!ensureLoginUi()) return;
                startExam(etId, etPwd, etExamCnt, etDelay, false);
            }
        });
        rowExamBtn.addView(bExam);

        Button bPick = mkBtn("选择试卷");
        bPick.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!ensureLoginUi()) return;
                pickPaper();
            }
        });
        rowExamBtn.addView(bPick);
        cardExam.addView(rowExamBtn);

        Switch swAutoExam = new Switch(this);
        swAutoExam.setTextSize(14);
        swAutoExam.setText("打开APP自动答题(每天一次)");
        swAutoExam.setChecked(sp.getBoolean("auto_exam", false));
        swAutoExam.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean c) {
                sp.edit().putBoolean("auto_exam", c).apply();
            }
        });
        cardExam.addView(swAutoExam);

        tvExam = new TextView(this);
        tvExam.setTextSize(13);
        tvExam.setTextColor(0xFF555555);
        tvExam.setPadding(0, (int) (6 * d), 0, 0);
        cardExam.addView(tvExam);
    }

    /** 切换答题方式后重建界面(显隐权限区块) */
    /** 切换答题方式后重建两个方式区块 */
    private void rebuildForMode() {
        float d = getResources().getDisplayMetrics().density;
        if (modeBlock != null) buildModeBlock(modeBlock, d);
        if (httpBlock != null) buildHttpBlock(httpBlock, d);
    }

    /** 刷新授权状态行: 到期时间 + 剩余天数, <=7天橙色提醒 */
    private void updateLicenseText() {
        if (tvLicense == null) return;
        String exp = License.expires(this);
        if (exp == null) {
            tvLicense.setText("授权状态: ✗ 未激活");
            tvLicense.setTextColor(0xFFC62828);
            return;
        }
        String full = "20" + exp.substring(0, 2) + "-" + exp.substring(2, 4)
                + "-" + exp.substring(4, 6) + " 23:59:59";
        long remain = License.ymdPublic(exp) - System.currentTimeMillis();
        long days = remain / 86400000L;
        String text = "授权至: " + full + " (剩余 " + days + " 天)";
        if (days <= 7) {
            text += " · 即将到期, 请续费";
            tvLicense.setTextColor(0xFFE65100);
        } else {
            tvLicense.setTextColor(0xFF1B8A3A);
        }
        tvLicense.setText(text);
        // 点击授权行(含"即将到期, 请续费") -> 弹出联系方式二维码 + 设备码复制
        tvLicense.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showContactDialog(); }
        });
    }

    /** 联系方式弹窗: 微信二维码 + 设备码一键复制 */
    private void showContactDialog() {
        float d = den();
        android.app.AlertDialog.Builder bd = new android.app.AlertDialog.Builder(this);
        bd.setTitle("联系方式 (微信扫码添加)");
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding((int) (20 * d), (int) (10 * d), (int) (20 * d), (int) (16 * d));

        android.widget.ImageView iv = new android.widget.ImageView(this);
        iv.setImageResource(R.drawable.contact_qr);
        android.widget.LinearLayout.LayoutParams lpIv = new android.widget.LinearLayout.LayoutParams(
                (int) (240 * d), (int) (300 * d));
        lpIv.gravity = Gravity.CENTER;
        iv.setLayoutParams(lpIv);
        iv.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        box.addView(iv);

        TextView tvDev = new TextView(this);
        tvDev.setText("设备码: " + License.deviceId(this));
        tvDev.setTextSize(14);
        tvDev.setTextColor(0xFF222222);
        tvDev.setPadding(0, (int) (12 * d), 0, 0);
        box.addView(tvDev);

        Button bCopy = mkBtn("一键复制设备码", true);
        bCopy.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String dev = License.deviceId(MainActivity.this);
                android.content.ClipboardManager cm =
                        (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                cm.setPrimaryClip(android.content.ClipData.newPlainText("dev", dev));
                Toast.makeText(MainActivity.this, "已复制: " + dev,
                        Toast.LENGTH_SHORT).show();
            }
        });
        box.addView(bCopy);
        bd.setView(box);
        bd.setNegativeButton("关闭", null);
        bd.show();
    }

    private static boolean isA11yOn(Activity a) {
        try {
            String s = Settings.Secure.getString(a.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return s != null && s.contains("com.bm.auto");
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // ---- 授权门禁: 未激活时只显示授权界面 ----
        if (!License.isActive(this)) {
            buildLicenseUi();
            return;
        }
        buildMainUi();
    }

    /** yyMMdd -> yyyy-MM-dd */
    private static String curFmt(String exp) {
        return exp.substring(0, 2) + "-" + exp.substring(2, 4)
                + "-" + exp.substring(4, 6);
    }

    /** 注册码授权界面(激活前唯一界面) */
    private void buildLicenseUi() {
        float d = den();
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(getDrawableRes(R.drawable.bg_root));

        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setPadding((int) (24 * d), (int) (90 * d), (int) (24 * d), 0);
        root.addView(ll);

        TextView title = new TextView(this);
        title.setText("行藏有度 · 授权激活");
        title.setTextSize(22);
        title.setTextColor(0xFF1A66C2);
        title.getPaint().setFakeBoldText(true);
        ll.addView(title);

        // 设备码卡片
        LinearLayout card = mkCard(ll, null);
        TextView tvDev = new TextView(this);
        tvDev.setText("本机设备码\n" + License.deviceId(this));
        tvDev.setTextSize(16);
        tvDev.setTextColor(0xFF222222);
        tvDev.getPaint().setFakeBoldText(true);
        tvDev.setPadding(0, 0, 0, (int) (10 * d));
        card.addView(tvDev);

        TextView tvHint = new TextView(this);
        tvHint.setText("请把设备码发给管理员获取注册码");
        tvHint.setTextSize(13);
        tvHint.setTextColor(0xFF8A94A0);
        card.addView(tvHint);

        Button bCopy = mkBtn("一键复制设备码", true);
        bCopy.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String dev = License.deviceId(MainActivity.this);
                android.content.ClipboardManager cm =
                        (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                cm.setPrimaryClip(android.content.ClipData.newPlainText("dev", dev));
                Toast.makeText(MainActivity.this, "已复制: " + dev,
                        Toast.LENGTH_SHORT).show();
            }
        });
        card.addView(bCopy);

        final android.widget.EditText etLic = mkInput();
        etLic.setHint("输入注册码 XXXX-XXXX-XXXX-XXXX");
        ll.addView(etLic);

        final TextView tvMsg = new TextView(this);
        tvMsg.setTextSize(13);
        tvMsg.setTextColor(0xFFCC3333);
        tvMsg.setPadding(0, (int) (12 * d), 0, 0);
        ll.addView(tvMsg);

        Button bAct = mkBtn("激 活", true);
        bAct.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String cur = License.expires(MainActivity.this);
                String err = License.activate(MainActivity.this,
                        etLic.getText().toString());
                if (err == null) {
                    String now = License.expires(MainActivity.this);
                    String msg = "激活成功";
                    if (cur != null && now != null && !cur.equals(now)) {
                        msg += "\n到期时间已从 20" + curFmt(cur)
                                + " 更新为 20" + curFmt(now)
                                + " (覆盖旧码, 不累加)";
                    }
                    Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show();
                    recreate();   // 重建进入主界面
                } else {
                    tvMsg.setText("激活失败: " + err);
                    Toast.makeText(MainActivity.this, "激活失败: " + err,
                            Toast.LENGTH_LONG).show();
                }
            }
        });
        ll.addView(bAct);

        // 底部联系方式: 微信二维码
        TextView tvContact = new TextView(this);
        tvContact.setText("购买/续费请加微信 (扫码)");
        tvContact.setTextSize(13);
        tvContact.setTextColor(0xFF8A94A0);
        tvContact.setGravity(Gravity.CENTER);
        tvContact.setPadding(0, (int) (22 * d), 0, (int) (6 * d));
        ll.addView(tvContact);

        android.widget.ImageView ivQr = new android.widget.ImageView(this);
        ivQr.setImageResource(R.drawable.contact_qr);
        android.widget.LinearLayout.LayoutParams lpQr = new android.widget.LinearLayout.LayoutParams(
                (int) (200 * d), (int) (250 * d));
        lpQr.gravity = Gravity.CENTER;
        ivQr.setLayoutParams(lpQr);
        ivQr.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        ll.addView(ivQr);

        setContentView(root);
    }

    private void buildMainUi() {
        float d = den();
        int p = (int) (16 * d);

        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setPadding(p, (int) (18 * d), p, p);
        ll.setBackground(getDrawableRes(R.drawable.bg_root));

        // 标题 + 授权状态(公司名待个人信息拉取后动态替换)
        tvTitle = new TextView(this);
        tvTitle.setText("行藏有度");
        tvTitle.setTextSize(20);
        tvTitle.setTextColor(0xFF1A66C2);
        tvTitle.getPaint().setFakeBoldText(true);
        ll.addView(tvTitle);

        tvLicense = new TextView(this);
        tvLicense.setTextSize(13);
        tvLicense.setPadding(0, 0, 0, (int) (2 * d));
        ll.addView(tvLicense);
        updateLicenseText();

        // 授权行右侧"联系方式"链接: 点击弹二维码 + 设备码
        TextView tvContactLink = new TextView(this);
        tvContactLink.setText("联系方式");
        tvContactLink.setTextSize(13);
        tvContactLink.setTextColor(0xFF1A66C2);
        tvContactLink.getPaint().setUnderlineText(true);
        android.widget.LinearLayout.LayoutParams lpCl = new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        lpCl.gravity = Gravity.END | Gravity.TOP;
        lpCl.topMargin = (int) (-20 * d);
        tvContactLink.setLayoutParams(lpCl);
        tvContactLink.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showContactDialog(); }
        });
        ll.addView(tvContactLink);

        // 3. 答题方式选择
        sp = getSharedPreferences("cfg", MODE_PRIVATE);
        LinearLayout cardMode = mkCard(ll, "答题方式");
        final String[] modes = {"app", "http"};
        final String[] modeNames = {"方式一: 操作界面答题 (需无障碍+悬浮窗)",
                "方式二: 协议自动答题 (无需任何权限)"};
        android.widget.RadioGroup rgMode = new android.widget.RadioGroup(this);
        for (int i = 0; i < 2; i++) {
            android.widget.RadioButton rb = new android.widget.RadioButton(this);
            rb.setId(i + 1);
            rb.setText(modeNames[i]);
            rb.setTextSize(14);
            rb.setPadding((int) (6 * d), (int) (6 * d), 0, (int) (6 * d));
            rgMode.addView(rb);
        }
        rgMode.check("http".equals(sp.getString("answer_mode", "http")) ? 2 : 1);
        rgMode.setOnCheckedChangeListener(new android.widget.RadioGroup.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(android.widget.RadioGroup g, int id) {
                sp.edit().putString("answer_mode", modes[id - 1]).apply();
                rebuildForMode();
            }
        });
        cardMode.addView(rgMode);

        // 方式一专属区块(权限+答题设置)
        modeBlock = new LinearLayout(this);
        modeBlock.setOrientation(LinearLayout.VERTICAL);
        ll.addView(modeBlock);
        buildModeBlock(modeBlock, d);

        // 方式二专属区块(账号登录+题库更新+阅读/一键日常+模拟考试)
        httpBlock = new LinearLayout(this);
        httpBlock.setOrientation(LinearLayout.VERTICAL);
        ll.addView(httpBlock);
        buildHttpBlock(httpBlock, d);

        // 5. 实时进度(两方式共用)
        LinearLayout cardRun = mkCard(ll, "运行状态");
        tvProgress = new TextView(this);
        tvProgress.setTextSize(14);
        tvProgress.setTextColor(0xFF222222);
        cardRun.addView(tvProgress);

        // 6. 控制按钮
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        bPause = mkBtn("暂停");
        bPause.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                BankService.paused = !BankService.paused;
                BankService.status = BankService.paused ? "已暂停" : "继续答题";
                syncControlBtns();
            }
        });
        row.addView(bPause);

        bSubmit = mkBtn("立即交卷");
        bSubmit.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                BankService.forceSubmit = true;
            }
        });
        row.addView(bSubmit);

        bUpdateCheck = mkBtn("检查更新");
        bUpdateCheck.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { checkUpdate(); }
        });
        row.addView(bUpdateCheck);

        Button bReset = mkBtn("重置统计");
        bReset.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { BankService.resetStats(); }
        });
        row.addView(bReset);

        Button bUpd = mkBtn("更新题库");
        bUpd.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!ensureLoginUi()) return;
                triggerBankUpdate();
            }
        });
        row.addView(bUpd);
        cardRun.addView(row);

        // 7. 题库信息与说明
        tvInfo = new TextView(this);
        tvInfo.setTextSize(12);
        tvInfo.setTextColor(0xFF8A94A0);
        tvInfo.setLineSpacing(0, 1.3f);
        tvInfo.setPadding((int) (4 * d), (int) (10 * d), 0, 0);
        ll.addView(tvInfo);
        loadBankInfo();

        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(ll);
        setContentView(sv);
        h.post(uiLoop);
    }

    private final Runnable uiLoop = new Runnable() {
        int ptsTick = 0;
        @Override public void run() {
            if (uiUpdating) { h.postDelayed(this, 1000); return; }
            uiUpdating = true;
            try {
                updateLicenseText();
                // 方式2可见时每30秒刷新一次积分概况
                if (refreshPtsRef != null
                        && !"app".equals(sp.getString("answer_mode", "http"))
                        && ++ptsTick >= 30) {
                    ptsTick = 0;
                    refreshPtsRef.run();
                }
                boolean a11y = isA11yOn(MainActivity.this);
                boolean ov = Settings.canDrawOverlays(MainActivity.this);
                if (tvA11y != null) {
                    tvA11y.setText(a11y ? "无障碍服务: ✓ 已开启"
                            : "无障碍服务: ✗ 未开启 (必填)");
                    tvA11y.setTextColor(a11y ? 0xFF1B8A3A : 0xFFC62828);
                }
                if (tvOverlay != null) {
                    tvOverlay.setText(ov ? "悬浮窗权限: ✓ 已授权"
                            : "悬浮窗权限: ✗ 未授权 (答题页控制面板需要)");
                    tvOverlay.setTextColor(ov ? 0xFF1B8A3A : 0xFFE65100);
                }

                String st = BankService.statusText();
                tvProgress.setText("运行状态: " + (BankService.alive ? st : "服务未运行")
                        + "\n当前第 " + BankService.qNum + " 题 | 已答 "
                        + BankService.answered + " 题 | 题库命中 " + BankService.hits
                        + "\n题库更新: " + BankService.updateInfo);
                if (tvDaily != null) {
                    String dailyLine = "";
                    if (BankUpdater.dailyRunning) {
                        dailyLine = "日常任务: " + BankUpdater.dailyInfo;
                    } else if (!BankUpdater.dailyInfo.isEmpty()) {
                        dailyLine = BankUpdater.dailyInfo;
                    }
                    tvDaily.setText(dailyLine);
                }
                if (tvExam != null) {
                    if (BankUpdater.examRunning) {
                        String line = "答题状态: " + BankUpdater.examInfo;
                        if (!BankUpdater.examPaper.isEmpty()) {
                            line += "\n当前试卷: " + BankUpdater.examPaper
                                    + "    第 " + BankUpdater.examQNum + "/"
                                    + BankUpdater.examQTotal + " 题";
                            if (!BankUpdater.examAnswer.isEmpty()) {
                                line += "\n提取答案: " + BankUpdater.examAnswer;
                            }
                        }
                        tvExam.setText(line);
                    } else {
                        tvExam.setText(BankUpdater.examInfo.isEmpty()
                                ? "" : "答题状态: " + BankUpdater.examInfo);
                    }
                }
                // 自动答题(每天一次): 打开APP后触发
                if (sp.getBoolean("auto_exam", false) && !autoExamChecked) {
                    autoExamChecked = true;
                    String today = new java.text.SimpleDateFormat(
                            "yyyy-MM-dd", java.util.Locale.CHINA).format(new java.util.Date());
                    if (!sp.getString("last_exam_date", "").equals(today)
                            && !BankUpdater.examRunning) {
                        sp.edit().putString("last_exam_date", today).apply();
                        triggerExam(sp.getInt("exam_count", 1),
                                sp.getString("exam_paper_id", null));
                    }
                }
                syncControlBtns();
            } catch (Exception ignore) { }
            uiUpdating = false;
            h.postDelayed(this, 1000);
        }
    };

    private void syncControlBtns() {
        bPause.setText(BankService.paused ? "继续" : "暂停");
    }

    private TextView mkStatus(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(14);
        t.setPadding(0, (int) (8 * getResources().getDisplayMetrics().density), 0, (int) (6 * getResources().getDisplayMetrics().density));
        return t;
    }

    private Button mkBtn(String s) {
        return mkBtn(s, false);
    }

    /** 统一圆角按钮: primary=主色(蓝) */
    private Button mkBtn(String s, boolean primary) {
        Button b = new Button(this);
        b.setText(s);
        b.setTextSize(13);
        b.setAllCaps(false);
        b.setTextColor(primary ? 0xFFFFFFFF : 0xFF1A66C2);
        b.setBackground(getDrawableRes(primary
                        ? R.drawable.bg_btn_primary : R.drawable.bg_btn_normal));
        b.setPadding((int) (16 * den()), (int) (9 * den()),
                (int) (16 * den()), (int) (9 * den()));
        b.setStateListAnimator(null);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        int m = (int) (6 * den());
        lp.rightMargin = m;
        lp.topMargin = m;
        b.setLayoutParams(lp);
        return b;
    }

    /** 圆角卡片容器 */
    private LinearLayout mkCard(LinearLayout parent, String title) {
        float d = den();
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(getDrawableRes(R.drawable.bg_card));
        card.setPadding((int) (14 * d), (int) (12 * d), (int) (14 * d), (int) (12 * d));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) (10 * d);
        card.setLayoutParams(lp);
        if (title != null) {
            TextView t = new TextView(this);
            t.setText(title);
            t.setTextSize(14);
            t.setTextColor(0xFF1A66C2);
            t.getPaint().setFakeBoldText(true);
            t.setPadding(0, 0, 0, (int) (6 * d));
            card.addView(t);
        }
        parent.addView(card);
        return card;
    }

    /** 统一样式输入框 */
    private android.widget.EditText mkInput() {
        android.widget.EditText e = new android.widget.EditText(this);
        e.setTextSize(14);
        e.setSingleLine(true);
        e.setBackground(getDrawableRes(R.drawable.bg_input));
        e.setPadding((int) (10 * den()), (int) (9 * den()),
                (int) (10 * den()), (int) (9 * den()));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) (5 * den());
        e.setLayoutParams(lp);
        return e;
    }

    private float den() {
        return getResources().getDisplayMetrics().density;
    }

    /** API22+: 按资源id取 Drawable (替代 androidx ContextCompat) */
    private android.graphics.drawable.Drawable getDrawableRes(int id) {
        return getDrawable(id);
    }

    /** 立即从培训平台直连拉取题库(后台线程, 不依赖无障碍服务是否开启) */
    private void triggerBankUpdate() {
        if (BankUpdater.running) {
            Toast.makeText(this, "题库正在更新中", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, "开始更新题库...", Toast.LENGTH_SHORT).show();
        final android.content.Context app = getApplicationContext();
        new Thread(new Runnable() {
            @Override public void run() {
                final BankUpdater.Result r = BankUpdater.update(app);
                h.post(new Runnable() {
                    @Override public void run() {
                        Toast.makeText(MainActivity.this, r.message, Toast.LENGTH_LONG).show();
                        loadBankInfo();
                    }
                });
            }
        }).start();
    }

    /** 拉取所有类别的试卷清单, 弹窗让用户选一张(也可选"不指定"按张数跑) */
    private void pickPaper() {
        if (BankUpdater.examRunning) {
            Toast.makeText(this, "答题进行中, 稍后再选", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, "正在获取试卷列表...", Toast.LENGTH_SHORT).show();
        final android.content.Context app = getApplicationContext();
        new Thread(new Runnable() {
            @Override public void run() {
                final String[] list = BankUpdater.paperListForUi(app);
                h.post(new Runnable() {
                    @Override public void run() {
                        if (list == null || list.length == 0) {
                            Toast.makeText(MainActivity.this,
                                    "没有可考试的试卷", Toast.LENGTH_LONG).show();
                            return;
                        }
                        String[] items = new String[list.length + 1];
                        items[0] = "不指定(按答题张数自动)";
                        for (int i = 0; i < list.length; i++) {
                            items[i + 1] = list[i].substring(list[i].indexOf('|') + 1);
                        }
                        new android.app.AlertDialog.Builder(MainActivity.this)
                                .setTitle("选择试卷 (" + list.length + " 张)")
                                .setItems(items, (dlg, which) -> {
                                    if (which == 0) {
                                        sp.edit().remove("exam_paper_id")
                                                .remove("exam_paper_name").apply();
                                        Toast.makeText(MainActivity.this,
                                                "已清除指定试卷", Toast.LENGTH_SHORT).show();
                                    } else {
                                        String row = list[which - 1];
                                        sp.edit()
                                          .putString("exam_paper_id",
                                                  row.substring(0, row.indexOf('|')))
                                          .putString("exam_paper_name",
                                                  row.substring(row.indexOf('|') + 1))
                                          .apply();
                                        Toast.makeText(MainActivity.this,
                                                "已选择: " + items[which],
                                                Toast.LENGTH_SHORT).show();
                                    }
                                })
                                .setNegativeButton("取消", null)
                                .show();
                    }
                });
            }
        }).start();
    }

    /** 检查是否已在"账号登录"卡完成过登录(有 login_pid); 未登录弹提示 */
    private boolean ensureLoginUi() {
        if (sp.getString("login_pid", "").isEmpty()) {
            Toast.makeText(this, "请先在\"账号登录\"中点击登录",
                    Toast.LENGTH_LONG).show();
            return false;
        }
        return true;
    }

    /** 保存账号/密码/张数/延迟并启动自动答题(手动或自动共用) */
    private void startExam(android.widget.EditText etId, android.widget.EditText etPwd,
                           android.widget.EditText etCnt, boolean silent) {
        startExam(etId, etPwd, etCnt, null, silent);
    }

    private void startExam(android.widget.EditText etId, android.widget.EditText etPwd,
                           android.widget.EditText etCnt,
                           android.widget.EditText etDelay, boolean silent) {
        if (BankUpdater.examRunning) {
            if (!silent) Toast.makeText(this, "答题已在进行中", Toast.LENGTH_SHORT).show();
            return;
        }
        String id = etId.getText().toString().trim();
        String bound = sp.getString("bind_idcard", "");
        if (!id.isEmpty() && !bound.isEmpty() && !bound.equals(id)) {
            Toast.makeText(this, "该授权已绑定账号 " + maskId(bound)
                    + ", 不能重复绑定其他账号", Toast.LENGTH_LONG).show();
            return;
        }
        // 必须先登录, 且登录账号与开始答题的账号一致
        String loginId = sp.getString("login_idcard", "");
        if (!id.isEmpty() && !loginId.isEmpty() && !loginId.equals(id)) {
            Toast.makeText(this, "当前登录的是 " + maskId(loginId)
                    + ", 与输入账号不一致, 请先登录", Toast.LENGTH_LONG).show();
            return;
        }
        if (!id.isEmpty() && bound.isEmpty()) {
            sp.edit().putString("bind_idcard", id).apply();
        }
        sp.edit()
          .putString("idcard", id)
          .putString("password", etPwd.getText().toString().trim())
          .putInt("exam_count", parseNum(etCnt, 1))
          .putInt("answer_delay", etDelay != null
                  ? Math.max(0, parseNum(etDelay, 1))
                  : sp.getInt("answer_delay", 1)).apply();
        triggerExam(sp.getInt("exam_count", 1),
                sp.getString("exam_paper_id", null));
    }

    private static int parseNum(android.widget.EditText et, int def) {
        try {
            int v = Integer.parseInt(et.getText().toString().trim());
            if (v >= 0) return v;
        } catch (Exception ignore) { }
        return def;
    }

    /** 账号脱敏显示: 前4位 + **** + 后4位 */
    private static String maskId(String id) {
        if (id == null || id.length() <= 8) return id;
        return id.substring(0, 4) + "****" + id.substring(id.length() - 4);
    }

    /** 后台线程执行自动答题(先清未交卷, 再按指定试卷/张数), 不依赖无障碍服务 */
    private void triggerExam(int count, String paperId) {
        String desc = paperId != null
                ? "指定试卷: " + sp.getString("exam_paper_name", "")
                : (count == 0 ? "(全部)" : count + " 张");
        Toast.makeText(this, "开始自动答题 " + desc + "...",
                Toast.LENGTH_SHORT).show();
        final android.content.Context app = getApplicationContext();
        final String fp = paperId;
        new Thread(new Runnable() {
            @Override public void run() {
                final BankUpdater.Result r = BankUpdater.runExams(app, count, fp);
                h.post(new Runnable() {
                    @Override public void run() {
                        Toast.makeText(MainActivity.this, r.message, Toast.LENGTH_LONG).show();
                    }
                });
            }
        }).start();
    }

    /** 检查更新: 查询 GitHub Release, 有新版本时弹出下载对话框 */
    private void checkUpdate() {
        bUpdateCheck.setEnabled(false);
        bUpdateCheck.setText("检查中...");
        UpdateHelper.checkLatest(this, new UpdateHelper.Callback() {
            @Override public void onResult(UpdateHelper.UpdateInfo info) {
                bUpdateCheck.setEnabled(true);
                bUpdateCheck.setText("检查更新");
                if (info == null) {
                    Toast.makeText(MainActivity.this, "网络错误，无法获取更新信息",
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                if (!info.newer) {
                    Toast.makeText(MainActivity.this, "当前已是最新版本 (v"
                            + info.remoteVersion + ")", Toast.LENGTH_SHORT).show();
                    return;
                }
                showUpdateDialog(info);
            }
        });
    }

    /** 显示更新对话框: 版本名 + 更新日志 + 下载按钮 */
    private void showUpdateDialog(final UpdateHelper.UpdateInfo info) {
        float d = den();
        android.app.AlertDialog.Builder bd = new android.app.AlertDialog.Builder(this);
        bd.setTitle("发现新版本 v" + info.remoteVersion);

        // 内容区
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding((int) (20 * d), (int) (12 * d), (int) (20 * d), 0);

        if (info.apkSize > 0) {
            float mb = info.apkSize / (1024f * 1024f);
            TextView tvSize = new TextView(this);
            tvSize.setText(String.format("新版本大小: %.1f MB", mb));
            tvSize.setTextSize(13);
            tvSize.setTextColor(0xFF8A94A0);
            box.addView(tvSize);
        }

        if (info.changelog != null && !info.changelog.isEmpty()) {
            TextView tvLog = new TextView(this);
            tvLog.setText("更新说明:\n" + info.changelog);
            tvLog.setTextSize(13);
            tvLog.setTextColor(0xFF333333);
            tvLog.setLineSpacing(0, 1.4f);
            box.addView(tvLog);
        }

        // 下载进度条 (初始隐藏)
        LinearLayout dlRow = new LinearLayout(this);
        dlRow.setOrientation(LinearLayout.VERTICAL);
        pbDownload = new android.widget.ProgressBar(this, null,
                android.R.attr.progressBarStyleHorizontal);
        pbDownload.setMax(100);
        pbDownload.setVisibility(View.GONE);
        dlRow.addView(pbDownload);
        tvDownloadStatus = new TextView(this);
        tvDownloadStatus.setTextSize(13);
        tvDownloadStatus.setTextColor(0xFF666666);
        tvDownloadStatus.setVisibility(View.GONE);
        dlRow.addView(tvDownloadStatus);
        box.addView(dlRow);

        bd.setMessage("更新说明:\n" + (info.changelog != null ? info.changelog : "无更新说明"));
        bd.setView(box);

        bd.setPositiveButton("立即下载", new android.content.DialogInterface.OnClickListener() {
            @Override public void onClick(android.content.DialogInterface dlg, int which) {
                dlg.dismiss();
                startDownload(info.downloadUrl);
            }
        });
        bd.setNegativeButton("稍后", null);
        dlDialog = bd.show();
        // 去掉默认 setMessage 显示，我们自己控制
        ((android.widget.TextView) dlDialog.findViewById(android.R.id.message)).setVisibility(View.GONE);
    }

    /** 下载 APK 并安装 */
    private void startDownload(String url) {
        if (url.isEmpty()) {
            Toast.makeText(this, "下载地址无效", Toast.LENGTH_SHORT).show();
            return;
        }
        final android.content.Context app = getApplicationContext();
        UpdateHelper.Progress prog = new UpdateHelper.Progress() {
            @Override public void onProgress(int pct) {
                if (pbDownload != null) {
                    pbDownload.setProgress(pct);
                    pbDownload.setVisibility(View.VISIBLE);
                    tvDownloadStatus.setText("下载中 " + pct + "%");
                    tvDownloadStatus.setVisibility(View.VISIBLE);
                }
            }
            @Override public void onDone() {
                if (tvDownloadStatus != null) {
                    tvDownloadStatus.setText("下载完成，正在安装...");
                }
                try { dlDialog.dismiss(); } catch (Exception ignore) { }
                File apk = new File(app.getExternalFilesDir(null), "AutoAnswer_update.apk");
                UpdateHelper.installApk(app, apk);
            }
            @Override public void onFail(String msg) {
                if (tvDownloadStatus != null) {
                    tvDownloadStatus.setText("下载失败: " + msg);
                }
                Toast.makeText(MainActivity.this, "下载失败: " + msg,
                        Toast.LENGTH_LONG).show();
            }
        };
        Toast.makeText(this, "开始下载更新...", Toast.LENGTH_SHORT).show();
        new Thread(() -> UpdateHelper.downloadApk(app, url, prog)).start();
    }

    private void loadBankInfo() {
        StringBuilder sb = new StringBuilder();
        File f = new File(getFilesDir(), "bank.db");
        try {
            SQLiteDatabase db = SQLiteDatabase.openDatabase(f.getPath(), null, SQLiteDatabase.OPEN_READONLY);
            long n = db.compileStatement("SELECT COUNT(*) FROM bank").simpleQueryForLong();
            db.close();
            sb.append("题库: ").append(n).append(" 题\n");
        } catch (Exception e) {
            sb.append("题库: 未加载\n");
        }
        sb.append("\n使用步骤:\n")
          .append("1. 开启无障碍服务(点上方按钮, 找到「行藏有度」开启)\n")
          .append("2. 打开「彬煤安培365」APP, 手动登录\n")
          .append("3. 进入考试页后自动答题、翻页, 答完自动交卷\n")
          .append("4. 答题页底部有悬浮面板: 实时进度/暂停/交卷\n\n")
          .append("规则: 快速模式每题约1秒, 慢速约3~6秒; 未命中题目随机作答; 交卷后静默8秒等待成绩, 不会重复答题");
        tvInfo.setText(sb.toString());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        h.removeCallbacksAndMessages(null);
    }
}
