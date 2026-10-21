// Modified by 枫彡: permissions, asynchronous step operations and automatic-run handling.
package cn.xylin.mistep.activitys;

import android.Manifest;
import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.os.Build;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.RadioGroup;
import android.widget.TimePicker;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textview.MaterialTextView;
import java.util.Calendar;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import cn.xylin.mistep.R;
import cn.xylin.mistep.StepApplication;
import cn.xylin.mistep.utils.Shared;
import cn.xylin.mistep.utils.StepUtil;
import cn.xylin.mistep.utils.Util;
import cn.xylin.mistep.works.AutoModifySteps;

/**
 * @author XyLin
 * @date 2021/1/25 23:06:22
 * Main.java
 **/
public class Main extends BaseActivity implements View.OnClickListener, CompoundButton.OnCheckedChangeListener, View.OnLongClickListener {
    public static final String USER_SETTING = "userSetting";
    public static final String ROOT_MODE = "isRootMode";
    public static final String MODIFY_MODE_ADD = "modifyModeAdd";
    public static final String AUTO_MODIFY_MODE = "autoModifyMode";
    public static final String AUTO_ADD_STEPS = "autoAddSteps";
    public static final String TIMING_NOTIFICATION = "isNotificationTimingModifyResult";
    public static final String TIMING_HOUR = "timingHour";
    public static final String TIMING_MINUTE = "timingMinute";
    private static final String DAY_INT = "dayInt";
    private static final String CHECK_RECORD_APP = "checkRecordApp";
    private MaterialTextView tvTodaySteps;
    private TextInputEditText edtAddSteps;
    private RadioGroup rdgModifyMode;
    private SwitchMaterial shFirstOpenAutoAdd, shTimingModify, shTimingNotification;
    private Shared shared;
    private boolean isRootMode;
    private TimePickerDialog timeDialog;
    private final ExecutorService stepExecutor = Executors.newSingleThreadExecutor();
    private boolean modifying;
    private String autoDay;

    @Override
    void initActivityControl() {
        isCanExitActivity = true;
        setContentView(R.layout.activity_main);
        tvTodaySteps = findViewById(R.id.tvTodaySteps);
        edtAddSteps = findViewById(R.id.edtAddSteps);
        rdgModifyMode = findViewById(R.id.rdgModifyMode);
        shFirstOpenAutoAdd = findViewById(R.id.shFirstOpenAutoAdd);
        shTimingModify = findViewById(R.id.shTimingModify);
        shTimingNotification = findViewById(R.id.shTimingNotification);
        shared = Shared.getShared();
    }

