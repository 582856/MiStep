// Modified by 枫彡: HyperOS 4 reads, nonnegative updates, verification and shared compatibility.
package cn.xylin.mistep.utils;

import android.content.ContentValues;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Calendar;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author XyLin
 * @date 2021/1/26 17:28:20
 * StepUtil.java
 **/
public class StepUtil {
    private static final String[] MAY_BE_RECORD_APP = {"com.miui.powerkeeper", "com.xiaomi.joyose"};
    private static final Uri STEP_URI = Uri.parse("content://com.miui.providers.steps/item");
    private static final Uri LATEST_URI = Uri.parse("content://com.miui.providers.steps/item_latest");
    private static final String ROOT_ADD_STEPS = "content insert --user 0 --uri content://com.miui.providers.steps/item --bind _begin_time:l:%d --bind _end_time:l:%d --bind _mode:i:2 --bind _steps:i:%d";
    private static final String BEGIN_TIME = "_begin_time";
    private static final String END_TIME = "_end_time";
    private static final String MODE = "_mode";
    private static final String STEPS = "_steps";
    private static final String[] QUERY_FILED = {"_id", BEGIN_TIME, END_TIME, MODE, STEPS};
    private static final Pattern ROOT_STEPS = Pattern.compile("_id=(\\d+),\\s*_steps=(-?\\d+)");

    public static int getTodaySteps(Context context) {
        return getTodaySteps(context, false);
    }

    public static int getTodaySteps(Context context, boolean isRootMode) {
        try {
            if (isOnePlus()) return onePlusCommand(context, isRootMode, "read");
            long total = 0;
            for (long[] row : readTodayRows(context, isRootMode)) {
                total += row[1];
            }
            return checkedTotal(total);
        } catch (IOException | RuntimeException error) {
            WLOG.printLogs("读取步数", error.toString());
            return -1;
        }
    }

    static List<long[]> readTodayRows(Context context, boolean isRootMode) throws IOException {
        long[] bounds = getDayBounds();
        String selection = BEGIN_TIME + " >= " + bounds[0] + " AND " + END_TIME + " < " + bounds[1]
                + " AND " + MODE + " IN (2,3,4)";
        try {
            Cursor result;
            try {
                result = context.getContentResolver().query(LATEST_URI, QUERY_FILED, selection, null, null);
            } catch (IllegalArgumentException unsupported) {
                result = context.getContentResolver().query(STEP_URI, QUERY_FILED, selection, null, null);
            }
            try (Cursor cursor = result) {
                if (cursor == null) {
                    throw new IllegalStateException("系统计步服务未返回数据");
                }
                List<long[]> rows = new ArrayList<>();
                while (cursor.moveToNext()) {
                    rows.add(new long[]{cursor.getLong(cursor.getColumnIndexOrThrow("_id")),
                            cursor.getLong(cursor.getColumnIndexOrThrow(STEPS))});
                }
                return rows;
            }
        } catch (RuntimeException error) {
            WLOG.printLogs("读取步数", error.toString());
        }
        if (isRootMode) {
            try {
                // OS4 的 root shell 不持有运动权限，只在明确选择 ROOT 模式时使用系统的跨用户查询入口。
                return readRootRows(selection);
            } catch (IOException | RuntimeException error) {
                WLOG.printLogs("ROOT读取步数", error.toString());
            }
        }
        throw new IOException("无法读取今日计步记录");
    }

    private static List<long[]> readRootRows(String selection) throws IOException {
        String output = executeRoot("content query --user 0 --uri content://com.miui.providers.steps/has_permission"
                + " --projection _id:_steps --where \"" + selection + "\"");
        Matcher matcher = ROOT_STEPS.matcher(output);
        List<long[]> rows = new ArrayList<>();
        while (matcher.find()) {
            rows.add(new long[]{Long.parseLong(matcher.group(1)), Long.parseLong(matcher.group(2))});
        }
        if (rows.isEmpty() && !output.trim().equals("No result found.")) {
            throw new IOException("系统计步查询返回无效结果");
        }
        return rows;
    }

    static long[] getDayBounds() {
        Calendar day = Calendar.getInstance();
        day.set(Calendar.HOUR_OF_DAY, 0);
        day.set(Calendar.MINUTE, 0);
        day.set(Calendar.SECOND, 0);
        day.set(Calendar.MILLISECOND, 0);
        long begin = day.getTimeInMillis();
        day.add(Calendar.DAY_OF_MONTH, 1);
        return new long[]{begin, day.getTimeInMillis()};
    }

    private static int checkedTotal(long steps) {
        if (steps < 0 || steps > Integer.MAX_VALUE) {
            throw new IllegalStateException("系统步数超出范围");
        }
        return (int) steps;
    }

