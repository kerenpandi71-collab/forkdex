package io.github.forkdex;

import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Binder;
import android.os.IBinder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Shizuku UserService that controls real Android tasks.
 * Apps remain native Android activities; Forkdex does not draw fake app windows.
 */
public final class ForkdexWindowService extends IForkdexWindowService.Stub {
    private static final int WINDOWING_MODE_FULLSCREEN = 1;
    private static final int WINDOWING_MODE_FREEFORM = 5;
    private static final int ACTIVITY_TYPE_STANDARD = 1;
    private static volatile boolean freeformSettingsEnabled;
    private static Object sharedWindowOrganizer;

    public ForkdexWindowService() { super(); }

    @Override
    public String launchFreeform(String packageName, String className,
            int displayId, Rect bounds) {
        if (packageName == null || packageName.isEmpty()
                || className == null || className.isEmpty()
                || displayId < 0 || bounds == null || bounds.isEmpty()) {
            return "Invalid launch target or window bounds";
        }
        try {
            enableFreeformSettings();
            Object atm = getActivityTaskManager();
            Intent intent = new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setComponent(new ComponentName(packageName, className))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
            ActivityOptions options = ActivityOptions.makeBasic();
            options.setLaunchDisplayId(displayId);
            options.setLaunchBounds(new Rect(bounds));
            ActivityOptions.class.getMethod("setLaunchWindowingMode", Integer.TYPE)
                    .invoke(options, Integer.valueOf(WINDOWING_MODE_FREEFORM));
            ActivityOptions.class.getMethod("setLaunchActivityType", Integer.TYPE)
                    .invoke(options, Integer.valueOf(ACTIVITY_TYPE_STANDARD));
            Method start = atm.getClass().getMethod("startActivity",
                    Class.forName("android.app.IApplicationThread"),
                    String.class, String.class, Intent.class, String.class,
                    IBinder.class, String.class, Integer.TYPE, Integer.TYPE,
                    Class.forName("android.app.ProfilerInfo"), android.os.Bundle.class);
            Object result = start.invoke(atm, null, "com.android.shell", null,
                    intent, null, null, null, -1, 0, null, options.toBundle());
            int code = ((Integer) result).intValue();
            return code < 0 ? "ActivityTaskManager rejected freeform launch: " + code
                    : "FREEFORM_LAUNCH_ACCEPTED:" + code;
        } catch (Throwable error) {
            return failure("Freeform launch", error);
        }
    }

    @Override
    public String enableDesktopMode() {
        try {
            runSettings("enable_freeform_support");
            runSettings("force_resizable_activities");
            runSettings("force_desktop_mode_on_external_displays");
            return "DESKTOP_MODE_SETTINGS_ENABLED";
        } catch (Throwable error) {
            return failure("Enable desktop mode settings", error);
        }
    }

    @Override
    public String getWindowingCapabilities() {
        try {
            Class.forName("android.window.WindowContainerToken");
            Class<?> wct = Class.forName("android.window.WindowContainerTransaction");
            wct.getMethod("setWindowingMode",
                    Class.forName("android.window.WindowContainerToken"), Integer.TYPE);
            Class.forName("android.window.WindowOrganizer").getMethod("applyTransaction", wct);
            getActivityTaskManager().getClass().getMethod("getTasks",
                    Integer.TYPE, Boolean.TYPE, Boolean.TYPE, Integer.TYPE);
            return "Native freeform launch, task inventory, resize and WindowContainerTransaction controls detected. Device ROM must expose freeform mode.";
        } catch (Throwable error) {
            return "Native window API unavailable: " + rootMessage(error);
        }
    }

