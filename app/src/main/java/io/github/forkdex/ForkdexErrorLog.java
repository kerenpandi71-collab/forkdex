package io.github.forkdex;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Small on-device diagnostic log for Shizuku and native window service events. */
final class ForkdexErrorLog {
    private static final String PREFS = "forkdex_error_log";
    private static final String KEY = "entries";
    private static final int MAX_CHARS = 48000;
    private ForkdexErrorLog() {}

    static synchronized void add(Context context, String stage, Throwable error) {
        StringBuilder detail = new StringBuilder();
        if (error != null) {
            detail.append(error.getClass().getName()).append(": ")
                    .append(String.valueOf(error.getMessage()));
            for (StackTraceElement frame : error.getStackTrace()) {
                detail.append("\n  at ").append(frame);
                if (detail.length() > 5000) break;
            }
            Throwable cause = error.getCause();
            if (cause != null) detail.append("\nCaused by: ")
                    .append(cause.getClass().getName()).append(": ")
                    .append(String.valueOf(cause.getMessage()));
        }
        add(context, stage, detail.toString());
    }

    static synchronized void add(Context context, String stage, String detail) {
        String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
                .format(new Date());
        String entry = "[" + time + "] " + stage
                + (detail == null || detail.isEmpty() ? "" : "\n" + detail) + "\n\n";
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String old = prefs.getString(KEY, "");
        String combined = old + entry;
        if (combined.length() > MAX_CHARS) combined = combined.substring(combined.length() - MAX_CHARS);
        prefs.edit().putString(KEY, combined).apply();
        Log.e("ForkdexDiagnostics", stage + (detail == null ? "" : ": " + detail));
    }

    static String read(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY, "");
    }

    static void clear(Context context) {
        context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().remove(KEY).apply();
    }
}
