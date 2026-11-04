package io.github.forkdex;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.graphics.Rect;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import rikka.shizuku.Shizuku;

final class ForkdexWindowClient {
    private final Shizuku.UserServiceArgs args;
    private final Context appContext;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private IForkdexWindowService service;
    private boolean binding;
    private String lastError = "";
    private Runnable onConnectionChanged = () -> {};
    private final Runnable bindTimeout;

    private void handleBindTimeout() {
        if (binding && !isConnected()) {
            binding = false;
            lastError = "Timeout 15 detik: Shizuku tidak mengirim callback UserService. Periksa status layanan Shizuku.";
            ForkdexErrorLog.add(appContext, "UserService bind timeout", lastError);
            notifyConnectionChanged();
        }
    }

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            mainHandler.removeCallbacks(bindTimeout);
            if (binder == null || !binder.isBinderAlive()) {
                service = null;
                binding = false;
                lastError = "UserService mengembalikan Binder kosong atau sudah mati";
            } else {
                service = IForkdexWindowService.Stub.asInterface(binder);
                binding = false;
                lastError = "";
            }
            ForkdexErrorLog.add(appContext, "UserService onServiceConnected", lastError);
            notifyConnectionChanged();
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            mainHandler.removeCallbacks(bindTimeout);
            service = null;
            binding = false;
            lastError = "Binder UserService terputus";
            ForkdexErrorLog.add(appContext, "UserService onServiceDisconnected", lastError);
            notifyConnectionChanged();
        }
        @Override public void onBindingDied(ComponentName name) {
            mainHandler.removeCallbacks(bindTimeout);
            service = null;
            binding = false;
            lastError = "Android melaporkan binding UserService mati (onBindingDied).";
            ForkdexErrorLog.add(appContext, "UserService onBindingDied", lastError);
            notifyConnectionChanged();
        }
        @Override public void onNullBinding(ComponentName name) {
            mainHandler.removeCallbacks(bindTimeout);
            service = null;
            binding = false;
            lastError = "UserService mengembalikan null binding (onNullBinding). Periksa konstruktor dan kelas service.";
            ForkdexErrorLog.add(appContext, "UserService onNullBinding", lastError);
            notifyConnectionChanged();
        }
    };

    ForkdexWindowClient(Context context) {
        Context app = context.getApplicationContext();
        appContext = app;
        bindTimeout = this::handleBindTimeout;
        args = new Shizuku.UserServiceArgs(new ComponentName(app, ForkdexWindowService.class))
                .daemon(false)
                .processNameSuffix("forkdex_window")
                .tag("forkdex-native-windowing")
                .debuggable((app.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0)
                .version(4);
    }

    void setOnConnectionChanged(Runnable listener) {
        onConnectionChanged = listener == null ? () -> {} : listener;
    }

    private void notifyConnectionChanged() { onConnectionChanged.run(); }

    String connectionStatus() {
        if (isConnected()) return "Layanan Forkdex terhubung.";
        if (binding) return "Izin terdeteksi; sedang menghubungkan UserService Shizuku...";
        return lastError.isEmpty() ? "UserService belum terhubung." : "Gagal menghubungkan UserService: " + lastError;
    }

    boolean isConnected() { return service != null && service.asBinder().isBinderAlive(); }
    boolean isBinding() { return binding; }

    void connect() {
        if (binding || isConnected()) return;
        try {
            if (!Shizuku.pingBinder()) {
                lastError = "Binder Shizuku tidak aktif";
                ForkdexErrorLog.add(appContext, "Pre-bind check", lastError);
                notifyConnectionChanged();
                return;
            }
            if (Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                lastError = "Izin Shizuku Forkdex belum GRANTED";
                ForkdexErrorLog.add(appContext, "Pre-bind permission check", lastError);
                notifyConnectionChanged();
                return;
            }
            binding = true;
            lastError = "";
            notifyConnectionChanged();
            mainHandler.removeCallbacks(bindTimeout);
            mainHandler.postDelayed(bindTimeout, 15000L);
            ForkdexErrorLog.add(appContext, "UserService bind requested",
                    "class=" + ForkdexWindowService.class.getName() + ", tag=forkdex-native-windowing, version=4");
            Shizuku.bindUserService(args, connection);
        } catch (Throwable error) {
            mainHandler.removeCallbacks(bindTimeout);
            binding = false;
            service = null;
            lastError = error.getClass().getSimpleName() + ": " + error.getMessage();
            ForkdexErrorLog.add(appContext, "UserService bind exception", error);
            notifyConnectionChanged();
        }
    }

    String launch(String packageName, String className, int displayId, Rect bounds) {
        if (!isConnected()) {
            connect();
            return "Layanan native window belum terhubung. " + connectionStatus();
        }
        try { return service.launchFreeform(packageName, className, displayId, bounds); }
        catch (Exception error) { return "Gagal menjalankan native freeform: " + error.getMessage(); }
    }

    String listTasks(int displayId) throws Exception { return requireService().listTasks(displayId); }
    String resizeTask(int displayId, int taskId, Rect bounds) throws Exception { return requireService().resizeTask(displayId, taskId, bounds); }
    String setTaskMode(int displayId, int taskId, int mode, Rect bounds) throws Exception { return requireService().setTaskMode(displayId, taskId, mode, bounds); }
    String focusTask(int displayId, int taskId) throws Exception { return requireService().focusTask(displayId, taskId); }
    String minimizeTask(int displayId, int taskId) throws Exception { return requireService().minimizeTask(displayId, taskId); }
    String closeTask(int taskId) throws Exception { return requireService().closeTask(taskId); }
    String setPackageDensity(int displayId, String packageName, int densityDpi) throws Exception { return requireService().setPackageDensity(displayId, packageName, densityDpi); }

    String enableDesktopMode() throws Exception { return requireService().enableDesktopMode(); }

    String capabilities() {
        if (!isConnected()) return connectionStatus();
        try { return service.getWindowingCapabilities(); }
        catch (Exception error) { return "Pemeriksaan kemampuan gagal: " + error.getMessage(); }
    }

    void disconnect() {
        mainHandler.removeCallbacks(bindTimeout);
        try { if (binding || service != null) Shizuku.unbindUserService(args, connection, true); }
        catch (Exception ignored) { }
        service = null;
        binding = false;
        notifyConnectionChanged();
    }

    private IForkdexWindowService requireService() {
        if (!isConnected()) throw new IllegalStateException(connectionStatus());
        return service;
    }
}