    public static synchronized boolean modifySteps(Context context, boolean isSetMode, boolean isRootMode, int steps) {
        if (isOnePlus()) {
            try {
                onePlusCommand(context, isRootMode, (isSetMode ? "set " : "add ") + steps);
                return true;
            } catch (IOException | RuntimeException error) {
                WLOG.printLogs("一加步数", error.toString());
                return false;
            }
        }
        long expected;
        try {
            List<long[]> rows = readTodayRows(context, isRootMode);
            long[] counts = new long[rows.size()];
            long total = 0;
            boolean hasNegative = false;
            for (int index = 0; index < rows.size(); index++) {
                counts[index] = rows.get(index)[1];
                total += counts[index];
                hasNegative |= counts[index] < 0;
            }
            int todaySteps = checkedTotal(total);
            expected = isSetMode ? steps : (long) todaySteps + steps;
            checkedTotal(expected);
            int delta = (int) (expected - todaySteps);
            if (delta < 0 || hasNegative) {
                long[] planned = planNonNegativeSteps(counts, Math.min(expected, todaySteps));
                saveStepBackup(context, rows);
                StringBuilder changed = new StringBuilder();
                int changedCount = 0;
                for (int index = 0; index < counts.length; index++) {
                    if (counts[index] != planned[index]) {
                        if (changedCount++ > 0) {
                            changed.append(" OR ");
                        }
                        changed.append("(_id=").append(rows.get(index)[0])
                                .append(" AND _steps=").append(counts[index]).append(')');
                    }
                }
                // 先用一次集合更新清零受影响记录，避免旧负记录和巨大正记录在修复中产生负总数或瞬时巨额总数。
                if (changedCount > 0 && updateStepRows(context, isRootMode, 0, changed.toString()) != changedCount) {
                    return false;
                }
                for (int index = 0; index < counts.length; index++) {
                    if (counts[index] != planned[index] && planned[index] > 0
                            && updateStepRows(context, isRootMode, (int) planned[index],
                            "_id=" + rows.get(index)[0] + " AND _steps=0") != 1) {
                        return false;
                    }
                }
            }
            if (delta > 0) {
                boolean inserted = isRootMode ? rootModeAddSteps(delta) : coreModeAddSteps(context, delta);
                if (!inserted && getTodaySteps(context, isRootMode) < expected) {
                    return false;
                }
            }
        } catch (IOException | RuntimeException error) {
            WLOG.printLogs("调整步数", error.toString());
            return false;
        }
        // content 工具在服务异常时也可能返回 0，必须重新查询实际步数；走路期间允许自然增长。
        return getTodaySteps(context, isRootMode) >= expected;
    }

    static long[] planNonNegativeSteps(long[] current, long target) {
        checkedTotal(target);
        long[] planned = current.clone();
        long positiveTotal = 0;
        Integer[] order = new Integer[planned.length];
        for (int index = 0; index < planned.length; index++) {
            if (planned[index] < Integer.MIN_VALUE || planned[index] > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("记录步数超出范围");
            }
            planned[index] = Math.max(0, planned[index]);
            positiveTotal += planned[index];
            order[index] = index;
        }
        if (target > positiveTotal) {
            throw new IllegalArgumentException("增加步数应插入正数记录");
        }
        // 优先减少最大记录，尽量保留正常行走的细分历史，并减少需要更新的行数。
        Arrays.sort(order, (left, right) -> Long.compare(current[right], current[left]));
        long remaining = positiveTotal - target;
        for (int index : order) {
            long reduction = Math.min(planned[index], remaining);
            planned[index] -= reduction;
            remaining -= reduction;
            if (remaining == 0) {
                break;
            }
        }
        return planned;
    }