    @Override
    public String listTasks(int displayId) {
        try {
            List<?> tasks = getTasks(getActivityTaskManager(), displayId);
            JSONArray result = new JSONArray();
            for (Object task : tasks) {
                String pkg = taskPackage(task);
                if (pkg == null || pkg.equals("io.github.forkdex")) continue;
                JSONObject item = new JSONObject();
                item.put("taskId", intField(task, "taskId"));
                item.put("displayId", optionalIntField(task, "displayId", displayId));
                item.put("packageName", pkg);
                item.put("windowingMode", taskWindowingMode(task));
                item.put("visible", optionalBooleanField(task, "isVisible", false));
                item.put("focused", optionalBooleanField(task, "isFocused", false));
                item.put("bounds", rectJson(taskBounds(task)));
                result.put(item);
            }
            return result.toString();
        } catch (Throwable error) {
            return errorJson(error);
        }
    }

    @Override
    public String resizeTask(int displayId, int taskId, Rect bounds) {
        if (bounds == null || bounds.isEmpty()) return "Invalid task bounds";
        try {
            Object atm = getActivityTaskManager();
            Object task = requireTask(atm, displayId, taskId);
            if (taskWindowingMode(task) != WINDOWING_MODE_FREEFORM)
                return "Task is not in freeform mode";
            atm.getClass().getMethod("resizeTask", Integer.TYPE, Rect.class, Integer.TYPE)
                    .invoke(atm, Integer.valueOf(taskId), new Rect(bounds), Integer.valueOf(0));
            return "TASK_RESIZED:" + taskId;
        } catch (Throwable error) { return failure("Resize task", error); }
    }

    @Override
    public String setTaskMode(int displayId, int taskId, int mode, Rect bounds) {
        if (mode != WINDOWING_MODE_FULLSCREEN && mode != WINDOWING_MODE_FREEFORM)
            return "Unsupported windowing mode";
        if (mode == WINDOWING_MODE_FREEFORM && (bounds == null || bounds.isEmpty()))
            return "Freeform bounds are required";
        try {
            Object atm = getActivityTaskManager();
            Object task = requireTask(atm, displayId, taskId);
            Object token = field(task, "token");
            Class<?> tokenClass = Class.forName("android.window.WindowContainerToken");
            Class<?> wctClass = Class.forName("android.window.WindowContainerTransaction");
            Object wct = wctClass.getConstructor().newInstance();
            wctClass.getMethod("setWindowingMode", tokenClass, Integer.TYPE)
                    .invoke(wct, token, Integer.valueOf(mode));
            wctClass.getMethod("setBounds", tokenClass, Rect.class)
                    .invoke(wct, token, mode == WINDOWING_MODE_FULLSCREEN
                            ? new Rect() : new Rect(bounds));
            applySystemTransition(mode == WINDOWING_MODE_FREEFORM ? 6 : 3, wctClass, wct);
            return mode == WINDOWING_MODE_FREEFORM
                    ? "TASK_FREEFORM:" + taskId : "TASK_FULLSCREEN:" + taskId;
        } catch (Throwable error) { return failure("Change task mode", error); }
    }

    @Override
    public String focusTask(int displayId, int taskId) {
        try {
            Object atm = getActivityTaskManager();
            Object task = requireTask(atm, displayId, taskId);
            Object token = field(task, "token");
            Class<?> tokenClass = Class.forName("android.window.WindowContainerToken");
            Class<?> wctClass = Class.forName("android.window.WindowContainerTransaction");
            Object wct = wctClass.getConstructor().newInstance();
            wctClass.getMethod("reorder", tokenClass, Boolean.TYPE)
                    .invoke(wct, token, Boolean.TRUE);
            Object organizer = newSharedWindowOrganizer();
            organizer.getClass().getMethod("applyTransaction", wctClass).invoke(organizer, wct);
            return "TASK_FOCUSED:" + taskId;
        } catch (Throwable error) { return failure("Focus task", error); }
    }

