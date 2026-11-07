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
    private TextView taskbarDate;
    private EditText drawerSearch;
    private boolean taskRefreshInFlight;
    private boolean activityResumed;
    private long lastTaskRefreshAt;
    private String lastTaskbarSignature;
    private String lastTaskbarStatus;
    private DisplayManager displayManager;
    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
        @Override public void onDisplayAdded(int displayId) { }
        @Override public void onDisplayChanged(int displayId) { }
        @Override public void onDisplayRemoved(int displayId) {
            if (currentDisplayId() == displayId) {
                Toast.makeText(MainActivity.this, "Layar eksternal terputus.", Toast.LENGTH_LONG).show();
                finish();
            }
        }
    };
    private final Runnable taskbarTicker = new Runnable() {
        @Override public void run() {
            java.util.Date now = new java.util.Date();
            java.util.Locale locale = java.util.Locale.getDefault();
            if (taskbarClock != null) taskbarClock.setText(new java.text.SimpleDateFormat("HH:mm", locale).format(now));
            if (taskbarDate != null) taskbarDate.setText(new java.text.SimpleDateFormat("EEE, d MMM", locale).format(now));
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
            refreshTaskbarTasks();
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
        boolean descending = getSharedPreferences("launcher_preferences", MODE_PRIVATE)
                .getBoolean("sort_descending", false);
        Collections.sort(apps, (left, right) -> {
            int order = left.loadLabel(getPackageManager()).toString().compareToIgnoreCase(
                    right.loadLabel(getPackageManager()).toString());
            return descending ? -order : order;
        });
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
            Collections.sort(clonedApps, (left, right) -> {
                String a = left.getLabel() == null ? "" : left.getLabel().toString();
                String b = right.getLabel() == null ? "" : right.getLabel().toString();
                int order = a.compareToIgnoreCase(b);
                return getSharedPreferences("launcher_preferences", MODE_PRIVATE)
                        .getBoolean("sort_descending", false) ? -order : order;
            });
        } catch (Throwable error) {
            ForkdexErrorLog.add(this, "Load cloned apps/profiles", error);
        }
    }

    private FrameLayout desktopRoot;
    private LinearLayout appDrawer;
    private LinearLayout drawerApps;
    private android.widget.HorizontalScrollView pinnedScroll;
    private TextView sortButton;
    private TextView viewButton;
    private boolean drawerVisible = false;
    private float drawerGestureDownX;
    private float drawerGestureDownY;
    private boolean drawerGestureEligible;

    private void render() {
        // Ask Android to draw the current system wallpaper behind the launcher window.
        // This also supports live wallpapers and avoids relying on getDrawable(), which
        // may return null or a stale image on newer Android releases.
        try {
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER);
            getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            getWindow().setDimAmount(0.04f);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                android.view.WindowManager.LayoutParams glassParams = getWindow().getAttributes();
                glassParams.flags |= android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND;
                glassParams.setBlurBehindRadius(dp(8));
                getWindow().setAttributes(glassParams);
            }
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
            displayLabel.setBackground(glassControl(0x8A1B2638, 16));
            displayLabel.setPadding(dp(12), dp(7), dp(12), dp(7));
            FrameLayout.LayoutParams labelParams = new FrameLayout.LayoutParams(-2, -2,
                    Gravity.TOP | Gravity.RIGHT);
            labelParams.setMargins(0, dp(12), dp(12), 0);
            desktopRoot.addView(displayLabel, labelParams);
        }

        renderWindowsPhoneHome(desktopRoot);
    }

    private LinearLayout pinnedApps;


    /** Windows Phone-inspired Start Screen. The recent-task dock is intentionally absent. */
    private void renderWindowsPhoneHome(FrameLayout root) {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(18), dp(18), dp(18), dp(12));
        page.setOnApplyWindowInsetsListener((view, insets) -> {
            int bottomInset;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                bottomInset = insets.getInsets(android.view.WindowInsets.Type.navigationBars()).bottom;
            } else {
                bottomInset = insets.getSystemWindowInsetBottom();
            }
            view.setPadding(dp(18), dp(18), dp(18), dp(12) + bottomInset);
            return insets;
        });

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView eyebrow = text("PERSONAL ANDROID DESKTOP", 9, true);
        eyebrow.setTextColor(0xFF8CCBFF);
        eyebrow.setLetterSpacing(0.12f);
        TextView brand = text("FORKDEX", 27, true);
        brand.setTextColor(0xFFFFFFFF);
        TextView subtitle = text("Ruang kerja Android kamu", 11, false);
        subtitle.setTextColor(0xFFD9E5F4);
        titles.addView(eyebrow);
        titles.addView(brand);
        titles.addView(subtitle);
        header.addView(titles, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView control = text("⚙", 23, true);
        control.setTextColor(Color.WHITE);
        control.setGravity(Gravity.CENTER);
        control.setBackground(glassControl(0x55202A38, 18));
        control.setContentDescription("Pengaturan Forkdex");
        control.setOnClickListener(v -> showLauncherOptions());
        header.addView(control, new LinearLayout.LayoutParams(dp(48), dp(48)));

        TextView quick = text("⋯", 24, true);
        quick.setTextColor(Color.WHITE);
        quick.setGravity(Gravity.CENTER);
        quick.setBackground(glassControl(0x55202A38, 18));
        quick.setContentDescription("Kontrol Forkdex");
        quick.setOnClickListener(v -> showTaskbarPanel());
        LinearLayout.LayoutParams quickLp = new LinearLayout.LayoutParams(dp(48), dp(48));
        quickLp.setMargins(dp(8), 0, 0, 0);
        header.addView(quick, quickLp);
        page.addView(header);

        EditText search = new EditText(this);
        drawerSearch = search;
        search.setSingleLine(true);
        search.setTextSize(15);
        search.setHint("Cari aplikasi");
        search.setHintTextColor(0xFFCBD6E5);
        search.setTextColor(Color.WHITE);
        search.setPadding(dp(14), 0, dp(14), 0);
        search.setBackground(glassPanel(0x55202A38, 22));
        search.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(-1, dp(48));
        searchLp.setMargins(0, dp(18), 0, dp(16));
        page.addView(search, searchLp);

        TextView section = text("APLIKASI", 12, true);
        section.setTextColor(0xFFEAF0F7);
        LinearLayout.LayoutParams sectionLp = new LinearLayout.LayoutParams(-1, dp(28));
        page.addView(section, sectionLp);

        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.setFillViewport(false);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout tileRows = new LinearLayout(this);
        tileRows.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(tileRows, new android.widget.ScrollView.LayoutParams(-1, -2));
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        Runnable populate = () -> {
            tileRows.removeAllViews();
            String query = search.getText().toString().trim().toLowerCase(
                    java.util.Locale.getDefault());
            List<Object> entries = new ArrayList<>();
            for (ResolveInfo app : apps) {
                if (!isHiddenApp(app.activityInfo.packageName)
                        && app.loadLabel(getPackageManager()).toString().toLowerCase(
                        java.util.Locale.getDefault()).contains(query)) entries.add(app);
            }
            for (LauncherActivityInfo clone : clonedApps) {
                String label = clone.getLabel() == null ? "" : clone.getLabel().toString();
                if (!isHiddenApp(clone.getComponentName().getPackageName())
                        && label.toLowerCase(java.util.Locale.getDefault()).contains(query))
                    entries.add(clone);
            }
            int index = 0;
            while (index < entries.size()) {
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, dp(142));
                rowLp.setMargins(0, 0, 0, dp(7));
                int rowStart = index;
                for (int column = 0; column < 2 && index < entries.size(); column++, index++) {
                    Object entry = entries.get(index);
                    LinearLayout tile = new LinearLayout(this);
                    tile.setOrientation(LinearLayout.VERTICAL);
                    tile.setGravity(Gravity.CENTER);
                    tile.setPadding(dp(6), dp(6), dp(6), dp(6));
                    GradientDrawable tileGlass = new GradientDrawable(
                            GradientDrawable.Orientation.TOP_BOTTOM,
                            new int[] {0x66506A89, 0x55404D62, 0x44303B4E});
                    tileGlass.setCornerRadius(dp(22));
                    tileGlass.setStroke(dp(1), 0x668FAAC8);
                    tile.setBackground(new android.graphics.drawable.RippleDrawable(
                            android.content.res.ColorStateList.valueOf(0x44FFFFFF),
                            tileGlass, rounded(0x33FFFFFF, 22)));
                    tile.setElevation(dp(3));
                    ImageView icon = new ImageView(this);
                    if (entry instanceof ResolveInfo) {
                        ResolveInfo app = (ResolveInfo) entry;
                        icon.setImageDrawable(app.loadIcon(getPackageManager()));
                        tile.setOnClickListener(v -> launchSelectedApp(app));
                        tile.setOnLongClickListener(v -> { showAppActions(app); return true; });
                    } else {
                        LauncherActivityInfo clone = (LauncherActivityInfo) entry;
                        icon.setImageDrawable(clone.getIcon(0));
                        tile.setOnClickListener(v -> launchClonedApp(clone));
                        tile.setOnLongClickListener(v -> { showClonedAppActions(clone); return true; });
                    }
                    icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    icon.setAdjustViewBounds(true);
                    LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(88), dp(88));
                    iconLp.gravity = Gravity.CENTER_HORIZONTAL;
                    tile.addView(icon, iconLp);
                    String labelText = entry instanceof ResolveInfo
                            ? ((ResolveInfo) entry).loadLabel(getPackageManager()).toString()
                            : String.valueOf(((LauncherActivityInfo) entry).getLabel());
                    TextView label = text(labelText, 13, true);
                    label.setTextColor(Color.WHITE);
                    label.setMaxLines(1);
                    label.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    label.setGravity(Gravity.CENTER);
                    label.setShadowLayer(2f, 0f, 1f, 0x66000000);
                    LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(-1, -2);
                    labelLp.topMargin = dp(5);
                    tile.addView(label, labelLp);
                    addPressMotion(tile);
                    LinearLayout.LayoutParams tileLp = new LinearLayout.LayoutParams(0, -1, 1f);
                    tileLp.setMargins(dp(4), dp(3), dp(4), dp(3));
                    row.addView(tile, tileLp);
                }
                if (index == rowStart + 1) {
                    View spacer = new View(this);
                    row.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));
                }
                tileRows.addView(row, rowLp);
            }
            if (entries.isEmpty()) {
                TextView empty = text("Tidak ada aplikasi yang cocok.", 14, false);
                empty.setTextColor(Color.WHITE);
                empty.setPadding(dp(12), dp(20), dp(12), dp(20));
                tileRows.addView(empty);
            }
            section.setText("APLIKASI  •  " + entries.size());
        };
        search.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) { populate.run(); }
            public void afterTextChanged(android.text.Editable s) { }
        });
        search.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                android.view.inputmethod.InputMethodManager keyboard =
                        (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (keyboard != null) keyboard.hideSoftInputFromWindow(search.getWindowToken(), 0);
                search.clearFocus();
                return true;
            }
            return false;
        });
        populate.run();

        FrameLayout.LayoutParams pageLp = new FrameLayout.LayoutParams(-1, -1);
        root.addView(page, pageLp);
    }

    private void createAppDrawer() {
        appDrawer = new LinearLayout(this);
        appDrawer.setOrientation(LinearLayout.VERTICAL);
        appDrawer.setPadding(dp(18), dp(14), dp(18), dp(12));
        appDrawer.setBackground(glassPanel(0xD51B293D, 24));
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
        drawerSearch = search;
        search.setSingleLine(true);
        search.setTextSize(14);
        search.setHint("Cari aplikasi");
        search.setHintTextColor(0xFF9EABC0);
        search.setTextColor(0xFFF4F7FC);
        search.setPadding(dp(15), 0, dp(15), 0);
        search.setBackground(glassControl(0xA6334156, 15));
        search.setCompoundDrawablePadding(dp(8));
        search.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        search.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        LinearLayout searchRow = new LinearLayout(this);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView clearSearch = text("×", 22, false);
        clearSearch.setGravity(Gravity.CENTER);
        clearSearch.setContentDescription("Hapus pencarian");
        clearSearch.setVisibility(View.GONE);
        clearSearch.setOnClickListener(v -> {
            search.setText("");
            search.requestFocus();
        });
        searchRow.addView(search, new LinearLayout.LayoutParams(0, dp(48), 1f));
        searchRow.addView(clearSearch, new LinearLayout.LayoutParams(dp(42), dp(48)));
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(-1, dp(48));
        searchParams.setMargins(0, dp(12), 0, dp(12));
        appDrawer.addView(searchRow, searchParams);

        LinearLayout pinHeader = new LinearLayout(this);
        pinHeader.setGravity(Gravity.CENTER_VERTICAL);
        TextView pinTitle = text("Pinned", 15, true);
        pinTitle.setTextColor(0xFFF1F4FA);
        pinHeader.addView(pinTitle, new LinearLayout.LayoutParams(0, dp(30), 1f));
        TextView editPins = text("Kelola", 12, true);
        editPins.setTextColor(0xFF9FC5FF);
        editPins.setGravity(Gravity.CENTER);
        editPins.setOnClickListener(v -> showPinnedAppsManager());
        pinHeader.addView(editPins, new LinearLayout.LayoutParams(-2, dp(30)));
        appDrawer.addView(pinHeader);

        pinnedScroll = new android.widget.HorizontalScrollView(this);
        pinnedScroll.setHorizontalScrollBarEnabled(false);
        pinnedScroll.setFillViewport(true);
        pinnedApps = new LinearLayout(this);
        pinnedApps.setGravity(Gravity.CENTER_VERTICAL);
        pinnedScroll.addView(pinnedApps, new android.widget.HorizontalScrollView.LayoutParams(-2, -1));
        appDrawer.addView(pinnedScroll, new LinearLayout.LayoutParams(-1, dp(38)));
        populatePinnedApps();

        LinearLayout section = new LinearLayout(this);
        section.setGravity(Gravity.CENTER_VERTICAL);
        TextView allApps = text("All apps", 16, true);
        allApps.setTextColor(0xFFF1F4FA);
        section.addView(allApps, new LinearLayout.LayoutParams(0, dp(34), 1f));
        boolean descendingSort = getSharedPreferences("launcher_preferences", MODE_PRIVATE)
                .getBoolean("sort_descending", false);
        sortButton = text(descendingSort ? "Z–A  ▾" : "A–Z  ▾", 12, true);
        sortButton.setTextColor(0xFFE6EDF8);
        sortButton.setGravity(Gravity.CENTER);
        sortButton.setPadding(dp(12), 0, dp(12), 0);
        sortButton.setBackground(glassControl(0x703E4B61, 10));
        sortButton.setContentDescription("Urutkan daftar aplikasi");
        sortButton.setOnClickListener(v -> showSortOptions());
        section.addView(sortButton, new LinearLayout.LayoutParams(-2, dp(32)));
        viewButton = text(isDrawerListView() ? "☷" : "▦", 19, true);
        viewButton.setTextColor(0xFFE6EDF8);
        viewButton.setGravity(Gravity.CENTER);
        viewButton.setBackground(glassControl(0x703E4B61, 10));
        viewButton.setContentDescription("Ganti tampilan Grid atau List");
        LinearLayout.LayoutParams viewParams = new LinearLayout.LayoutParams(dp(38), dp(32));
        viewParams.setMargins(dp(6), 0, 0, 0);
        section.addView(viewButton, viewParams);
        viewButton.setOnClickListener(v -> {
            boolean listMode = !isDrawerListView();
            getSharedPreferences("launcher_preferences", MODE_PRIVATE).edit()
                    .putBoolean("drawer_list_view", listMode).apply();
            viewButton.setText(listMode ? "☷" : "▦");
            refreshDrawerAppsForCurrentQuery();
        });
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
        int bottomSafe = Math.min(dp(84), screenH / 6);
        int panelH = Math.min(dp(680), Math.max(dp(300), screenH - bottomSafe - dp(16)));
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                panelW, panelH, Gravity.BOTTOM | Gravity.START);
        params.setMargins(dp(12), 0, 0, bottomSafe);
        desktopRoot.addView(appDrawer, params);

        search.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence value, int start, int count, int after) { }
            public void onTextChanged(CharSequence value, int start, int before, int count) {
                java.util.Locale locale = java.util.Locale.getDefault();
                String q = value.toString().trim().toLowerCase(locale);
                clearSearch.setVisibility(q.isEmpty() ? View.GONE : View.VISIBLE);
                List<ResolveInfo> filtered = new ArrayList<>();
                for (ResolveInfo app : apps)
                    if (app.loadLabel(getPackageManager()).toString().toLowerCase(locale).contains(q)
                            && !isHiddenApp(app.activityInfo.packageName)) filtered.add(app);
                List<LauncherActivityInfo> filteredClones = new ArrayList<>();
                for (LauncherActivityInfo clone : clonedApps) {
                    String label = clone.getLabel() == null ? "" : clone.getLabel().toString();
                    String clonePackage = clone.getComponentName().getPackageName();
                    if (label.toLowerCase(locale).contains(q) && !isHiddenApp(clonePackage)) {
                        filteredClones.add(clone);
                    }
                }
                populateDrawerApps(filtered, filteredClones);
            }
            public void afterTextChanged(android.text.Editable value) { }
        });
        search.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                ((android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                        .hideSoftInputFromWindow(search.getWindowToken(), 0);
                search.clearFocus();
                return true;
            }
            return false;
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
            item.setBackground(tileSurface(0x0EFFFFFF, 12));
            item.setContentDescription(label.getText());
            addPressMotion(item);
            item.setOnClickListener(v -> { setDrawerVisible(false); launchSelectedApp(app); });
            item.setOnLongClickListener(v -> { showAppActions(app); return true; });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(70), -1);
            lp.setMargins(0, 0, dp(5), 0);
            pinnedApps.addView(item, lp);
            count++;
        }
        java.util.Set<String> clonePins = getSharedPreferences(
                "launcher_preferences", MODE_PRIVATE).getStringSet(
                "pinned_clones", Collections.emptySet());
        for (LauncherActivityInfo clone : clonedApps) {
            String pkg = clone.getComponentName().getPackageName();
            if (!clonePins.contains(clonePinKey(clone)) || isHiddenApp(pkg)) continue;
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER);
            ImageView icon = new ImageView(this);
            icon.setImageDrawable(clone.getIcon(0));
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            item.addView(icon, new LinearLayout.LayoutParams(dp(36), dp(36)));
            String labelText = clone.getLabel() == null ? pkg : clone.getLabel().toString();
            TextView label = text(labelText, 10, false);
            label.setTextColor(0xFFBBD7FF);
            label.setGravity(Gravity.CENTER);
            label.setMaxLines(1);
            item.addView(label, new LinearLayout.LayoutParams(dp(66), dp(20)));
            item.setContentDescription(labelText + ", profil lain");
            item.setBackground(tileSurface(0x163E6BA5, 12));
            addPressMotion(item);
            item.setOnClickListener(v -> { setDrawerVisible(false); launchClonedApp(clone); });
            item.setOnLongClickListener(v -> { showClonedAppActions(clone); return true; });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(70), -1);
            lp.setMargins(0, 0, dp(5), 0);
            pinnedApps.addView(item, lp);
            count++;
        }
        if (count == 0) {
            TextView empty = text("Sematkan favorit dari menu tekan-lama.", 11, false);
            empty.setTextColor(0xFF9EABC0);
            empty.setGravity(Gravity.CENTER_VERTICAL);
            empty.setSingleLine(true);
            empty.setEllipsize(android.text.TextUtils.TruncateAt.END);
            pinnedApps.addView(empty, new LinearLayout.LayoutParams(-1, -1));
        }
        if (pinnedScroll != null && pinnedScroll.getLayoutParams() != null) {
            android.view.ViewGroup.LayoutParams rowParams = pinnedScroll.getLayoutParams();
            int rowHeight = dp(count == 0 ? 34 : 70);
            if (rowParams.height != rowHeight) {
                rowParams.height = rowHeight;
                pinnedScroll.setLayoutParams(rowParams);
            }
        }
    }

    private void showPinnedAppsManager() {
        android.content.SharedPreferences prefs = getSharedPreferences(
                "launcher_preferences", MODE_PRIVATE);
        java.util.Set<String> appPins = new java.util.HashSet<>(prefs.getStringSet(
                "pinned_apps", Collections.emptySet()));
        java.util.Set<String> clonePins = new java.util.HashSet<>(prefs.getStringSet(
                "pinned_clones", Collections.emptySet()));
        List<String> names = new ArrayList<>();
        List<Runnable> removeActions = new ArrayList<>();
        for (ResolveInfo app : apps) {
            String pkg = app.activityInfo.packageName;
            if (!appPins.contains(pkg)) continue;
            names.add(app.loadLabel(getPackageManager()).toString());
            removeActions.add(() -> appPins.remove(pkg));
        }
        for (LauncherActivityInfo clone : clonedApps) {
            String key = clonePinKey(clone);
            if (!clonePins.contains(key)) continue;
            String label = clone.getLabel() == null
                    ? clone.getComponentName().getPackageName() : clone.getLabel().toString();
            names.add(label + " · Profil lain");
            removeActions.add(() -> clonePins.remove(key));
        }
        if (names.isEmpty()) {
            new AlertDialog.Builder(this).setTitle("Aplikasi yang disematkan")
                    .setMessage("Belum ada aplikasi di Pinned. Tekan lama ikon aplikasi lalu pilih Sematkan.")
                    .setPositiveButton("Mengerti", null).show();
            return;
        }
        new AlertDialog.Builder(this).setTitle("Kelola Pinned · pilih untuk melepas")
                .setItems(names.toArray(new String[0]), (dialog, which) -> {
                    removeActions.get(which).run();
                    prefs.edit().putStringSet("pinned_apps", appPins)
                            .putStringSet("pinned_clones", clonePins).apply();
                    populatePinnedApps();
                    Toast.makeText(this, "Sematan dilepas.", Toast.LENGTH_SHORT).show();
                }).setNegativeButton("Tutup", null).show();
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
            else showForkdexSettings();
        }).show();
    }

    private void showSortOptions() {
        String[] options = {"Nama (A–Z)", "Nama (Z–A)"};
        int selected = getSharedPreferences("launcher_preferences", MODE_PRIVATE)
                .getBoolean("sort_descending", false) ? 1 : 0;
        new AlertDialog.Builder(this).setTitle("Urutkan aplikasi")
                .setSingleChoiceItems(options, selected, (d, which) -> {
            getSharedPreferences("launcher_preferences", MODE_PRIVATE).edit()
                    .putBoolean("sort_descending", which == 1).apply();
            if (sortButton != null) sortButton.setText(which == 1 ? "Z–A  ▾" : "A–Z  ▾");
            Collections.sort(apps, (a, b) -> {
                int result = a.loadLabel(getPackageManager()).toString().compareToIgnoreCase(
                        b.loadLabel(getPackageManager()).toString());
                return which == 1 ? -result : result;
            });
            Collections.sort(clonedApps, (a, b) -> {
                String left = a.getLabel() == null ? "" : a.getLabel().toString();
                String right = b.getLabel() == null ? "" : b.getLabel().toString();
                int result = left.compareToIgnoreCase(right);
                return which == 1 ? -result : result;
            });
            populateDrawerApps(apps, clonedApps);
            populatePinnedApps();
            d.dismiss();
        }).setNegativeButton("Tutup", null).show();
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

    private static final int REQUEST_UNINSTALL_APP = 4107;

    private void requestUninstall(String pkg) {
        if (pkg == null || pkg.trim().isEmpty()) {
            Toast.makeText(this, "Paket aplikasi tidak valid.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (getPackageName().equals(pkg)) {
            Toast.makeText(this, "Forkdex tidak dapat menghapus dirinya sendiri dari menu ini.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        try {
            Intent uninstall = new Intent(Intent.ACTION_UNINSTALL_PACKAGE,
                    android.net.Uri.parse("package:" + pkg));
            uninstall.putExtra(Intent.EXTRA_RETURN_RESULT, true);
            if (uninstall.resolveActivity(getPackageManager()) == null) {
                // Compatibility fallback for Android builds exposing only ACTION_DELETE.
                uninstall = new Intent(Intent.ACTION_DELETE,
                        android.net.Uri.parse("package:" + pkg));
            }
            if (uninstall.resolveActivity(getPackageManager()) == null) {
                Toast.makeText(this, "Pemasang Android tidak menyediakan opsi uninstall untuk aplikasi ini.",
                        Toast.LENGTH_LONG).show();
                return;
            }
            startActivityForResult(uninstall, REQUEST_UNINSTALL_APP);
        } catch (SecurityException error) {
            ForkdexErrorLog.add(this, "Request uninstall " + pkg, error);
            Toast.makeText(this, "Android menolak permintaan uninstall. Periksa izin REQUEST_DELETE_PACKAGES.",
                    Toast.LENGTH_LONG).show();
        } catch (Exception error) {
            ForkdexErrorLog.add(this, "Request uninstall " + pkg, error);
            Toast.makeText(this, "Gagal membuka konfirmasi uninstall: " + error.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_UNINSTALL_APP) {
            // Refresh launcher entries after returning from the system package installer.
            getWindow().getDecorView().postDelayed(() -> {
                loadApps();
                populateDrawerApps(apps, clonedApps);
                populatePinnedApps();
            }, 700L);
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
        String[] actions = {"Pengaturan Android", "Setelan keamanan", "Tutup Forkdex"};
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

    private void showForkdexSettings() {
        String[] options = {"Mode jendela dan Freeform", "Per-App DPI",
                "Shizuku dan layanan", "Aplikasi tersembunyi", "Quick settings"};
        new AlertDialog.Builder(this).setTitle("Pengaturan Forkdex")
                .setItems(options, (dialog, which) -> {
                    switch (which) {
                        case 0 -> toggleWindowMode();
                        case 1 -> showProfiles();
                        case 2 -> showShizukuDialog();
                        case 3 -> showHiddenApps();
                        case 4 -> showTaskbarPanel();
                    }
                }).setNegativeButton("Tutup", null).show();
    }

    private void addDrawerTool(LinearLayout row, String glyph, String label,
                               View.OnClickListener listener) {
        TextView button = text(glyph, 18, true);
        button.setGravity(Gravity.CENTER);
        button.setTextColor(Color.WHITE);
        button.setContentDescription(label);
        button.setBackground(glassControl(0x65303B50, 12));
        addPressMotion(button);
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(38), 1f);
        p.setMargins(dp(2), 0, dp(2), 0);
        row.addView(button, p);
    }

    private boolean isDrawerListView() {
        return getSharedPreferences("launcher_preferences", MODE_PRIVATE)
                .getBoolean("drawer_list_view", false);
    }

    private void refreshDrawerAppsForCurrentQuery() {
        String query = drawerSearch == null ? "" : drawerSearch.getText().toString().trim()
                .toLowerCase(java.util.Locale.getDefault());
        List<ResolveInfo> filtered = new ArrayList<>();
        for (ResolveInfo app : apps) {
            String label = app.loadLabel(getPackageManager()).toString()
                    .toLowerCase(java.util.Locale.getDefault());
            if (label.contains(query) && !isHiddenApp(app.activityInfo.packageName)) filtered.add(app);
        }
        List<LauncherActivityInfo> filteredClones = new ArrayList<>();
        for (LauncherActivityInfo clone : clonedApps) {
            String label = clone.getLabel() == null ? "" : clone.getLabel().toString();
            if (label.toLowerCase(java.util.Locale.getDefault()).contains(query)
                    && !isHiddenApp(clone.getComponentName().getPackageName())) {
                filteredClones.add(clone);
            }
        }
        populateDrawerApps(filtered, filteredClones);
    }

    private void populateDrawerApps(List<ResolveInfo> source, List<LauncherActivityInfo> cloneSource) {
        if (drawerApps == null) return;
        drawerApps.removeAllViews();
        List<View> normalTiles = new ArrayList<>();
        List<View> cloneTiles = new ArrayList<>();

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
            tile.addView(icon, new LinearLayout.LayoutParams(dp(48), dp(48)));
            TextView label = text(labelText, 12, false);
            label.setTextColor(0xFFE8EDF6);
            label.setGravity(Gravity.CENTER);
            label.setMaxLines(2);
            label.setIncludeFontPadding(false);
            tile.addView(label, new LinearLayout.LayoutParams(-1, dp(32)));
            tile.setContentDescription(labelText);
            tile.setBackgroundColor(Color.TRANSPARENT);
            tile.setTooltipText(labelText);
            addPressMotion(tile);
            tile.setOnClickListener(v -> { setDrawerVisible(false); launchSelectedApp(app); });
            tile.setOnLongClickListener(v -> { showAppActions(app); return true; });
            normalTiles.add(tile);
        }

        for (LauncherActivityInfo clone : cloneSource) {
            String pkg = clone.getComponentName().getPackageName();
            if (isHiddenApp(pkg)) continue;
            String name = clone.getLabel() == null ? pkg : clone.getLabel().toString();
            LinearLayout tile = new LinearLayout(this);
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setGravity(Gravity.CENTER);
            tile.setPadding(dp(3), dp(5), dp(3), dp(5));
            ImageView icon = new ImageView(this);
            icon.setImageDrawable(clone.getIcon(0));
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            tile.addView(icon, new LinearLayout.LayoutParams(dp(48), dp(48)));
            TextView label = text(name, 12, false);
            label.setTextColor(0xFFBBD7FF);
            label.setGravity(Gravity.CENTER);
            label.setMaxLines(2);
            label.setIncludeFontPadding(false);
            tile.addView(label, new LinearLayout.LayoutParams(-1, dp(32)));
            tile.setContentDescription(name + ", aplikasi clone");
            tile.setBackgroundColor(Color.TRANSPARENT);
            tile.setTooltipText(name + " · Clone");
            addPressMotion(tile);
            tile.setOnClickListener(v -> { setDrawerVisible(false); launchClonedApp(clone); });
            tile.setOnLongClickListener(v -> { showClonedAppActions(clone); return true; });
            cloneTiles.add(tile);
        }

        if (!normalTiles.isEmpty()) {
            addDrawerGroupTitle("Aplikasi utama");
            addDrawerTileGroup(normalTiles);
        }
        if (!cloneTiles.isEmpty()) {
            addDrawerGroupTitle("Aplikasi Clone");
            addDrawerTileGroup(cloneTiles);
        }
        if (normalTiles.isEmpty() && cloneTiles.isEmpty()) {
            TextView empty = text("Tidak ada aplikasi yang cocok. Coba kata kunci lain.", 13, false);
            empty.setTextColor(0xFFB8C3D3);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(20), dp(16), dp(20), dp(16));
            empty.setContentDescription("Pencarian tidak menemukan aplikasi");
            drawerApps.addView(empty, new LinearLayout.LayoutParams(-1, dp(96)));
        }
    }

    private void addDrawerGroupTitle(String title) {
        TextView heading = text(title, 13, true);
        heading.setTextColor(0xFFDCE8FA);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.setPadding(dp(5), dp(4), 0, dp(2));
        drawerApps.addView(heading, new LinearLayout.LayoutParams(-1, dp(32)));
    }

    private void addDrawerTileGroup(List<View> tiles) {
        if (isDrawerListView()) {
            for (View tile : tiles) {
                LinearLayout item = (LinearLayout) tile;
                item.setOrientation(LinearLayout.HORIZONTAL);
                item.setGravity(Gravity.CENTER_VERTICAL);
                item.setPadding(dp(10), dp(4), dp(10), dp(4));
                ImageView icon = (ImageView) item.getChildAt(0);
                LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(40), dp(40));
                item.updateViewLayout(icon, iconParams);
                TextView label = (TextView) item.getChildAt(1);
                label.setGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT);
                label.setTextSize(14);
                LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(0, -1, 1f);
                labelParams.setMargins(dp(14), 0, 0, 0);
                item.updateViewLayout(label, labelParams);
                item.setBackgroundColor(Color.TRANSPARENT);
                LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, dp(56));
                rowParams.setMargins(0, dp(1), 0, dp(1));
                drawerApps.addView(item, rowParams);
            }
            return;
        }
        float density = getResources().getDisplayMetrics().density;
        int panelWidthPx = appDrawer != null && appDrawer.getLayoutParams() != null
                ? appDrawer.getLayoutParams().width : getResources().getDisplayMetrics().widthPixels;
        int contentWidthDp = Math.round(panelWidthPx / density) - 36;
        int minColumns = contentWidthDp < 260 ? 3 : 4;
        int columns = Math.max(minColumns, Math.min(6, contentWidthDp / 94));
        for (int i = 0; i < tiles.size(); i += columns) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int j = i; j < Math.min(i + columns, tiles.size()); j++) {
                LinearLayout.LayoutParams cell = new LinearLayout.LayoutParams(0, dp(88), 1f);
                cell.setMargins(dp(3), dp(2), dp(3), dp(2));
                row.addView(tiles.get(j), cell);
            }
            for (int j = Math.min(i + columns, tiles.size()); j < i + columns; j++) {
                row.addView(new View(this), new LinearLayout.LayoutParams(0, dp(88), 1f));
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
            applySavedProfileAfterLaunch(clone.getComponentName().getPackageName(), currentDisplayId());
            Toast.makeText(this, "Membuka salinan aplikasi: " + clone.getLabel(),
                    Toast.LENGTH_SHORT).show();
        } catch (Throwable error) {
            ForkdexErrorLog.add(this, "Launch cloned app " + clone.getComponentName(), error);
            Toast.makeText(this, "Gagal membuka aplikasi clone: " + error.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private String clonePinKey(LauncherActivityInfo clone) {
        return clone.getUser().hashCode() + ":"
                + clone.getComponentName().flattenToShortString();
    }

    private void showClonedAppActions(LauncherActivityInfo clone) {
        String packageName = clone.getComponentName().getPackageName();
        String label = clone.getLabel() == null ? packageName : clone.getLabel().toString();
        java.util.Set<String> pins = new java.util.HashSet<>(getSharedPreferences(
                "launcher_preferences", MODE_PRIVATE).getStringSet(
                "pinned_clones", Collections.emptySet()));
        boolean pinned = pins.contains(clonePinKey(clone));
        String[] actions = {"Buka aplikasi", pinned ? "Lepas dari Dock" : "Sematkan ke Dock",
                "Info aplikasi", "Sembunyikan aplikasi"};
        new AlertDialog.Builder(this).setTitle(label + " • Profil lain")
                .setItems(actions, (dialog, which) -> {
                    if (which == 0) {
                        setDrawerVisible(false);
                        launchClonedApp(clone);
                    } else if (which == 1) {
                        if (pinned) pins.remove(clonePinKey(clone));
                        else pins.add(clonePinKey(clone));
                        getSharedPreferences("launcher_preferences", MODE_PRIVATE).edit()
                                .putStringSet("pinned_clones", pins).apply();
                        populatePinnedApps();
                    } else if (which == 2) {
                        openAppInfo(packageName);
                    } else {
                        java.util.Set<String> hidden = new java.util.HashSet<>(getSharedPreferences(
                                "launcher_preferences", MODE_PRIVATE).getStringSet(
                                "hidden_apps", Collections.emptySet()));
                        hidden.add(packageName);
                        getSharedPreferences("launcher_preferences", MODE_PRIVATE).edit()
                                .putStringSet("hidden_apps", hidden).apply();
                        populateDrawerApps(apps, clonedApps);
                        populatePinnedApps();
                    }
                }).show();
    }

    private void showTaskbarPanel() {
        String[] actions = {"Internet & konektivitas", "Per-App DPI", "Shizuku",
                "Log diagnostik", "Jendela berjalan", "Desktop Mode", "Kembali ke mode normal",
                getSharedPreferences("desktop_preferences", MODE_PRIVATE).getBoolean("freeform_enabled", true)
                        ? "Mode jendela: Freeform (ketuk untuk Normal)"
                        : "Mode jendela: Normal (ketuk untuk Freeform)"};
        new AlertDialog.Builder(this).setTitle("Quick settings  •  Forkdex")
            .setItems(actions, (dialog, which) -> {
                switch (which) {
                    case 0: openConnectivityPanel(); break;
                    case 1: showProfiles(); break;
                    case 2: showShizukuDialog(); break;
                    case 3: showErrorLogs(); break;
                    case 4: showWindows(); break;
                    case 5: showDesktopModeSetup(); break;
                    case 7: toggleWindowMode(); break;
                    case 6:
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

    private void openConnectivityPanel() {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                Intent panel = new Intent(android.provider.Settings.Panel.ACTION_INTERNET_CONNECTIVITY);
                if (panel.resolveActivity(getPackageManager()) != null) {
                    startActivity(panel);
                    return;
                }
            }
            startActivity(new Intent(android.provider.Settings.ACTION_WIFI_SETTINGS));
        } catch (Throwable error) {
            ForkdexErrorLog.add(this, "Open connectivity quick settings", error);
            Toast.makeText(this, "Panel konektivitas tidak tersedia di ROM ini.",
                    Toast.LENGTH_LONG).show();
        }
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
        if (appDrawer == null) return;
        appDrawer.animate().cancel();
        if (visible) {
            appDrawer.setVisibility(View.VISIBLE);
            appDrawer.setPivotX(0f);
            appDrawer.setPivotY(appDrawer.getHeight());
            appDrawer.setAlpha(0f);
            appDrawer.setScaleX(0.97f);
            appDrawer.setScaleY(0.97f);
            appDrawer.setTranslationY(dp(10));
            appDrawer.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
                    .setDuration(220L).setInterpolator(new android.view.animation.DecelerateInterpolator())
                    .start();
        } else if (appDrawer.getVisibility() == View.VISIBLE) {
            appDrawer.animate().alpha(0f).scaleX(0.985f).scaleY(0.985f)
                    .translationY(dp(7)).setDuration(150L)
                    .setInterpolator(new android.view.animation.AccelerateInterpolator())
                    .withEndAction(() -> {
                        if (!drawerVisible) appDrawer.setVisibility(View.GONE);
                    }).start();
        }
    }

    private void focusDrawerSearch() {
        if (drawerSearch == null) return;
        drawerSearch.requestFocus();
        android.view.inputmethod.InputMethodManager keyboard =
                (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (keyboard != null) keyboard.showSoftInput(drawerSearch,
                android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
    }

    @Override public boolean dispatchTouchEvent(android.view.MotionEvent event) {
        int action = event.getActionMasked();
        if (action == android.view.MotionEvent.ACTION_DOWN) {
            drawerGestureDownX = event.getRawX();
            drawerGestureDownY = event.getRawY();
            drawerGestureEligible = !drawerVisible
                    && drawerGestureDownY > getResources().getDisplayMetrics().heightPixels * 0.55f;
            if (drawerVisible && appDrawer != null) {
                int[] location = new int[2];
                appDrawer.getLocationOnScreen(location);
                boolean inside = event.getRawX() >= location[0]
                        && event.getRawX() < location[0] + appDrawer.getWidth()
                        && event.getRawY() >= location[1]
                        && event.getRawY() < location[1] + appDrawer.getHeight();
                if (!inside) setDrawerVisible(false);
            }
        } else if (action == android.view.MotionEvent.ACTION_UP && drawerGestureEligible) {
            float dx = event.getRawX() - drawerGestureDownX;
            float dy = event.getRawY() - drawerGestureDownY;
            if (dy < -dp(90) && Math.abs(dx) < dp(100)) setDrawerVisible(true);
            drawerGestureEligible = false;
        } else if (action == android.view.MotionEvent.ACTION_CANCEL) {
            drawerGestureEligible = false;
        }
        return super.dispatchTouchEvent(event);
    }

    @Override public void onBackPressed() {
        if (drawerVisible) {
            if (drawerSearch != null && drawerSearch.hasFocus()) {
                android.view.inputmethod.InputMethodManager keyboard =
                        (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (keyboard != null) keyboard.hideSoftInputFromWindow(drawerSearch.getWindowToken(), 0);
                drawerSearch.clearFocus();
            }
            setDrawerVisible(false);
            return;
        }
        super.onBackPressed();
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private void addPressMotion(View view) {
        view.setOnTouchListener((target, event) -> {
            int action = event.getActionMasked();
            if (action == android.view.MotionEvent.ACTION_DOWN) {
                target.animate().scaleX(0.95f).scaleY(0.95f).setDuration(90L).start();
            } else if (action == android.view.MotionEvent.ACTION_UP
                    || action == android.view.MotionEvent.ACTION_CANCEL) {
                target.animate().scaleX(1f).scaleY(1f).setDuration(160L)
                        .setInterpolator(new android.view.animation.OvershootInterpolator(1.15f))
                        .start();
            }
            return false;
        });
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
        return new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x44FFFFFF), control,
                rounded(Color.WHITE, radiusDp));
    }

    private android.graphics.drawable.Drawable tileSurface(int color, float radiusDp) {
        return new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x34FFFFFF),
                rounded(color, radiusDp), rounded(Color.WHITE, radiusDp));
    }

    private void renderTaskbar(FrameLayout root) {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(12), dp(7), dp(12), dp(7));
        bar.setBackground(glassPanel(0xA3121D2C, 32));
        bar.setElevation(dp(24));

        ImageView start = new ImageView(this);
        start.setImageResource(R.drawable.forkdex_logo);
        start.setScaleType(ImageView.ScaleType.FIT_CENTER);
        start.setPadding(dp(5), dp(5), dp(5), dp(5));
        start.setContentDescription("Forkdex — Buka Start");
        start.setBackground(glassControl(0x784B79C5, 15));
        addPressMotion(start);
        start.setOnClickListener(v -> setDrawerVisible(
                appDrawer == null || appDrawer.getVisibility() != View.VISIBLE));
        bar.addView(start, new LinearLayout.LayoutParams(dp(46), dp(42)));

        android.widget.HorizontalScrollView scroll = new android.widget.HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setFillViewport(false);
        scroll.setContentDescription("Aplikasi terbuka atau baru digunakan; pin favorit dikelola di Start");
        scroll.setTooltipText("Aplikasi terbuka / recent apps");
        taskButtons = new LinearLayout(this);
        taskButtons.setOrientation(LinearLayout.HORIZONTAL);
        taskButtons.setGravity(Gravity.CENTER_VERTICAL);
        scroll.addView(taskButtons, new android.widget.HorizontalScrollView.LayoutParams(-2, -1));
        LinearLayout.LayoutParams taskParams = new LinearLayout.LayoutParams(0, dp(42), 1f);
        taskParams.setMargins(dp(10), 0, dp(10), 0);
        bar.addView(scroll, taskParams);

        View divider = new View(this);
        divider.setBackgroundColor(0x667D899B);
        bar.addView(divider, new LinearLayout.LayoutParams(dp(1), dp(25)));

        TextView quick = text("⌃", 18, true);
        quick.setTextColor(0xFFE8EDF6);
        quick.setGravity(Gravity.CENTER);
        quick.setContentDescription("Quick settings Forkdex");
        quick.setBackground(glassControl(0x6237465B, 14));
        addPressMotion(quick);
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
        taskbarDate = text(new java.text.SimpleDateFormat("EEE, d MMM",
                java.util.Locale.getDefault()).format(new java.util.Date()), 9, false);
        taskbarDate.setTextColor(0xFFB8C3D3);
        taskbarDate.setGravity(Gravity.CENTER);
        clockBox.addView(taskbarClock);
        clockBox.addView(taskbarDate);
        clockBox.setContentDescription("Jam dan tanggal");
        clockBox.setOnClickListener(v -> showTaskbarPanel());
        bar.addView(clockBox, new LinearLayout.LayoutParams(dp(68), dp(40)));

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                -1, dp(66), Gravity.BOTTOM);
        params.setMargins(dp(14), 0, dp(14), dp(10));
        root.addView(bar, params);
        lastTaskbarSignature = null;
        lastTaskbarStatus = null;
        lastTaskRefreshAt = 0L;
        refreshTaskbarTasks();
    }

    private void refreshTaskbarTasks() {
        if (taskButtons == null || !activityResumed) return;
        if (!windowClient.isConnected()) {
            String statusText = windowClient.isBinding()
                    ? "Menghubungkan Shizuku…" : "Hubungkan Shizuku untuk jendela";
            if (!statusText.equals(lastTaskbarStatus)) {
                taskButtons.removeAllViews();
                TextView status = text(statusText, 11, false);
                status.setTextColor(0xFFCED9EA);
                status.setGravity(Gravity.CENTER_VERTICAL);
                status.setContentDescription(windowClient.connectionStatus());
                status.setOnClickListener(v -> showShizukuDialog());
                taskButtons.addView(status, new LinearLayout.LayoutParams(-2, dp(40)));
                lastTaskbarStatus = statusText;
            }
            lastTaskbarSignature = null;
            lastTaskRefreshAt = 0L;
            return;
        }
        lastTaskbarStatus = null;
        long now = android.os.SystemClock.elapsedRealtime();
        if (taskRefreshInFlight || now - lastTaskRefreshAt < 5000L) return;
        lastTaskRefreshAt = now;
        taskRefreshInFlight = true;
        final int displayId = currentDisplayId();
        new Thread(() -> {
            try {