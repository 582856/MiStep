package cn.xylin.mistep.utils;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Process;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** ROOT 子进程入口，直接使用系统 SQLite，避免依赖手机上额外安装 sqlite3。 */
public final class OnePlusSteps {
    private static final String DATABASE = "/data/user/0/com.oplus.healthservice/databases/health_step.db";

    public static void main(String[] args) {
        try {
            if (android.os.Build.VERSION.SDK_INT < 28) throw new IllegalStateException("此一加适配需要 Android 9 或以上");
            if (Process.myUid() != 0) throw new IllegalStateException("需要 ROOT 授权");
            boolean write = args.length == 3 && ("set".equals(args[0]) || "add".equals(args[0]));
            if (!write && !(args.length == 1 && "read".equals(args[0]))) {
                throw new IllegalArgumentException("无效操作");
            }
            String day = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
            // ColorOS 的默认同步配置依赖应用进程初始化，app_process 中显式指定 FULL。
            SQLiteDatabase.OpenParams params = new SQLiteDatabase.OpenParams.Builder()
                    .setOpenFlags(write ? SQLiteDatabase.OPEN_READWRITE : SQLiteDatabase.OPEN_READONLY)
                    .setSynchronousMode(SQLiteDatabase.SYNC_MODE_FULL).build();
            try (SQLiteDatabase db = SQLiteDatabase.openDatabase(new File(DATABASE), params)) {
                if (!write) {
                    System.out.println("STEPS=" + total(db, day));
                    return;
                }
                db.beginTransaction();
                try {
                    long before = total(db, day);
                    long input = Integer.parseInt(args[1]);
                    long target = "set".equals(args[0]) ? input : before + input;
                    check(target);
                    long minuteBefore = minuteTotal(db, day);
                    if (target != before || target != minuteBefore) {
                        backup(db, day, new File(args[2], "oneplus-steps-" + System.currentTimeMillis() + ".json"));
                        adjust(db, "day_statistic", "day_date=?", new String[]{day}, "day_offset", target);
                        reconcileMinutes(db, day, target);
                    }
                    if (total(db, day) != target || minuteTotal(db, day) != target) {
                        throw new IllegalStateException("写入验证失败");
                    }
                    db.setTransactionSuccessful();
                    System.out.println("STEPS=" + target);
                } finally {
                    db.endTransaction();
                }
            }
        } catch (Throwable error) {
            System.err.println("ERROR=" + error);
            error.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static long total(SQLiteDatabase db, String day) {
        try (Cursor cursor = db.rawQuery("SELECT COALESCE(SUM(day_offset),0) FROM day_statistic WHERE day_date=?", new String[]{day})) {
            if (!cursor.moveToFirst()) throw new IllegalStateException("读取失败");
            long result = cursor.getLong(0);
            check(result);
            return result;
        }
    }

    private static long minuteTotal(SQLiteDatabase db, String day) {
        try (Cursor cursor = db.rawQuery("SELECT COALESCE(SUM(minute_offset),0) FROM step_data WHERE substr(minute_time,1,10)=?", new String[]{day})) {
            if (!cursor.moveToFirst()) throw new IllegalStateException("读取分钟明细失败");
            long result = cursor.getLong(0);
            check(result);
            return result;
        }
    }

    private static void check(long count) {
        if (count < 0 || count > Integer.MAX_VALUE) throw new IllegalArgumentException("步数超出范围");
    }

    private static void reconcileMinutes(SQLiteDatabase db, String day, long target) {
        int count;
        try (Cursor cursor = db.rawQuery("SELECT COUNT(*) FROM step_data WHERE substr(minute_time,1,10)=?", new String[]{day})) {
            if (!cursor.moveToFirst()) throw new IllegalStateException("读取分钟明细失败");
            count = cursor.getInt(0);
        }
        if (count == 0) throw new IllegalStateException("今日尚无计步记录，请先走几步后重试");
        long quotient = target / count;
        long remainder = target % count;
        try (Cursor rows = db.query("step_data", new String[]{"_id"}, "substr(minute_time,1,10)=?", new String[]{day}, null, null, "minute_time ASC")) {
            int index = 0;
            while (rows.moveToNext()) {
                long offset = quotient + (index++ < remainder ? 1 : 0);
                ContentValues values = new ContentValues();
                values.put("minute_offset", offset);
                values.put("minute_offset_run", 0);
                values.put("minute_offset_walk", offset);
                if (db.update("step_data", values, "_id=?", new String[]{rows.getString(0)}) != 1) {
                    throw new IllegalStateException("分钟记录已改变");
                }
            }
        }
    }

    private static void adjust(SQLiteDatabase db, String table, String where, String[] args, String column, long target) {
        try (Cursor rows = db.query(table, new String[]{"_id", column, column + "_run", column + "_walk"},
                where, args, null, null, column + " DESC")) {
            long sum = 0;
            while (rows.moveToNext()) {
                long value = rows.getLong(1);
                check(value);
                sum += value;
            }
            long difference = target - sum;
            rows.moveToPosition(-1);
            while (difference != 0 && rows.moveToNext()) {
                long old = rows.getLong(1);
                long change = difference > 0 ? difference : -Math.min(old, -difference);
                long value = old + change;
                check(value);
                long run = Math.min(value, Math.max(0, rows.getLong(2)));
                ContentValues values = new ContentValues();
                values.put(column, value);
                values.put(column + "_run", run);
                values.put(column + "_walk", value - run);
                if (db.update(table, values, "_id=?", new String[]{rows.getString(0)}) != 1) {
                    throw new IllegalStateException("记录已改变");
                }
                difference -= change;
            }
            // 无今日记录时不猜测硬件基准；等待系统产生首条记录再修改。
            if (difference != 0) throw new IllegalStateException("今日尚无计步记录，请先走几步后重试");
        }
    }

    private static void backup(SQLiteDatabase db, String day, File file) throws Exception {
        JSONObject result = new JSONObject();
        String[] tables = {"day_statistic", "step_data"};
        String[] filters = {"day_date=?", "substr(minute_time,1,10)=?"};
        for (int i = 0; i < tables.length; i++) {
            JSONArray records = new JSONArray();
            try (Cursor cursor = db.query(tables[i], null, filters[i], new String[]{day}, null, null, null)) {
                while (cursor.moveToNext()) {
                    JSONObject record = new JSONObject();
                    for (int c = 0; c < cursor.getColumnCount(); c++) {
                        record.put(cursor.getColumnName(c), cursor.isNull(c) ? JSONObject.NULL : cursor.getString(c));
                    }
                    records.put(record);
                }
            }
            result.put(tables[i], records);
        }
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(result.toString().getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        }
    }
}