    @Override
    public String minimizeTask(int displayId, int taskId) {
        try {
            Object atm = getActivityTaskManager();
            Object task = requireTask(atm, displayId, taskId);
            Object token = field(task, "token");
            Class<?> tokenClass = Class.forName("android.window.WindowContainerToken");
            Class<?> wctClass = Class.forName("android.window.WindowContainerTransaction");
            Object wct = wctClass.getConstructor().newInstance();
            wctClass.getMethod("reorder", tokenClass, Boolean.TYPE)
                    .invoke(wct, token, Boolean.FALSE);
            Object organizer = newSharedWindowOrganizer();
            organizer.getClass().getMethod("applyTransaction", wctClass).invoke(organizer, wct);
            return "TASK_MINIMIZED:" + taskId;
        } catch (Throwable error) { return failure("Minimize task", error); }
    }

    @Override
    public String setPackageDensity(int displayId, String packageName, int densityDpi) {
        if (packageName == null || packageName.trim().isEmpty())
            return "Nama paket aplikasi tidak valid";
        if (densityDpi != 0 && (densityDpi < 72 || densityDpi > 1000))
            return "Density DPI di luar batas aman (72-1000), atau 0 untuk mengikuti sistem";
        try {
            Object atm = getActivityTaskManager();
            List<?> tasks = getTasks(atm, displayId);
            int applied = 0;
            Class<?> tokenClass = Class.forName("android.window.WindowContainerToken");
            Class<?> wctClass = Class.forName("android.window.WindowContainerTransaction");
            Method setDensity = wctClass.getMethod("setDensityDpi", tokenClass, Integer.TYPE);
            Object wct = wctClass.getConstructor().newInstance();
            for (Object task : tasks) {
                if (!packageName.equals(taskPackage(task))) continue;
                Object token = field(task, "token");
                setDensity.invoke(wct, token, Integer.valueOf(densityDpi));
                applied++;
            }
            if (applied == 0)
                return "NO_MATCHING_TASK: Aplikasi belum memiliki task aktif pada display " + displayId;
            Class<?> organizerClass = Class.forName("android.window.WindowOrganizer");
            Object organizer = organizerClass.getConstructor().newInstance();
            organizerClass.getMethod("applyTransaction", wctClass).invoke(organizer, wct);
            return "TASK_DENSITY_APPLIED:" + applied + ":" + densityDpi;
        } catch (NoSuchMethodException error) {
            return "DPI_UNSUPPORTED: ROM tidak menyediakan WindowContainerTransaction.setDensityDpi(token, dpi)";
        } catch (Throwable error) {
            return failure("Per-App DPI", error);
        }
    }

    @Override
    public String closeTask(int taskId) {
        try {
            Object atm = getActivityTaskManager();
            Object result = atm.getClass().getMethod("removeTask", Integer.TYPE)
                    .invoke(atm, Integer.valueOf(taskId));
            return !Boolean.FALSE.equals(result) ? "TASK_CLOSED:" + taskId
                    : "ActivityTaskManager refused to close task";
        } catch (Throwable error) { return failure("Close task", error); }
    }


    private static void applySystemTransition(int transitionType,
            Class<?> transactionClass, Object transaction) throws Exception {
        Object organizer = newSharedWindowOrganizer();
        Object token = organizer.getClass()
                .getMethod("startNewTransition", Integer.TYPE, transactionClass)
                .invoke(organizer, Integer.valueOf(transitionType), transaction);
        if (!(token instanceof IBinder)) {
            throw new IllegalStateException("WindowManager did not return a transition token");
        }
    }

    private static synchronized Object newSharedWindowOrganizer() throws Exception {
        if (sharedWindowOrganizer == null) {
            Object organizer = Class.forName("android.window.WindowOrganizer")
                    .getConstructor().newInstance();
            Object shared = organizer.getClass().getMethod("shareTransactionQueue")
                    .invoke(organizer);
            if (!Boolean.TRUE.equals(shared)) {
                throw new IllegalStateException(
                        "WindowManager surface transaction queue is unavailable");
            }
            sharedWindowOrganizer = organizer;
        }
        return sharedWindowOrganizer;
    }