    @Override
    void initControlAttribute() {
        isRootMode = shared.getValue(USER_SETTING, ROOT_MODE, true);
        if (Build.VERSION.SDK_INT >= 29 && checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACTIVITY_RECOGNITION}, 1);
        }
        updateTodaySteps();
        edtAddSteps.setText(shared.getValue(USER_SETTING, AUTO_ADD_STEPS, 0).toString());
        rdgModifyMode.check(shared.getValue(USER_SETTING, MODIFY_MODE_ADD, true) ? R.id.rdbAddSteps : R.id.rdbSetSteps);
        int autoModifyMode = shared.getValue(USER_SETTING, AUTO_MODIFY_MODE, 0);
        shFirstOpenAutoAdd.setChecked(autoModifyMode == 1);
        shTimingModify.setChecked(autoModifyMode == 2);
        if (autoModifyMode == 2) {
            shTimingNotification.setVisibility(View.VISIBLE);
            shTimingNotification.setChecked(shared.getValue(USER_SETTING, TIMING_NOTIFICATION, false));
        }
        rdgModifyMode.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup group, int checkedId) {
                shared.putValue(USER_SETTING, MODIFY_MODE_ADD, checkedId == R.id.rdbAddSteps).commitShared(USER_SETTING);
            }
        });
        shFirstOpenAutoAdd.setOnCheckedChangeListener(this);
        shTimingModify.setOnCheckedChangeListener(this);
        shTimingNotification.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                shared.putValue(USER_SETTING, TIMING_NOTIFICATION, isChecked).commitShared(USER_SETTING);
                if (isChecked && Build.VERSION.SDK_INT >= 33
                        && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2);
                }
            }
        });
        MaterialButton button = findViewById(R.id.btnModifySteps);
        button.setOnClickListener(this);
        button.setOnLongClickListener(this);
        ((MaterialTextView) findViewById(R.id.tvUseTip)).setMovementMethod(ScrollingMovementMethod.getInstance());
        if (autoModifyMode == 1) {
            checkNewDay();
            return;
        }
        if (!shared.getValue(USER_SETTING, CHECK_RECORD_APP, false)) {
            checkRecordAppState();
        }
    }

    private void checkNewDay() {
        String day = todayKey();
        if (!day.equals(shared.getValue(USER_SETTING, "dayDate", ""))) {
            autoDay = day;
            findViewById(R.id.btnModifySteps).callOnClick();
        }
    }

    private String todayKey() {
        Calendar date = Calendar.getInstance(TimeZone.getDefault());
        return date.get(Calendar.YEAR) + "-" + date.get(Calendar.MONTH) + "-" + date.get(Calendar.DAY_OF_MONTH);
    }

    @Override
    public void onClick(View v) {
        if (v.getId() == R.id.btnModifySteps && !modifying) {
            String inputStr = Util.getControlText(edtAddSteps);
            if (Util.isStrEmpty(inputStr)) {
                Util.toast(appActivity, R.string.toast_empty_steps);
            } else {
                try {
                    int steps = Integer.parseInt(inputStr);
                    shared.putValue(USER_SETTING, AUTO_ADD_STEPS, steps).commitShared(USER_SETTING);
                    boolean setMode = rdgModifyMode.getCheckedRadioButtonId() == R.id.rdbSetSteps;
                    boolean rootMode = isRootMode;
                    modifying = true;
                    v.setEnabled(false);
                    stepExecutor.execute(() -> {
                        boolean success = StepUtil.modifySteps(getApplicationContext(), setMode, rootMode, steps);
                        int total = StepUtil.getTodaySteps(getApplicationContext(), rootMode);
                        runOnUiThread(() -> {
                            modifying = false;
                            if (isDestroyed()) {
                                return;
                            }
                            v.setEnabled(true);
                            Util.toast(appActivity, success ? "修改步数成功。"
                                    : "修改或验证失败，请检查运动权限、ROOT授权和运行模式。不要连续重复增加。");
                            showTodaySteps(total);
                            if (autoDay != null) {
                                if (success) {
                                    shared.putValue(USER_SETTING, "dayDate", autoDay).commitShared(USER_SETTING);
                                }
                                autoDay = null;
                                if (success) {
                                    StepApplication.finishAll();
                                }
                            }
                        });
                    });
                } catch (NumberFormatException ignore) {
                    Util.toast(appActivity, R.string.toast_input_steps_error);
                }
            }
        }
    }

    @Override
    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
        int switchId = buttonView.getId();
        if (buttonView.isPressed()) {
            if (switchId == R.id.shFirstOpenAutoAdd) {
                    if (isChecked) {
                        shTimingModify.setChecked(false);
                        shared.putValue(USER_SETTING, DAY_INT, Calendar.getInstance(TimeZone.getDefault()).get(Calendar.DAY_OF_MONTH));
                        shared.putValue(USER_SETTING, "dayDate", todayKey());
                    } else {
                        shared.removeValue(USER_SETTING, DAY_INT).removeValue(USER_SETTING, "dayDate");
                    }
            } else if (switchId == R.id.shTimingModify) {
                    if (isChecked) {
                        if (timeDialog == null) {
                            timeDialog = new TimePickerDialog(
                                    appActivity,
                                    new TimePickerDialog.OnTimeSetListener() {
                                        @Override
                                        public void onTimeSet(TimePicker view, int hourOfDay, int minute) {
                                            shFirstOpenAutoAdd.setChecked(false);
                                            shTimingNotification.setVisibility(View.VISIBLE);
                                            shared.putValue(USER_SETTING, TIMING_HOUR, hourOfDay)
                                                    .putValue(USER_SETTING, TIMING_MINUTE, minute)
                                                    .commitShared(USER_SETTING);
                                            AutoModifySteps.setNextModifySteps(getApplicationContext(), false);
                                            Util.toast(appActivity, "将于每天的" + hourOfDay + "点" + (minute > 0 && minute < 10 ? "0" + minute : minute) + "分自动修改");
                                        }
                                    },
                                    shared.getValue(USER_SETTING, TIMING_HOUR, 0),
                                    shared.getValue(USER_SETTING, TIMING_MINUTE, 1),
                                    true
                            );
                            timeDialog.setOnCancelListener(new DialogInterface.OnCancelListener() {
                                @Override
                                public void onCancel(DialogInterface dialog) {
                                    shTimingModify.setChecked(false);
                                    AutoModifySteps.setNextModifySteps(getApplicationContext(), true);
                                }
                            });
                        }
                        timeDialog.show();
                        Util.toast(appActivity, R.string.toast_please_appoint_time);
                    } else {
                        shTimingNotification.setVisibility(View.GONE);
                        AutoModifySteps.setNextModifySteps(getApplicationContext(), true);
                    }
            } else {
                    return;
            }
            shared.putValue(USER_SETTING, AUTO_MODIFY_MODE, !shFirstOpenAutoAdd.isChecked() && !shTimingModify.isChecked() ? 0 : shFirstOpenAutoAdd.isChecked() ? 1 : 2)
                    .commitShared(USER_SETTING);
        } else if (!isChecked) {
            if (switchId == R.id.shFirstOpenAutoAdd) {
                shared.removeValue(USER_SETTING, DAY_INT).removeValue(USER_SETTING, "dayDate").commitShared(USER_SETTING);
            } else if (switchId == R.id.shTimingModify) {
                shTimingNotification.setVisibility(View.GONE);
                AutoModifySteps.setNextModifySteps(getApplicationContext(), true);
            }
        }
    }

    private void updateTodaySteps() {
        boolean rootMode = isRootMode;
        stepExecutor.execute(() -> {
            int steps = StepUtil.getTodaySteps(getApplicationContext(), rootMode);
            runOnUiThread(() -> {
                if (!isDestroyed()) {
                    showTodaySteps(steps);
                }
            });
        });
    }

    private void showTodaySteps(int steps) {
        tvTodaySteps.setText(steps < 0 ? "今日步数：读取失败，请授权运动/ROOT权限"
                : "今日步数：" + steps + (isRootMode ? "（ROOT）" : "（核心破解）"));
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 1) {
            updateTodaySteps();
        }
    }

    @Override
    protected void onDestroy() {
        stepExecutor.shutdown();
        super.onDestroy();
    }

    @Override
    public boolean onLongClick(View v) {
        if (modifying) {
            return true;
        }
        isRootMode = !isRootMode;
        Util.toast(appActivity, "已切换为" + (isRootMode ? "ROOT" : "核心破解") + "模式。");
        shared.putValue(USER_SETTING, ROOT_MODE, isRootMode).commitShared(USER_SETTING);
        updateTodaySteps();
        return true;
    }

    private void checkRecordAppState() {
        if (!StepUtil.checkRecordDisable(getApplicationContext())) {
            new AlertDialog.Builder(appActivity)
                    .setTitle(R.string.dialog_record_title)
                    .setMessage(R.string.dialog_record_message)
                    .setNeutralButton(R.string.dialog_record_btn_enable, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            stepExecutor.execute(() -> {
                                boolean enabled = StepUtil.rootEnableRecordApp();
                                runOnUiThread(() -> {
                                    if (!isDestroyed()) {
                                        Util.toast(appActivity, enabled ? R.string.toast_record_enable_success : R.string.toast_record_enable_fail);
                                        updateTodaySteps();
                                    }
                                });
                            });
                        }
                    })
                    .setNegativeButton(R.string.dialog_record_btn_cancel, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            shared.putValue(USER_SETTING, CHECK_RECORD_APP, true).applyShared(USER_SETTING);
                        }
                    })
                    .setPositiveButton(R.string.dialog_record_btn_ok, null)
                    .show();
        }
    }
}
