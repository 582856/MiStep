package cn.xylin.mistep.utils;

import android.app.Instrumentation;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import java.util.Calendar;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

public class StepInstrumentation extends Instrumentation {
    private Bundle arguments;

    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        this.arguments = arguments;
        start();
    }

    private void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private void completeResult(int code, Bundle result) {
        try {
            JSONObject json = new JSONObject();
            for (String key : result.keySet()) {
                json.put(key, result.get(key));
            }
            try (OutputStream stream = getTargetContext().openFileOutput("step-test-result.json", Context.MODE_PRIVATE)) {
                stream.write(json.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception error) {
            result.putString("result_file_error", error.toString());
        }
        finish(code, result);
    }

    @Override
    public void onStart() {
        Bundle result = new Bundle();
        Context context = getTargetContext();
        boolean root = "true".equals(arguments.getString("root", "false"));
        int baseline = -1;
        boolean changed = false;
        Uri running = null;
        Uri treadmill = null;
        try {
            if ("true".equals(arguments.getString("foreground", "false"))) {
                require(context.getSharedPreferences("userSetting", Context.MODE_PRIVATE)
                        .getInt("autoModifyMode", 0) == 0, "foreground testing requires automation off");
                Intent launch = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
                require(launch != null, "launcher intent");
                startActivitySync(launch);
            }
            long[] bounds = StepUtil.getDayBounds();
            Calendar start = Calendar.getInstance();
            start.setTimeInMillis(bounds[0]);
            require(start.get(Calendar.HOUR_OF_DAY) == 0 && start.get(Calendar.MINUTE) == 0
                    && start.get(Calendar.SECOND) == 0 && start.get(Calendar.MILLISECOND) == 0, "day boundary");
            require(bounds[0] <= System.currentTimeMillis() && bounds[1] > System.currentTimeMillis(), "today range");
            baseline = StepUtil.getTodaySteps(context, root);
            if ("true".equals(arguments.getString("denied", "false"))) {
                require(baseline == -1, "permission denial must not become zero");
                require(!StepUtil.modifySteps(context, true, false, 12345), "no write without a valid read");
                result.putString("RESULT", "permission_failure_safe");
                completeResult(0, result);
                return;
            }
            require(baseline >= 0, "read today");
            result.putInt("baseline", baseline);
            for (int index = 0; index < 3; index++) {
                require(StepUtil.modifySteps(context, true, root, baseline), "idempotent set");
                require(StepUtil.getTodaySteps(context, root) == baseline, "set must not accumulate");
                require(StepUtil.readTodayRows(context, root).stream().allMatch(row -> row[1] >= 0), "no negative records");
            }
            if ("true".equals(arguments.getString("repairOnly", "false"))) {
                result.putString("RESULT", "success");
                result.putInt("final", baseline);
                completeResult(0, result);
                return;
            }
            require(!StepUtil.modifySteps(context, true, root, -1), "negative target");
            changed = true;
            require(StepUtil.modifySteps(context, false, root, 10), "add 10");
            require(StepUtil.getTodaySteps(context, root) == baseline + 10, "added total");
            require(StepUtil.modifySteps(context, true, root, baseline), "restore via decreasing set");
            changed = false;
            require(StepUtil.getTodaySteps(context, root) == baseline, "restored total");
            require(StepUtil.readTodayRows(context, root).stream().allMatch(row -> row[1] >= 0), "decreasing set cannot insert negatives");
            changed = true;
            require(StepUtil.modifySteps(context, false, root, -1), "subtract one via add mode");
            require(StepUtil.getTodaySteps(context, root) == baseline - 1, "subtracted total");
            require(StepUtil.readTodayRows(context, root).stream().allMatch(row -> row[1] >= 0), "negative input cannot insert negative records");
            require(StepUtil.modifySteps(context, true, root, baseline), "restore after negative input");
            changed = false;
            if (!root) {
                // 创建本次测试专用记录，finally 仅删除这些记录，不触碰用户历史。
                ContentValues values = new ContentValues();
                values.put("_begin_time", bounds[0]);
                values.put("_end_time", bounds[0]);
                values.put("_mode", 3);
                values.put("_steps", 7);
                running = context.getContentResolver().insert(Uri.parse("content://com.miui.providers.steps/item"), values);
                require(running != null, "running record");
                values.put("_mode", 4);
                values.put("_steps", 9);
                treadmill = context.getContentResolver().insert(Uri.parse("content://com.miui.providers.steps/item"), values);
                require(treadmill != null, "treadmill record");
                require(StepUtil.getTodaySteps(context, false) == baseline + 16, "include modes 3/4 and midnight");
            }
            result.putString("RESULT", "success");
        } catch (Throwable error) {
            result.putString("RESULT", "failure");
            result.putString("error", error.toString());
        } finally {
            if (running != null) {
                context.getContentResolver().delete(running, null, null);
            }
            if (treadmill != null) {
                context.getContentResolver().delete(treadmill, null, null);
            }
            if (changed && baseline >= 0) {
                result.putBoolean("restored", StepUtil.modifySteps(context, true, root, baseline));
            }
        }
        result.putInt("final", StepUtil.getTodaySteps(context, root));
        completeResult("success".equals(result.getString("RESULT")) ? 0 : 1, result);
    }
}
