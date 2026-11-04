package io.github.forkdex;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.Intent;
import android.net.Uri;

import rikka.shizuku.Shizuku;

/** Owns Shizuku availability and runtime permission checks for Forkdex. */
final class ShizukuBridge {
    private static final int REQUEST_CODE = 8107;
    private final Context context;
    private Runnable onChanged = () -> {};

    ShizukuBridge(Context context) {
        this.context = context.getApplicationContext();
        Shizuku.addBinderReceivedListenerSticky(this::notifyChanged);
        Shizuku.addBinderDeadListener(this::notifyChanged);
        Shizuku.addRequestPermissionResultListener((requestCode, grantResult) -> {
            if (requestCode == REQUEST_CODE) notifyChanged();
        });
    }

    void setOnChanged(Runnable listener) {
        onChanged = listener == null ? () -> {} : listener;
    }

    boolean isInstalled() {
        try {
            context.getPackageManager().getPackageInfo(
                    "moe.shizuku.privileged.api", 0);
            return true;
        } catch (PackageManager.NameNotFoundException | RuntimeException error) {
            return false;
        }
    }

    boolean isBinderReady() {
        try { return Shizuku.pingBinder() && Shizuku.getVersion() >= 11; }
        catch (Throwable error) { return false; }
    }

    boolean hasPermission() {
        if (!isBinderReady()) return false;
        try { return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED; }
        catch (Throwable error) { return false; }
    }

    String status() {
        if (!isInstalled()) return "Shizuku Manager tidak terdeteksi.";
        if (!isBinderReady()) return "Shizuku Manager terpasang, tetapi layanan Shizuku belum aktif atau binder belum terhubung.";
        try {
            int version = Shizuku.getVersion();
            int uid = Shizuku.getUid();
            int permission = Shizuku.checkSelfPermission();
            if (permission != PackageManager.PERMISSION_GRANTED) return "Binder aktif (API " + version + ", UID " + uid + "), tetapi izin Forkdex belum terbaca GRANTED. Periksa izin Forkdex di Shizuku, lalu kembali ke aplikasi.";
            return "Izin Shizuku terdeteksi: GRANTED • API " + version + " • UID " + uid;
        } catch (Throwable error) {
            return "Pemeriksaan Shizuku gagal: " + error.getClass().getSimpleName() + ": " + error.getMessage();
        }
    }
    void requestPermission() {
        if (!isBinderReady()) {
            openManager();
            return;
        }
        if (hasPermission()) {
            notifyChanged();
            return;
        }
        Shizuku.requestPermission(REQUEST_CODE);
    }

    void openManager() {
        Intent launch = context.getPackageManager()
                .getLaunchIntentForPackage("moe.shizuku.privileged.api");
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(launch);
        } else {
            Intent browser = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://shizuku.rikka.app/download/"));
            browser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(browser);
        }
    }

    private void notifyChanged() {
        onChanged.run();
    }
}