    private static void saveStepBackup(Context context, List<long[]> rows) throws IOException {
        StringBuilder json = new StringBuilder("{\"time\":").append(System.currentTimeMillis()).append(",\"rows\":[");
        for (int index = 0; index < rows.size(); index++) {
            if (index > 0) {
                json.append(',');
            }
            json.append("{\"_id\":").append(rows.get(index)[0])
                    .append(",\"_steps\":").append(rows.get(index)[1]).append('}');
        }
        json.append("]}");
        try (OutputStream stream = context.openFileOutput("steps-before-update-" + System.currentTimeMillis() + ".json", Context.MODE_PRIVATE)) {
            stream.write(json.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    static int updateStepRows(Context context, boolean root, int steps, String selection) throws IOException {
        if (steps < 0) {
            throw new IllegalArgumentException("禁止写入负步数");
        }
        if (root) {
            Matcher ids = Pattern.compile("_id=(\\d+)").matcher(selection);
            StringBuilder idList = new StringBuilder();
            while (ids.find()) {
                if (idList.length() > 0) {
                    idList.append(',');
                }
                idList.append(ids.group(1));
            }
            if (idList.length() == 0) {
                throw new IllegalArgumentException("更新必须限定具体记录");
            }
            try {
                executeRoot("content update --user 0 --uri " + STEP_URI
                        + " --bind _steps:i:" + steps + " --where \"" + selection + "\"");
            } catch (IOException error) {
                WLOG.printLogs("ROOT更新状态待核实", error.toString());
            }
            // OS4 的 content update 成功也无输出，因此读取实际行值验证，而非依赖工具的提示文本。
            try (Cursor cursor = context.getContentResolver().query(STEP_URI, new String[]{"_id", STEPS},
                    "_id IN (" + idList + ") AND _steps=" + steps, null, null)) {
                if (cursor != null) {
                    return cursor.getCount();
                }
            } catch (RuntimeException error) {
                WLOG.printLogs("更新结果读取", error.toString());
            }
            int count = 0;
            for (long[] row : readRootRows("_id IN (" + idList + ")")) {
                if (row[1] == steps) {
                    count++;
                }
            }
            return count;
        }
        ContentValues values = new ContentValues(1);
        values.put(STEPS, steps);
        return context.getContentResolver().update(STEP_URI, values, selection, null);
    }

    private static boolean coreModeAddSteps(Context context, int steps) {
        if (steps <= 0) {
            return false;
        }
        try {
            ContentValues values = new ContentValues(4);
            long end = System.currentTimeMillis();
            values.put(BEGIN_TIME, Math.max(getDayBounds()[0], end - 1000L));
            values.put(END_TIME, end);
            values.put(MODE, 2);
            values.put(STEPS, steps);
            return context.getContentResolver().insert(STEP_URI, values) != null;
        } catch (RuntimeException error) {
            WLOG.printLogs("写入步数", error.toString());
        }
        return false;
    }

    private static boolean runRootCommand(String command) {
        try {
            executeRoot(command);
            return true;
        } catch (IOException error) {
            WLOG.printLogs("ROOT命令", error.toString());
        }
        return false;
    }

    private static boolean rootModeAddSteps(int steps) {
        if (steps <= 0) {
            return false;
        }
        long end = System.currentTimeMillis();
        return runRootCommand(String.format(
                Locale.CHINA,
                ROOT_ADD_STEPS,
                Math.max(getDayBounds()[0], end - 1000L),
                end,
                steps
        ));
    }

    private static String executeRoot(String command) throws IOException {
        // 一加上的 KernelSU 保留应用挂载隔离时看不到健康服务数据，需使用全局挂载命名空间。
        ProcessBuilder builder = isOnePlus() ? new ProcessBuilder("su", "-M", "-c", command)
                : new ProcessBuilder("su", "-c", command);
        Process process = builder.redirectErrorStream(true).start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try (InputStream stream = process.getInputStream()) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = stream.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                }
            } catch (IOException ignored) {
            }
        }, "mistep-su-output");
        reader.setDaemon(true);
        reader.start();
        try {
            process.getOutputStream().close();
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                throw new IOException("ROOT授权或命令执行超时");
            }
            reader.join(2000);
            if (reader.isAlive()) {
                throw new IOException("ROOT输出读取超时");
            }
            String result = output.toString(StandardCharsets.UTF_8.name());
            if (process.exitValue() != 0 || result.contains("Error") || result.contains("Exception")
                    || result.contains("Permission Denial")) {
                throw new IOException("ROOT命令失败：" + result.trim());
            }
            return result;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("ROOT操作被中断", error);
        } finally {
            process.destroy();
        }
    }

    public static boolean rootEnableRecordApp() {
        if (isOnePlus()) return runRootCommand("pm enable com.oplus.healthservice");
        boolean enableSuccess = false;
        for (String pkg : MAY_BE_RECORD_APP) {
            boolean enableResult = runRootCommand("pm enable " + pkg);
            if (!enableSuccess && enableResult) {
                enableSuccess = true;
            }
        }
        return enableSuccess;
    }

    public static boolean checkRecordDisable(Context context) {
        PackageManager packageManager = context.getPackageManager();
        if (isOnePlus()) return packageManager.resolveContentProvider("com.oplus.healthservice.stepprovider", 0) != null;
        if (packageManager.resolveContentProvider(STEP_URI.getAuthority(), 0) != null) {
            return true;
        }
        try {
            for (String pkg : MAY_BE_RECORD_APP) {
                ApplicationInfo info = packageManager.getApplicationInfo(pkg, 0);
                if (!info.enabled) {
                    return false;
                }
            }
            return true;
        } catch (PackageManager.NameNotFoundException ignore) {
        }
        return false;
    }

    public static boolean isOnePlus() {
        return "OnePlus".equalsIgnoreCase(android.os.Build.MANUFACTURER);
    }

    private static int onePlusCommand(Context context, boolean root, String operation) throws IOException {
        if (!root) throw new IOException("一加系统写入需要 ROOT 模式");
        String command = "CLASSPATH=" + shellQuote(context.getApplicationInfo().sourceDir)
                + " app_process /system/bin cn.xylin.mistep.utils.OnePlusSteps " + operation;
        if (!"read".equals(operation)) command += " " + shellQuote(context.getFilesDir().getAbsolutePath());
        Matcher result = Pattern.compile("(?m)^STEPS=(\\d+)\\s*$").matcher(executeRoot(command));
        if (!result.find()) throw new IOException("一加计步服务未返回有效结果");
        return checkedTotal(Long.parseLong(result.group(1)));
    }

    private static String shellQuote(String text) {
        return "'" + text.replace("'", "'\\''") + "'";
    }
}
