package io.github.forkdex;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.pm.ResolveInfo;
import android.content.pm.LauncherApps;
import android.content.pm.LauncherActivityInfo;
import android.os.UserHandle;
import android.os.Process;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.GridView;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

public final class MainActivity extends Activity {
    private final List<ResolveInfo> apps = new ArrayList<>();
    private final List<ResolveInfo> visibleApps = new ArrayList<>();
    private final List<LauncherActivityInfo> clonedApps = new ArrayList<>();
    private LauncherApps launcherApps;
    private android.content.SharedPreferences profiles;
    private GridView grid;
    private TextView heading;
    private ShizukuBridge shizuku;
    private ForkdexWindowClient windowClient;
    private ResolveInfo pendingFreeformApp;
    private Runnable pendingServiceAction;
    private final android.os.Handler taskbarHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private LinearLayout taskButtons;
    private TextView taskbarClock;
    private DisplayManager displayManager;
    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
        @Override public void onDisplayAdded(int displayId) { }
        @Override public void onDisplayChanged(int displayId) { }
        @Override public void onDisplayRemoved(int displayId) {
            if (getDisplay() != null && getDisplay().getDisplayId() == displayId) {
                Toast.makeText(MainActivity.this, "Layar eksternal terputus.", Toast.LENGTH_LONG).show();
                finish();
            }
        }
    };
    private final Runnable taskbarTicker = new Runnable() {
        @Override public void run() {
            if (taskbarClock != null) taskbarClock.setText(new java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(new java.util.Date()));
            refreshTaskbarTasks();
            taskbarHandler.postDelayed(this, 3000L);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        profiles = getSharedPreferences("app_profiles", MODE_PRIVATE);
        shizuku = new ShizukuBridge(this);
        windowClient = new ForkdexWindowClient(this);
        displayManager = (DisplayManager) getSystemService(DISPLAY_SERVICE);
        launcherApps = (LauncherApps) getSystemService(LAUNCHER_APPS_SERVICE);
        if (displayManager != null) {
            displayManager.registerDisplayListener(displayListener, taskbarHandler);
        }
        shizuku.setOnChanged(() -> runOnUiThread(this::showShizukuStatus));
        windowClient.setOnConnectionChanged(() -> runOnUiThread(() -> {
            if (windowClient.isConnected() && pendingFreeformApp != null) {
                ResolveInfo app = pendingFreeformApp;
                pendingFreeformApp = null;
                launchFreeformNow(app);
            } else if (windowClient.isConnected() && pendingServiceAction != null) {
                Runnable action = pendingServiceAction;
                pendingServiceAction = null;
                action.run();
            } else if (!windowClient.isBinding()) {
                Toast.makeText(this, windowClient.connectionStatus(), Toast.LENGTH_LONG).show();
            }
        }));
        getWindow().setStatusBarColor(Color.rgb(22, 29, 42));
        getWindow().setNavigationBarColor(Color.rgb(22, 29, 42));
        loadApps();
        render();
    }

    private void loadApps() {
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        apps.clear();
        apps.addAll(getPackageManager().queryIntentActivities(query, 0));
        loadClonedApps();
        Collections.sort(apps, Comparator.comparing(
            a -> a.loadLabel(getPackageManager()).toString().toLowerCase()));
        visibleApps.clear();
        visibleApps.addAll(apps);
    }


    private void loadClonedApps() {
        clonedApps.clear();
        if (launcherApps == null || Build.VERSION.SDK_INT < 21) return;
        try {
            UserHandle current = Process.myUserHandle();
            for (UserHandle profile : launcherApps.getProfiles()) {
                if (profile.equals(current)) continue;
                for (LauncherActivityInfo info : launcherApps.getActivityList(null, profile)) {
                    if (info != null && info.getComponentName() != null) clonedApps.add(info);
                }
            }
            Collections.sort(clonedApps, Comparator.comparing(
                    item -> item.getLabel().toString().toLowerCase()));
        } catch (Throwable error) {
            ForkdexErrorLog.add(this, "Load cloned apps/profiles", error);
        }
    }

    private FrameLayout desktopRoot;
    private LinearLayout appDrawer;
    private LinearLayout drawerApps;
    private boolean drawerVisible = false;

    private void render() {
        // Ask Android to draw the current system wallpaper behind the launcher window.
        // This also supports live wallpapers and avoids relying on getDrawable(), which
        // may return null or a stale image on newer Android releases.
        try {
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER);
            getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            getWindow().setDimAmount(0f);
        } catch (Throwable error) {
            ForkdexErrorLog.add(this, "Enable system wallpaper background", error);
        }

        desktopRoot = new FrameLayout(this);
        desktopRoot.setBackgroundColor(Color.TRANSPARENT);
        setContentView(desktopRoot);

        // Light dim layer keeps desktop controls readable without replacing the wallpaper.
        View shade = new View(this);
        shade.setBackgroundColor(0x05000000);
        desktopRoot.addView(shade, new FrameLayout.LayoutParams(-1, -1));

        if (currentDisplayId() != Display.DEFAULT_DISPLAY) {
            TextView displayLabel = text("DESKTOP  •  " + displayName(currentDisplayId()), 12, true);
            displayLabel.setTextColor(Color.WHITE);
            displayLabel.setBackground(rounded(0x660D111B, 10));
            displayLabel.setPadding(dp(12), dp(7), dp(12), dp(7));
            FrameLayout.LayoutParams labelParams = new FrameLayout.LayoutParams(-2, -2,
                    Gravity.TOP | Gravity.RIGHT);
            labelParams.setMargins(0, dp(12), dp(12), 0);
            desktopRoot.addView(displayLabel, labelParams);
        }

        renderTaskbar(desktopRoot);
        createAppDrawer();
        setDrawerVisible(drawerVisible);
    }

    private LinearLayout pinnedApps;

    private void createAppDrawer() {
        appDrawer = new LinearLayout(this);
        appDrawer.setOrientation(LinearLayout.VERTICAL);
        appDrawer.setPadding(dp(18), dp(14), dp(18), dp(12));
        appDrawer.setBackground(glassPanel(0xB81B2638, 22));
        appDrawer.setElevation(dp(32));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("Start", 23, true);
        title.setTextColor(0xFFF7F9FC);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(42), 1f));
        TextView tools = text("⋯", 25, true);
        tools.setGravity(Gravity.CENTER);
        tools.setTextColor(0xFFE8EDF6);
        tools.setBackground(glassControl(0x703E4B61, 22));
        tools.setOnClickListener(v -> showLauncherOptions());
        header.addView(tools, new LinearLayout.LayoutParams(dp(42), dp(42)));
        TextView close = text("×", 24, false);
        close.setGravity(Gravity.CENTER);
        close.setTextColor(0xFFCFD8E8);
        close.setBackground(glassControl(0x703E4B61, 22));
        close.setOnClickListener(v -> setDrawerVisible(false));
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(dp(42), dp(42));
        closeParams.setMargins(dp(8), 0, 0, 0);
        header.addView(close, closeParams);
        appDrawer.addView(header);

        EditText search = new EditText(this);
        search.setSingleLine(true);
        search.setTextSize(14);
        search.setHint("Cari aplikasi dan pengaturan");
        search.setHintTextColor(0xFF9EABC0);
        search.setTextColor(0xFFF4F7FC);
        search.setPadding(dp(15), 0, dp(15), 0);
        search.setBackground(glassControl(0x7838465B, 13));
        search.setCompoundDrawablePadding(dp(8));
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(-1, dp(48));
        searchParams.setMargins(0, dp(12), 0, dp(12));
        appDrawer.addView(search, searchParams);

        LinearLayout pinHeader = new LinearLayout(this);
        pinHeader.setGravity(Gravity.CENTER_VERTICAL);
        TextView pinTitle = text("Pinned", 15, true);
        pinTitle.setTextColor(0xFFF1F4FA);
        pinHeader.addView(pinTitle, new LinearLayout.LayoutParams(0, dp(30), 1f));
        TextView editPins = text("Kelola", 12, true);
        editPins.setTextColor(0xFF9FC5FF);
        editPins.setGravity(Gravity.CENTER);
        editPins.setOnClickListener(v -> Toast.makeText(this,
                "Tekan lama aplikasi lalu pilih Sematkan atau Lepas sematan.", Toast.LENGTH_LONG).show());
        pinHeader.addView(editPins, new LinearLayout.LayoutParams(-2, dp(30)));
        appDrawer.addView(pinHeader);

        android.widget.HorizontalScrollView pinScroll = new android.widget.HorizontalScrollView(this);
        pinScroll.setHorizontalScrollBarEnabled(false);
        pinScroll.setFillViewport(false);
        pinnedApps = new LinearLayout(this);
        pinnedApps.setGravity(Gravity.CENTER_VERTICAL);
        pinScroll.addView(pinnedApps, new android.widget.HorizontalScrollView.LayoutParams(-2, -1));
        appDrawer.addView(pinScroll, new LinearLayout.LayoutParams(-1, dp(82)));
        populatePinnedApps();

        LinearLayout section = new LinearLayout(this);
        section.setGravity(Gravity.CENTER_VERTICAL);
        TextView allApps = text("All apps", 16, true);
        allApps.setTextColor(0xFFF1F4FA);
        section.addView(allApps, new LinearLayout.LayoutParams(0, dp(34), 1f));
        TextView sort = text("A–Z  ▾", 12, true);
        sort.setTextColor(0xFFE6EDF8);
        sort.setGravity(Gravity.CENTER);
        sort.setPadding(dp(12), 0, dp(12), 0);
        sort.setBackground(glassControl(0x703E4B61, 10));
        sort.setOnClickListener(v -> showSortOptions());
        section.addView(sort, new LinearLayout.LayoutParams(-2, dp(32)));
        appDrawer.addView(section);

        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.setFillViewport(false);
        scroll.setVerticalScrollBarEnabled(false);
        drawerApps = new LinearLayout(this);
        drawerApps.setOrientation(LinearLayout.VERTICAL);
        drawerApps.setPadding(0, dp(5), 0, dp(5));
        scroll.addView(drawerApps, new android.widget.ScrollView.LayoutParams(-1, -2));
        appDrawer.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        View separator = new View(this);
        separator.setBackgroundColor(0x557D8BA0);
        LinearLayout.LayoutParams separatorParams = new LinearLayout.LayoutParams(-1, dp(1));
        separatorParams.setMargins(0, dp(7), 0, dp(7));
        appDrawer.addView(separator, separatorParams);

        LinearLayout footer = new LinearLayout(this);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        TextView user = text("Forkdex  •  User profile", 12, true);
        user.setTextColor(0xFFDCE5F2);
        footer.addView(user, new LinearLayout.LayoutParams(0, dp(38), 1f));
        TextView power = text("⏻", 21, true);
        power.setTextColor(Color.WHITE);
        power.setGravity(Gravity.CENTER);
        power.setBackground(glassControl(0x8043546C, 12));
        power.setOnClickListener(v -> showPowerOptions());
        footer.addView(power, new LinearLayout.LayoutParams(dp(44), dp(40)));
        appDrawer.addView(footer);

        int screenW = getResources().getDisplayMetrics().widthPixels;
        int screenH = getResources().getDisplayMetrics().heightPixels;
        int panelW = Math.min(dp(600), (int)(screenW * 0.94f));
        int panelH = Math.min(dp(690), (int)(screenH * 0.82f));
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                panelW, panelH, Gravity.TOP | Gravity.LEFT);
        params.setMargins(dp(10), dp(10), 0, 0);
        desktopRoot.addView(appDrawer, params);

        search.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence value, int start, int count, int after) { }
            public void onTextChanged(CharSequence value, int start, int before, int count) {
                String q = value.toString().trim().toLowerCase();
                List<ResolveInfo> filtered = new ArrayList<>();
                for (ResolveInfo app : apps)
                    if (app.loadLabel(getPackageManager()).toString().toLowerCase().contains(q)
                            && !isHiddenApp(app.activityInfo.packageName)) filtered.add(app);
                List<LauncherActivityInfo> filteredClones = new ArrayList<>();
                for (LauncherActivityInfo clone : clonedApps) {
                    String label = clone.getLabel() == null ? "" : clone.getLabel().toString();
                    if (label.toLowerCase().contains(q)) filteredClones.add(clone);
                }
                populateDrawerApps(filtered, filteredClones);
            }
            public void afterTextChanged(android.text.Editable value) { }
        });
        populateDrawerApps(apps, clonedApps);
    }

    private boolean isHiddenApp(String pkg) {
        return getSharedPreferences("launcher_preferences", MODE_PRIVATE)
                .getStringSet("hidden_apps", Collections.emptySet()).contains(pkg);
    }

    private void populatePinnedApps() {
        if (pinnedApps == null) return;
        pinnedApps.removeAllViews();
        java.util.Set<String> pins = getSharedPreferences("launcher_preferences", MODE_PRIVATE)
                .getStringSet("pinned_apps", Collections.emptySet());
        int count = 0;
        for (ResolveInfo app : apps) {
            String pkg = app.activityInfo.packageName;
            if (!pins.contains(pkg) || isHiddenApp(pkg)) continue;
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER);
            ImageView icon = new ImageView(this);
            icon.setImageDrawable(app.loadIcon(getPackageManager()));
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            item.addView(icon, new LinearLayout.LayoutParams(dp(36), dp(36)));
            TextView label = text(app.loadLabel(getPackageManager()).toString(), 10, false);
            label.setTextColor(0xFFE8EDF6);
            label.setGravity(Gravity.CENTER);
            label.setMaxLines(1);
            item.addView(label, new LinearLayout.LayoutParams(dp(66), dp(20)));
            item.setOnClickListener(v -> { setDrawerVisible(false); launchSelectedApp(app); });
            item.setOnLongClickListener(v -> { showAppActions(app); return true; });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(70), -1);
            lp.setMargins(0, 0, dp(5), 0);
            pinnedApps.addView(item, lp);
            count++;
        }
        if (count == 0) {
            TextView empty = text("Sematkan aplikasi favorit dengan menekan lama ikonnya.", 11, false);
            empty.setTextColor(0xFF9EABC0);
            empty.setGravity(Gravity.CENTER_VERTICAL);
            pinnedApps.addView(empty, new LinearLayout.LayoutParams(-1, -1));
        }
    }

    private void showLauncherOptions() {
        String[] options = {"Segarkan daftar aplikasi", "Tampilkan aplikasi tersembunyi",
                "Pengaturan Android", "Pengaturan layar & wallpaper", "Pengaturan Forkdex"};
        new AlertDialog.Builder(this).setTitle("Launcher options").setItems(options, (d, which) -> {
            if (which == 0) {
                loadApps();
                populateDrawerApps(apps, clonedApps);
                populatePinnedApps();
                Toast.makeText(this, "Daftar aplikasi diperbarui.", Toast.LENGTH_SHORT).show();
            } else if (which == 1) showHiddenApps();
            else if (which == 2) openSystemSettings();
            else if (which == 3) openWallpaperSettings();
            else showTaskbarPanel();
        }).show();
    }

    private void showSortOptions() {
        String[] options = {"Nama (A–Z)", "Nama (Z–A)"};
        new AlertDialog.Builder(this).setTitle("Urutkan aplikasi").setItems(options, (d, which) -> {
            getSharedPreferences("launcher_preferences", MODE_PRIVATE).edit()
                    .putBoolean("sort_descending", which == 1).apply();
            Collections.sort(apps, (a, b) -> {
                int result = a.loadLabel(getPackageManager()).toString().compareToIgnoreCase(
                        b.loadLabel(getPackageManager()).toString());
                return which == 1 ? -result : result;
            });
            populateDrawerApps(apps, clonedApps);
        }).show();
    }

    private void showHiddenApps() {
        java.util.Set<String> hidden = new java.util.HashSet<>(getSharedPreferences(
                "launcher_preferences", MODE_PRIVATE).getStringSet("hidden_apps", Collections.emptySet()));
        if (hidden.isEmpty()) {
            new AlertDialog.Builder(this).setMessage("Belum ada aplikasi tersembunyi.")
                    .setPositiveButton("OK", null).show();
            return;
        }
        List<String> names = new ArrayList<>();
        List<String> packages = new ArrayList<>(hidden);
        for (String pkg : packages) names.add(applicationLabel(pkg));
        new AlertDialog.Builder(this).setTitle("Aplikasi tersembunyi").setItems(
                names.toArray(new String[0]), (d, which) -> {
                    hidden.remove(packages.get(which));
                    getSharedPreferences("launcher_preferences", MODE_PRIVATE).edit()
                            .putStringSet("hidden_apps", hidden).apply();
                    populateDrawerApps(apps, clonedApps);
                }).setNegativeButton("Tutup", null).show();
    }

    private void showAppActions(ResolveInfo app) {
        String pkg = app.activityInfo.packageName;
        java.util.Set<String> pins = new java.util.HashSet<>(getSharedPreferences(
                "launcher_preferences", MODE_PRIVATE).getStringSet("pinned_apps", Collections.emptySet()));
        boolean pinned = pins.contains(pkg);
        String[] actions = {"Buka", pinned ? "Lepas sematan" : "Sematkan ke Start",
                "Info aplikasi", "Atur Per-App DPI", "Sembunyikan aplikasi", "Uninstall"};
        new AlertDialog.Builder(this).setTitle(app.loadLabel(getPackageManager()))
                .setItems(actions, (d, which) -> {
                    if (which == 0) { setDrawerVisible(false); launchSelectedApp(app); }
                    else if (which == 1) {
                        if (pinned) pins.remove(pkg); else pins.add(pkg);
                        getSharedPreferences("launcher_preferences", MODE_PRIVATE).edit()
                                .putStringSet("pinned_apps", pins).apply();
                        populatePinnedApps();
                    } else if (which == 2) openAppInfo(pkg);
                    else if (which == 3) showProfile(app);
                    else if (which == 4) {
                        java.util.Set<String> hidden = new java.util.HashSet<>(getSharedPreferences(
                                "launcher_preferences", MODE_PRIVATE).getStringSet("hidden_apps", Collections.emptySet()));
                        hidden.add(pkg);
                        getSharedPreferences("launcher_preferences", MODE_PRIVATE).edit()
                                .putStringSet("hidden_apps", hidden).apply();
                        populateDrawerApps(apps, clonedApps);
                        populatePinnedApps();
                    } else requestUninstall(pkg);
                }).show();
    }

    private void openAppInfo(String pkg) {
        try {
            Intent intent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:" + pkg));
            startActivity(intent);
        } catch (Exception error) {
            Toast.makeText(this, "Info aplikasi tidak tersedia.", Toast.LENGTH_SHORT).show();
        }
    }

    private void requestUninstall(String pkg) {
        try {
            startActivity(new Intent(Intent.ACTION_DELETE, android.net.Uri.parse("package:" + pkg)));
        } catch (Exception error) {
            Toast.makeText(this, "Uninstall tidak didukung untuk aplikasi ini.", Toast.LENGTH_SHORT).show();
        }
    }

    private void openSystemSettings() {
        try { startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS)); }
        catch (Exception ignored) { Toast.makeText(this, "Pengaturan tidak tersedia.", Toast.LENGTH_SHORT).show(); }
    }

    private void openWallpaperSettings() {
        try { startActivity(new Intent(Intent.ACTION_SET_WALLPAPER)); }
        catch (Exception ignored) { openSystemSettings(); }
    }

    private void showPowerOptions() {
        String[] actions = {"Pengaturan Android", "Kunci layar (jika didukung)", "Tutup Forkdex"};
        new AlertDialog.Builder(this).setTitle("Power & system").setItems(actions, (d, which) -> {
            if (which == 0) openSystemSettings();
            else if (which == 1) {
                if (Build.VERSION.SDK_INT >= 28) {
                    try { startActivity(new Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS)); }
                    catch (Exception ignored) { openSystemSettings(); }
                } else openSystemSettings();
            } else finish();
        }).show();
    }

    private void addDrawerTool(LinearLayout row, String glyph, String label,
                               View.OnClickListener listener) {
        TextView button = text(glyph, 18, true);
        button.setGravity(Gravity.CENTER);
        button.setTextColor(Color.WHITE);
        button.setContentDescription(label);
        button.setBackground(rounded(0x552E3545, 10));
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(38), 1f);
        p.setMargins(dp(2), 0, dp(2), 0);
        row.addView(button, p);
    }

    private void populateDrawerApps(List<ResolveInfo> source, List<LauncherActivityInfo> cloneSource) {
        if (drawerApps == null) return;
        drawerApps.removeAllViews();
        List<View> tiles = new ArrayList<>();
        for (ResolveInfo app : source) {
            String pkg = app.activityInfo.packageName;
            if (isHiddenApp(pkg)) continue;
            String labelText = app.loadLabel(getPackageManager()).toString();
            LinearLayout tile = new LinearLayout(this);
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setGravity(Gravity.CENTER);
            tile.setPadding(dp(3), dp(5), dp(3), dp(5));
            ImageView icon = new ImageView(this);
            icon.setImageDrawable(app.loadIcon(getPackageManager()));
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            tile.addView(icon, new LinearLayout.LayoutParams(dp(46), dp(46)));
            TextView label = text(labelText, 11, false);
            label.setTextColor(0xFFE8EDF6);
            label.setGravity(Gravity.CENTER);
            label.setMaxLines(2);
            label.setIncludeFontPadding(false);
            tile.addView(label, new LinearLayout.LayoutParams(-1, dp(32)));
            tile.setBackground(rounded(0x002F3B50, 11));
            tile.setOnClickListener(v -> { setDrawerVisible(false); launchSelectedApp(app); });
            tile.setOnLongClickListener(v -> { showAppActions(app); return true; });
            tiles.add(tile);
        }
        for (LauncherActivityInfo clone : cloneSource) {
            String name = clone.getLabel() == null
                    ? clone.getComponentName().getPackageName() : clone.getLabel().toString();
            LinearLayout tile = new LinearLayout(this);
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setGravity(Gravity.CENTER);
            tile.setPadding(dp(3), dp(5), dp(3), dp(5));
            ImageView icon = new ImageView(this);
            icon.setImageDrawable(clone.getIcon(0));
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            tile.addView(icon, new LinearLayout.LayoutParams(dp(46), dp(46)));
            TextView label = text(name, 11, false);
            label.setTextColor(0xFFBBD7FF);
            label.setGravity(Gravity.CENTER);
            label.setMaxLines(2);
            label.setIncludeFontPadding(false);
            tile.addView(label, new LinearLayout.LayoutParams(-1, dp(32)));
            tile.setBackground(rounded(0x002F3B50, 11));
            tile.setOnClickListener(v -> { setDrawerVisible(false); launchClonedApp(clone); });
            tiles.add(tile);
        }
        int widthDp = Math.round(getResources().getDisplayMetrics().widthPixels /
                getResources().getDisplayMetrics().density);
        int columns = widthDp < 380 ? 4 : 5;
        for (int i = 0; i < tiles.size(); i += columns) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int j = i; j < Math.min(i + columns, tiles.size()); j++) {
                LinearLayout.LayoutParams cell = new LinearLayout.LayoutParams(0, dp(88), 1f);
                cell.setMargins(dp(2), dp(2), dp(2), dp(2));
                row.addView(tiles.get(j), cell);
            }
            for (int j = Math.min(i + columns, tiles.size()); j < i + columns; j++) {
                View empty = new View(this);
                row.addView(empty, new LinearLayout.LayoutParams(0, dp(88), 1f));
            }
            drawerApps.addView(row, new LinearLayout.LayoutParams(-1, dp(92)));
        }
    }

    private void launchClonedApp(LauncherActivityInfo clone) {
        try {
            if (launcherApps == null) throw new IllegalStateException("LauncherApps tidak tersedia");
            ActivityOptions options = ActivityOptions.makeBasic();
            if (Build.VERSION.SDK_INT >= 26 && currentDisplayId() != Display.DEFAULT_DISPLAY)
                options.setLaunchDisplayId(currentDisplayId());
            launcherApps.startMainActivity(clone.getComponentName(), clone.getUser(),
                    new Rect(), options.toBundle());
            Toast.makeText(this, "Membuka salinan aplikasi: " + clone.getLabel(),
                    Toast.LENGTH_SHORT).show();
        } catch (Throwable error) {
            ForkdexErrorLog.add(this, "Launch cloned app " + clone.getComponentName(), error);
            Toast.makeText(this, "Gagal membuka aplikasi clone: " + error.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void showTaskbarPanel() {
        String[] actions = {"Per-App DPI", "Shizuku", "Log diagnostik", "Jendela berjalan",
                "Desktop Mode", "Kembali ke mode normal",
                getSharedPreferences("desktop_preferences", MODE_PRIVATE).getBoolean("freeform_enabled", true)
                        ? "Mode jendela: Freeform (ketuk untuk Normal)"
                        : "Mode jendela: Normal (ketuk untuk Freeform)"};
        new AlertDialog.Builder(this).setTitle("Quick settings  •  Forkdex")
            .setItems(actions, (dialog, which) -> {
                switch (which) {
                    case 0: showProfiles(); break;
                    case 1: showShizukuDialog(); break;
                    case 2: showErrorLogs(); break;
                    case 3: showWindows(); break;
                    case 4: showDesktopModeSetup(); break;
                    case 6: toggleWindowMode(); break;
                    case 5:
                        if (currentDisplayId() != Display.DEFAULT_DISPLAY) {
                            Toast.makeText(this, "Pilih Home launcher normal pada layar utama Android.", Toast.LENGTH_LONG).show();
                            finish();
                        } else {
                            setDrawerVisible(false);
                            Toast.makeText(this, "Forkdex kembali ke tampilan utama.", Toast.LENGTH_SHORT).show();
                        }
                        break;
                }
            }).setNegativeButton("Close", null).show();
    }

    private void toggleWindowMode() {
        android.content.SharedPreferences prefs =
                getSharedPreferences("desktop_preferences", MODE_PRIVATE);
        boolean enabled = !prefs.getBoolean("freeform_enabled", true);
        prefs.edit().putBoolean("freeform_enabled", enabled).apply();
        Toast.makeText(this, enabled
                ? "Mode Freeform aktif. Aplikasi dibuka sebagai jendela mengambang."
                : "Mode Normal aktif. Aplikasi dibuka tanpa Freeform.",
                Toast.LENGTH_LONG).show();
    }

    private void setDrawerVisible(boolean visible) {
        drawerVisible = visible;
        if (appDrawer != null) appDrawer.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private GradientDrawable rounded(int color, float radiusDp) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radiusDp));
        return shape;
    }

    /** Translucent tinted glass with a quiet cool edge and a soft top reflection. */
    private android.graphics.drawable.Drawable glassPanel(int color, float radiusDp) {
        int radius = dp(radiusDp);
        GradientDrawable tint = new GradientDrawable();
        tint.setColor(color);
        tint.setCornerRadius(radius);
        tint.setStroke(dp(1), 0x55E5F1FF);

        GradientDrawable reflection = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {0x25FFFFFF, 0x0BFFFFFF, 0x00FFFFFF});
        reflection.setCornerRadius(radius);
        reflection.setStroke(dp(1), 0x22FFFFFF);

        android.graphics.drawable.LayerDrawable glass =
                new android.graphics.drawable.LayerDrawable(
                        new android.graphics.drawable.Drawable[] {tint, reflection});
        int inset = dp(1);
        glass.setLayerInset(1, inset, inset, inset, inset);
        return glass;
    }

    private android.graphics.drawable.Drawable glassControl(int color, float radiusDp) {
        GradientDrawable control = rounded(color, radiusDp);
        control.setStroke(dp(1), 0x35E5F1FF);
        return control;
    }

    private void renderTaskbar(FrameLayout root) {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(16), dp(5), dp(16), dp(5));
        bar.setBackground(rounded(0xA8161D2A, 0));
        bar.setElevation(dp(24));

        TextView start = text("⊞", 26, true);
        start.setTextColor(0xFFF4F7FC);
        start.setGravity(Gravity.CENTER);
        start.setBackground(rounded(0xFF2876D2, 10));
        start.setOnClickListener(v -> setDrawerVisible(
                appDrawer == null || appDrawer.getVisibility() != View.VISIBLE));
        bar.addView(start, new LinearLayout.LayoutParams(dp(44), dp(40)));

        TextView search = text("⌕   Search", 13, false);
        search.setTextColor(0xFFE0E7F1);
        search.setGravity(Gravity.CENTER_VERTICAL);
        search.setPadding(dp(13), 0, dp(16), 0);
        search.setBackground(rounded(0xFF30394A, 10));
        search.setOnClickListener(v -> setDrawerVisible(true));
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(dp(132), dp(36));
        searchParams.setMargins(dp(10), 0, dp(16), 0);
        bar.addView(search, searchParams);

        android.widget.HorizontalScrollView scroll = new android.widget.HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setFillViewport(false);
        taskButtons = new LinearLayout(this);
        taskButtons.setOrientation(LinearLayout.HORIZONTAL);
        taskButtons.setGravity(Gravity.CENTER_VERTICAL);
        scroll.addView(taskButtons, new android.widget.HorizontalScrollView.LayoutParams(-2, -1));
        LinearLayout.LayoutParams taskParams = new LinearLayout.LayoutParams(0, dp(42), 1f);
        taskParams.setMargins(dp(4), 0, dp(10), 0);
        bar.addView(scroll, taskParams);

        View divider = new View(this);
        divider.setBackgroundColor(0x667D899B);
        bar.addView(divider, new LinearLayout.LayoutParams(dp(1), dp(25)));

        TextView quick = text("⌃", 18, true);
        quick.setTextColor(0xFFE8EDF6);
        quick.setGravity(Gravity.CENTER);
        quick.setBackground(rounded(0xFF30394A, 9));
        quick.setOnClickListener(v -> showTaskbarPanel());
        LinearLayout.LayoutParams quickParams = new LinearLayout.LayoutParams(dp(34), dp(34));
        quickParams.setMargins(dp(10), 0, dp(8), 0);
        bar.addView(quick, quickParams);

        LinearLayout clockBox = new LinearLayout(this);
        clockBox.setOrientation(LinearLayout.VERTICAL);
        clockBox.setGravity(Gravity.CENTER);
        taskbarClock = text("--:--", 12, true);
        taskbarClock.setTextColor(0xFFF1F4F9);
        taskbarClock.setGravity(Gravity.CENTER);
        TextView date = text(new java.text.SimpleDateFormat("dd/MM/yyyy",
                java.util.Locale.getDefault()).format(new java.util.Date()), 9, false);
        date.setTextColor(0xFFB8C3D3);
        date.setGravity(Gravity.CENTER);
        clockBox.addView(taskbarClock);
        clockBox.addView(date);
        clockBox.setOnClickListener(v -> showTaskbarPanel());
        bar.addView(clockBox, new LinearLayout.LayoutParams(dp(68), dp(40)));

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                -1, dp(58), Gravity.BOTTOM);
        root.addView(bar, params);
        refreshTaskbarTasks();
    }

    private void refreshTaskbarTasks() {
        if (taskButtons == null || !windowClient.isConnected()) return;
        final int displayId = currentDisplayId();
        new Thread(() -> {
            try {
                String raw = windowClient.listTasks(displayId);
                JSONArray tasks = new JSONArray(raw);
                saveActiveWindowBounds(tasks);
                runOnUiThread(() -> {
                    if (taskButtons == null) return;
                    taskButtons.removeAllViews();
                    for (int i = 0; i < tasks.length(); i++) {
                        JSONObject task = tasks.optJSONObject(i);
                        if (task == null) continue;
                        int taskId = task.optInt("taskId", -1);
                        String pkg = task.optString("packageName", "App");
                        boolean focused = task.optBoolean("focused", false);
                        FrameLayout item = new FrameLayout(this);
                        item.setBackground(rounded(
                                focused ? Color.rgb(61, 72, 103) : Color.TRANSPARENT, 9));
                        item.setContentDescription(applicationLabel(pkg));
                        ImageView icon = new ImageView(this);
                        icon.setImageDrawable(applicationIcon(pkg));
                        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
                        FrameLayout.LayoutParams iconParams = new FrameLayout.LayoutParams(
                                dp(25), dp(25), Gravity.CENTER);
                        iconParams.bottomMargin = dp(3);
                        item.addView(icon, iconParams);
                        View activeMark = new View(this);
                        activeMark.setBackground(rounded(
                                focused ? Color.rgb(133, 170, 255) : Color.TRANSPARENT, 3));
                        FrameLayout.LayoutParams markParams = new FrameLayout.LayoutParams(
                                dp(15), dp(3), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
                        markParams.bottomMargin = dp(3);
                        item.addView(activeMark, markParams);
                        item.setOnClickListener(v -> new Thread(() -> {
                            try { windowClient.focusTask(displayId, taskId); }
                            catch (Exception error) {
                                runOnUiThread(() -> Toast.makeText(this,
                                        error.getMessage(), Toast.LENGTH_SHORT).show());
                            }
                            refreshTaskbarTasks();
                        }, "Forkdex-Taskbar-Focus").start());
                        item.setOnLongClickListener(v -> {
                            showWindowActions(task, displayId);
                            return true;
                        });
                        LinearLayout.LayoutParams itemParams =
                                new LinearLayout.LayoutParams(dp(44), dp(40));
                        itemParams.setMargins(dp(2), 0, dp(2), 0);
                        taskButtons.addView(item, itemParams);
                    }
                });
            } catch (Exception error) {
                ForkdexErrorLog.add(this, "Taskbar task refresh", error);
            }
        }, "Forkdex-Taskbar-Refresh").start();
    }

    private void saveActiveWindowBounds(JSONArray tasks) {
        android.content.SharedPreferences.Editor editor = getSharedPreferences(
                "freeform_window_layouts", MODE_PRIVATE).edit();
        boolean changed = false;
        for (int i = 0; i < tasks.length(); i++) {
            JSONObject task = tasks.optJSONObject(i);
            if (task == null || task.optInt("windowingMode", 0) != 5) continue;
            String pkg = task.optString("packageName", "");
            JSONObject bounds = task.optJSONObject("bounds");
            if (pkg.isEmpty() || bounds == null) continue;
            int left = bounds.optInt("left"), top = bounds.optInt("top");
            int right = bounds.optInt("right"), bottom = bounds.optInt("bottom");
            if (right <= left || bottom <= top) continue;
            try {
                JSONObject saved = new JSONObject();
                saved.put("left", left); saved.put("top", top);
                saved.put("right", right); saved.put("bottom", bottom);
                editor.putString(pkg, saved.toString());
                changed = true;
            } catch (Exception ignored) { }
        }
        if (changed) editor.apply();
    }

    private String applicationLabel(String packageName) {
        try {
            return getPackageManager().getApplicationLabel(
                    getPackageManager().getApplicationInfo(packageName, 0)).toString();
        } catch (Exception ignored) {
            return shortAppLabel(packageName);
        }
    }

    private android.graphics.drawable.Drawable applicationIcon(String packageName) {
        try {
            return getPackageManager().getApplicationIcon(packageName);
        } catch (Exception ignored) {
            return getDrawable(android.R.drawable.sym_def_app_icon);
        }
    }

    private String shortAppLabel(String packageName) {
        int slash = packageName.lastIndexOf('.');
        String label = slash >= 0 ? packageName.substring(slash + 1) : packageName;
        return label.length() > 12 ? label.substring(0, 11) + "…" : label;
    }

    private final class AppAdapter extends BaseAdapter {
        public int getCount() { return visibleApps.size(); }
        public Object getItem(int p) { return visibleApps.get(p); }
        public long getItemId(int p) { return p; }
        public View getView(int p, View old, android.view.ViewGroup parent) {
            ResolveInfo item = visibleApps.get(p);
            LinearLayout cell = new LinearLayout(MainActivity.this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER);
            cell.setPadding(4, 8, 4, 8);
            android.widget.ImageView icon = new android.widget.ImageView(MainActivity.this);
            icon.setImageDrawable(item.loadIcon(getPackageManager()));
            cell.addView(icon, new LinearLayout.LayoutParams(48, 48));
            TextView name = text(item.loadLabel(getPackageManager()).toString(), 11, false);
            name.setGravity(Gravity.CENTER);
            name.setMaxLines(2);
            cell.addView(name);
            cell.setOnClickListener(v -> launchSelectedApp(item));
            cell.setOnLongClickListener(v -> { showProfile(item); return true; });
            return cell;
        }
    }

    private void launchSelectedApp(ResolveInfo app) {
        if (!getSharedPreferences("desktop_preferences", MODE_PRIVATE)
                .getBoolean("freeform_enabled", true)) {
            launchNormally(app);
            return;
        }
        openFreeform(app);
    }

    private void launchNormally(ResolveInfo app) {
        try {
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.addCategory(Intent.CATEGORY_LAUNCHER);
            intent.setClassName(app.activityInfo.packageName, app.activityInfo.name);
            ActivityOptions options = ActivityOptions.makeBasic();
            if (Build.VERSION.SDK_INT >= 26 && currentDisplayId() != Display.DEFAULT_DISPLAY)
                options.setLaunchDisplayId(currentDisplayId());
            startActivity(intent, options.toBundle());
        } catch (Throwable error) {
            ForkdexErrorLog.add(this, "Launch normal window " + app.activityInfo.packageName, error);
            Toast.makeText(this, "Gagal membuka aplikasi: " + error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void openFreeform(ResolveInfo app) {
        if (!windowClient.isConnected()) {
            pendingFreeformApp = app;
            windowClient.connect();
            if (windowClient.isBinding()) Toast.makeText(this, "Menghubungkan layanan native. Aplikasi akan dibuka otomatis setelah tersambung.", Toast.LENGTH_LONG).show();
            else Toast.makeText(this, windowClient.connectionStatus(), Toast.LENGTH_LONG).show();
            return;
        }
        launchFreeformNow(app);
    }

    private void launchFreeformNow(ResolveInfo app) {
        DisplayMetrics metrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(metrics);
        int margin = Math.max(24, (int) (24 * metrics.density));
        int top = Math.max(48, (int) (48 * metrics.density));
        Rect bounds = new Rect(margin, top,
                Math.max(margin + 240, metrics.widthPixels - margin),
                Math.max(top + 320, metrics.heightPixels - margin));
        String saved = getSharedPreferences("freeform_window_layouts", MODE_PRIVATE)
                .getString(app.activityInfo.packageName, null);
        if (saved != null) {
            try {
                JSONObject rect = new JSONObject(saved);
                Rect restored = new Rect(rect.optInt("left"), rect.optInt("top"),
                        rect.optInt("right"), rect.optInt("bottom"));
                if (restored.width() >= dp(240) && restored.height() >= dp(180))
                    bounds = restored;
            } catch (Exception ignored) { }
        }
        int displayId = Build.VERSION.SDK_INT >= 30 && getDisplay() != null
                ? getDisplay().getDisplayId() : 0;
        String pkg = app.activityInfo.packageName;
        String cls = app.activityInfo.name;
        // Capture immutable launch parameters before crossing the worker-thread boundary.
        final Rect launchBounds = new Rect(bounds);
        final int targetDisplayId = displayId;
        new Thread(() -> {
            String result = windowClient.launch(pkg, cls, targetDisplayId, launchBounds);
            int percent = profiles.getInt(pkg, 100);
            if (result != null && result.startsWith("FREEFORM_LAUNCH_ACCEPTED:") && percent != 100) {
                try {
                    Thread.sleep(900L);
                    int density = Math.max(72, Math.round(getResources().getDisplayMetrics().densityDpi * percent / 100f));
                    String dpiResult = windowClient.setPackageDensity(targetDisplayId, pkg, density);
                    result = result + " • " + dpiResult;
                } catch (Exception error) {
                    result = result + " • DPI: " + error.getMessage();
                }
            }
            final String message = result;
            runOnUiThread(() -> Toast.makeText(this, message, Toast.LENGTH_LONG).show());
        }, "Forkdex-Freeform-Launch").start();
    }



    private String displayName(int displayId) {
        try {
            DisplayManager manager = (DisplayManager) getSystemService(DISPLAY_SERVICE);
            Display display = manager == null ? null : manager.getDisplay(displayId);
            if (display != null && display.getName() != null && !display.getName().trim().isEmpty())
                return display.getName();
        } catch (Exception ignored) { }
        return "Display " + displayId;
    }

    private int currentDisplayId() {
        return Build.VERSION.SDK_INT >= 30 && getDisplay() != null
                ? getDisplay().getDisplayId() : 0;
    }


    private void showDesktopModeSetup() {
        new AlertDialog.Builder(this)
                .setTitle("Forkdex Desktop Mode")
                .setMessage("Forkdex memakai mekanisme Android SECONDARY_HOME seperti Taskbar.\n\n1. Aktifkan dukungan freeform dan desktop display.\n2. Jadikan Forkdex sebagai aplikasi Home default.\n3. Sambungkan monitor yang mendukung extended display.\n\nAndroid akan memanggil SecondaryHomeActivity secara otomatis jika ROM mengizinkan system decorations pada layar eksternal. Forkdex tidak membuat Activity desktop kedua secara manual.")
                .setPositiveButton("Aktifkan pengaturan", (dialog, which) -> {
                    if (!windowClient.isConnected()) {
                        pendingServiceAction = () -> enableDesktopModeSettings();
                        windowClient.connect();
                        Toast.makeText(this, "Menghubungkan layanan Shizuku...", Toast.LENGTH_LONG).show();
                    } else enableDesktopModeSettings();
                })
                .setNeutralButton("Pilih Forkdex sebagai Home", (dialog, which) -> {
                    try {
                        startActivity(new Intent(android.provider.Settings.ACTION_HOME_SETTINGS));
                    } catch (Exception error) {
                        Toast.makeText(this, "Buka Setelan > Aplikasi default > Aplikasi Home", Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("Tutup", null)
                .show();
    }

    private void enableDesktopModeSettings() {
        new Thread(() -> {
            try {
                String result = windowClient.enableDesktopMode();
                runOnUiThread(() -> {
                    Toast.makeText(this, result.contains("DESKTOP_MODE_SETTINGS_ENABLED")
                            ? "Desktop Mode diaktifkan. Pilih Forkdex sebagai Home default."
                            : result, Toast.LENGTH_LONG).show();
                    ForkdexErrorLog.add(this, "Desktop mode setup", result);
                });
            } catch (Exception error) {
                runOnUiThread(() -> Toast.makeText(this,
                        "Gagal mengaktifkan Desktop Mode: " + error.getMessage(),
                        Toast.LENGTH_LONG).show());
            }
        }, "Forkdex-Desktop-Setup").start();
    }

    private void showWindows() {
        if (!windowClient.isConnected()) {
            windowClient.connect();
            Toast.makeText(this, "Menghubungkan UserService. " + windowClient.connectionStatus(), Toast.LENGTH_LONG).show();
            return;
        }
        final int displayId = currentDisplayId();
        new Thread(() -> {
            try {
                String json = windowClient.listTasks(displayId);
                runOnUiThread(() -> showWindowList(json, displayId));
            } catch (Exception error) {
                runOnUiThread(() -> Toast.makeText(this,
                        "Gagal membaca task: " + error.getMessage(), Toast.LENGTH_LONG).show());
            }
        }, "Forkdex-Task-Inventory").start();
    }

    private void showWindowList(String raw, int displayId) {
        try {
            JSONArray tasks = new JSONArray(raw);
            if (tasks.length() == 0) {
                new AlertDialog.Builder(this).setTitle("Jendela berjalan")
                        .setMessage("Belum ada task aplikasi yang terdeteksi.")
                        .setPositiveButton("Tutup", null).show();
                return;
            }
            JSONObject[] entries = new JSONObject[tasks.length()];
            String[] labels = new String[tasks.length()];
            for (int i = 0; i < tasks.length(); i++) {
                entries[i] = tasks.getJSONObject(i);
                String pkg = entries[i].optString("packageName", "Aplikasi");
                int mode = entries[i].optInt("windowingMode", 1);
                labels[i] = pkg + "  •  #" + entries[i].optInt("taskId")
                        + (mode == 5 ? "  [Freeform]" : "  [Fullscreen]");
            }
            new AlertDialog.Builder(this).setTitle("Jendela berjalan")
                    .setItems(labels, (dialog, which) -> showWindowActions(entries[which], displayId))
                    .setNegativeButton("Tutup", null).show();
        } catch (Exception error) {
            Toast.makeText(this, "Data task tidak valid: " + error.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void showWindowActions(JSONObject task, int displayId) {
        int taskId = task.optInt("taskId", -1);
        int mode = task.optInt("windowingMode", 1);
        Rect bounds = new Rect();
        JSONObject saved = task.optJSONObject("bounds");
        if (saved != null) bounds.set(saved.optInt("left"), saved.optInt("top"),
                saved.optInt("right"), saved.optInt("bottom"));
        final int id = taskId;
        final Rect previousBounds = new Rect(bounds);
        String[] actions = {"Bawa ke depan", "Minimalkan", "Maksimalkan",
                "Kembalikan ke Freeform", "Tutup aplikasi"};
        new AlertDialog.Builder(this).setTitle(task.optString("packageName"))
                .setItems(actions, (dialog, which) -> {
                    new Thread(() -> {
                        String result;
                        try {
                            if (which == 0) result = windowClient.focusTask(displayId, id);
                            else if (which == 1) result = windowClient.minimizeTask(displayId, id);
                            else if (which == 2) result = windowClient.setTaskMode(
                                    displayId, id, 1, new Rect());
                            else if (which == 3) {
                                if (previousBounds.isEmpty()) {
                                    DisplayMetrics dm = new DisplayMetrics();
                                    getWindowManager().getDefaultDisplay().getRealMetrics(dm);
                                    int margin = Math.max(24, (int)(24 * dm.density));
                                    previousBounds.set(margin, Math.max(48, margin * 2),
                                            Math.max(margin + 240, dm.widthPixels - margin),
                                            Math.max(360, dm.heightPixels - margin));
                                }
                                result = windowClient.setTaskMode(displayId, id, 5, previousBounds);
                            } else result = windowClient.closeTask(id);
                        } catch (Exception error) {
                            result = "Window action gagal: " + error.getMessage();
                        }
                        final String message = result;
                        runOnUiThread(() -> Toast.makeText(this, message, Toast.LENGTH_LONG).show());
                    }, "Forkdex-Window-Action").start();
                }).setNegativeButton("Batal", null).show();
    }

    private void showErrorLogs() {
        String log = ForkdexErrorLog.read(this);
        if (log == null || log.trim().isEmpty()) log = "Belum ada log error. Tekan Sambungkan layanan, lalu ulangi masalah agar diagnostik tercatat.";
        final String currentLog = log;
        new AlertDialog.Builder(this)
                .setTitle("Forkdex • Log Error")
                .setMessage(currentLog)
                .setPositiveButton("Salin Log", (dialog, which) -> {
                    ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    clipboard.setPrimaryClip(ClipData.newPlainText("Forkdex error log", currentLog));
                    Toast.makeText(this, "Log berhasil disalin.", Toast.LENGTH_SHORT).show();
                })
                .setNeutralButton("Hapus Log", (dialog, which) -> {
                    new AlertDialog.Builder(this)
                            .setTitle("Hapus semua log?")
                            .setMessage("Log diagnostik lokal Forkdex akan dihapus.")
                            .setPositiveButton("Hapus", (d, w) -> {
                                ForkdexErrorLog.clear(this);
                                Toast.makeText(this, "Log dihapus.", Toast.LENGTH_SHORT).show();
                            })
                            .setNegativeButton("Batal", null)
                            .show();
                })
                .setNegativeButton("Tutup", null)
                .show();
    }

    private void showShizukuDialog() {
        new AlertDialog.Builder(this)
            .setTitle("Forkdex • Shizuku")
            .setMessage(shizuku.status() + "\n\n" + windowClient.capabilities())
            .setPositiveButton(shizuku.hasPermission() ? "Sambungkan layanan" : "Berikan izin", (d, w) -> {
                try {
                    if (!shizuku.hasPermission()) shizuku.requestPermission();
                    else windowClient.connect();
                } catch (RuntimeException error) {
                    Toast.makeText(this, "Shizuku: " + error.getMessage(), Toast.LENGTH_LONG).show();
                }
            })
            .setNeutralButton("Buka Manager", (d, w) -> shizuku.openManager())
            .setNegativeButton("Tutup", null)
            .show();
    }

    private void showShizukuStatus() {
        if (shizuku.hasPermission()) windowClient.connect();
        Toast.makeText(this, shizuku.status() + " • " + windowClient.connectionStatus(), Toast.LENGTH_LONG).show();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // Android routes the system HOME action back to this singleTask launcher.
        // Keep the desktop visible rather than leaving the Start drawer over it.
        if (desktopRoot == null) {
            render();
        } else {
            setDrawerVisible(false);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        taskbarHandler.removeCallbacks(taskbarTicker);
        taskbarHandler.post(taskbarTicker);
        if (shizuku != null) {
            shizuku.setOnChanged(() -> runOnUiThread(this::showShizukuStatus));
            if (shizuku.hasPermission()) windowClient.connect();
        }
    }

    @Override protected void onPause() {
        super.onPause();
        taskbarHandler.removeCallbacks(taskbarTicker);
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (displayManager != null) {
            try { displayManager.unregisterDisplayListener(displayListener); }
            catch (Exception ignored) { }
        }
    }

    private void showProfiles() {
        String[] labels = new String[apps.size()];
        for (int i = 0; i < apps.size(); i++) labels[i] = apps.get(i).loadLabel(getPackageManager()).toString();
        new AlertDialog.Builder(this).setTitle("Pilih aplikasi")
            .setItems(labels, (d, which) -> showProfile(apps.get(which)))
            .setNegativeButton("Tutup", null).show();
    }

    private void showProfile(ResolveInfo app) {
        String pkg = app.activityInfo.packageName;
        int current = profiles.getInt(pkg, 100);
        android.widget.SeekBar seek = new android.widget.SeekBar(this);
        seek.setMax(150);
        seek.setProgress(current - 50);
        TextView value = text(current + "%", 22, true);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(22, 10, 22, 4);
        value.setGravity(Gravity.CENTER);
        box.addView(value);
        box.addView(seek);
        seek.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(android.widget.SeekBar b, int p, boolean u) { value.setText((p + 50) + "%"); }
            public void onStartTrackingTouch(android.widget.SeekBar b) {}
            public void onStopTrackingTouch(android.widget.SeekBar b) {}
        });
        new AlertDialog.Builder(this).setTitle(app.loadLabel(getPackageManager()))
            .setView(box).setPositiveButton("Simpan profil", (d, w) -> {
                int percent = seek.getProgress() + 50;
                profiles.edit().putInt(pkg, percent).apply();
                if (percent == 100) {
                    applyProfileDensity(pkg, 0);
                } else {
                    int density = Math.max(72, Math.round(getResources().getDisplayMetrics().densityDpi * percent / 100f));
                    applyProfileDensity(pkg, density);
                }
            }).setNeutralButton("System Density", (d, w) -> profiles.edit().remove(pkg).apply())
            .setNegativeButton("Batal", null).show();
    }

    private void applyProfileDensity(String packageName, int densityDpi) {
        if (!windowClient.isConnected()) {
            Toast.makeText(this, "Profil tersimpan. Sambungkan Shizuku untuk menerapkan DPI.", Toast.LENGTH_LONG).show();
            return;
        }
        int displayId = currentDisplayId();
        new Thread(() -> {
            String result;
            try {
                result = windowClient.setPackageDensity(displayId, packageName, densityDpi);
            } catch (Exception error) {
                result = "Per-App DPI gagal: " + error.getMessage();
            }
            final String message = result;
            runOnUiThread(() -> {
                if (message.startsWith("NO_MATCHING_TASK:")) {
                    Toast.makeText(this, "Profil tersimpan. DPI akan diterapkan otomatis saat aplikasi dibuka lewat Forkdex.", Toast.LENGTH_LONG).show();
                } else if (message.startsWith("TASK_DENSITY_APPLIED:")) {
                    Toast.makeText(this, "Profil DPI berhasil diterapkan ke task aktif.", Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                }
            });
        }, "Forkdex-Per-App-DPI").start();
    }

    private TextView text(String value, int size, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value); t.setTextSize(size); t.setTextColor(Color.WHITE);
        if (bold) t.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        return t;
    }
}