    private static synchronized void enableFreeformSettings() throws Exception {
        if (freeformSettingsEnabled) return;
        runSettings("enable_freeform_support");
        runSettings("force_resizable_activities");
        freeformSettingsEnabled = true;
    }

    private static void runSettings(String key) throws Exception {
        Process process = new ProcessBuilder("/system/bin/settings", "put", "global", key, "1")
                .redirectErrorStream(true).start();
        if (!process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("Timed out enabling " + key);
        }
        if (process.exitValue() != 0)
            throw new IllegalStateException("Could not enable " + key);
    }

    private static Object getActivityTaskManager() throws Exception {
        Class<?> owner = Class.forName("android.app.ActivityTaskManager");
        Method method = owner.getDeclaredMethod("getService");
        method.setAccessible(true);
        return method.invoke(null);
    }

    private static List<?> getTasks(Object atm, int displayId) throws Exception {
        Object value = atm.getClass().getMethod("getTasks",
                Integer.TYPE, Boolean.TYPE, Boolean.TYPE, Integer.TYPE)
                .invoke(atm, Integer.valueOf(100), Boolean.FALSE,
                        Boolean.FALSE, Integer.valueOf(displayId));
        if (!(value instanceof List)) throw new IllegalStateException("No task list returned");
        return (List<?>) value;
    }

    private static Object requireTask(Object atm, int displayId, int taskId) throws Exception {
        for (Object task : getTasks(atm, displayId))
            if (intField(task, "taskId") == taskId) return task;
        throw new IllegalStateException("Task " + taskId + " not found on display " + displayId);
    }

    private static int taskWindowingMode(Object task) throws Exception {
        Object config = field(field(task, "configuration"), "windowConfiguration");
        return ((Integer) config.getClass().getMethod("getWindowingMode").invoke(config)).intValue();
    }

    private static Rect taskBounds(Object task) throws Exception {
        Object config = field(field(task, "configuration"), "windowConfiguration");
        Object bounds = config.getClass().getMethod("getBounds").invoke(config);
        return bounds instanceof Rect ? new Rect((Rect) bounds) : new Rect();
    }

    private static String taskPackage(Object task) {
        try {
            Object top = field(task, "topActivity");
            if (top instanceof ComponentName) return ((ComponentName) top).getPackageName();
        } catch (Throwable ignored) { }
        try {
            Object base = field(task, "baseActivity");
            if (base instanceof ComponentName) return ((ComponentName) base).getPackageName();
        } catch (Throwable ignored) { }
        return null;
    }

    private static Object field(Object target, String name) throws Exception {
        Field f = target.getClass().getField(name);
        return f.get(target);
    }

    private static int intField(Object target, String name) throws Exception {
        return ((Integer) field(target, name)).intValue();
    }

    private static int optionalIntField(Object target, String name, int fallback) {
        try { return intField(target, name); } catch (Throwable ignored) { return fallback; }
    }

    private static boolean optionalBooleanField(Object target, String name, boolean fallback) {
        try { return ((Boolean) field(target, name)).booleanValue(); }
        catch (Throwable ignored) { return fallback; }
    }

    private static JSONObject rectJson(Rect r) throws Exception {
        JSONObject json = new JSONObject();
        json.put("left", r.left); json.put("top", r.top);
        json.put("right", r.right); json.put("bottom", r.bottom);
        return json;
    }

    private static String errorJson(Throwable error) {
        try { JSONObject json = new JSONObject(); json.put("error", rootMessage(error)); return json.toString(); }
        catch (Exception ignored) { return "{\"error\":\"Task query failed\"}"; }
    }

    private static String failure(String operation, Throwable error) {
        return operation + " failed: " + rootMessage(error);
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current)
            current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName()
                : current.getMessage();
    }
}