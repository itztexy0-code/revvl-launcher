package com.itztexy.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class MainActivity extends Activity {

    static final int REQ_PICK = 42;
    static final int DOCK_MAX = 5;

    static class AppInfo {
        String key, pkg, label;
        ComponentName cn;
        Drawable icon;
    }

    static class Entry {
        boolean folder;
        String key, label;
        Drawable icon;
    }

    Store store;
    PackageManager pm;
    final List<AppInfo> apps = new ArrayList<>();
    final Map<String, AppInfo> appMap = new HashMap<>();

    FrameLayout root, home, drawer;
    LinearLayout dock;
    GridView grid;
    EditText search;
    EntryAdapter drawerAdapter, folderAdapter;
    AlertDialog folderDialog;
    String openFolderId;
    int cols;

    // ------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        store = new Store(this);
        pm = getPackageManager();
        cols = store.sp.getInt("cols", 4);
        loadApps();
        initDock();
        buildUi();
        registerPackageReceiver();
        refreshAll();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home button pressed while we are already the launcher
        if (folderDialog != null) folderDialog.dismiss();
        closeDrawer();
    }

    @Override
    public void onBackPressed() {
        if (drawer.getVisibility() == View.VISIBLE) closeDrawer();
        // on a launcher's home screen Back does nothing
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            unregisterReceiver(pkgReceiver);
        } catch (Exception ignored) {
        }
    }

    final BroadcastReceiver pkgReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            loadApps();
            refreshAll();
        }
    };

    void registerPackageReceiver() {
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(pkgReceiver, f, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(pkgReceiver, f);
        }
    }

    // ------------------------------------------------------------ data

    void loadApps() {
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> list = pm.queryIntentActivities(i, 0);
        apps.clear();
        appMap.clear();
        for (ResolveInfo ri : list) {
            if (ri.activityInfo.packageName.equals(getPackageName())) continue;
            AppInfo a = new AppInfo();
            a.pkg = ri.activityInfo.packageName;
            a.cn = new ComponentName(a.pkg, ri.activityInfo.name);
            a.key = a.cn.flattenToString();
            a.label = String.valueOf(ri.loadLabel(pm));
            a.icon = ri.loadIcon(pm);
            apps.add(a);
            appMap.put(a.key, a);
        }
    }

    /** First run: put phone, messages, camera and browser in the dock if we can find them. */
    void initDock() {
        if (store.sp.getBoolean("dockInit", false)) return;
        Intent[] probes = {
                new Intent(Intent.ACTION_DIAL),
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MESSAGING),
                new Intent(MediaStore.ACTION_IMAGE_CAPTURE),
                new Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))
        };
        for (Intent in : probes) {
            ResolveInfo r = pm.resolveActivity(in, PackageManager.MATCH_DEFAULT_ONLY);
            if (r == null || r.activityInfo == null) continue;
            for (AppInfo a : apps) {
                if (a.pkg.equals(r.activityInfo.packageName) && !store.dock.contains(a.key)) {
                    store.dock.add(a.key);
                    break;
                }
            }
        }
        store.save();
        store.sp.edit().putBoolean("dockInit", true).apply();
    }

    String labelOf(AppInfo a) {
        String custom = store.labels.get(a.key);
        return custom != null ? custom : a.label;
    }

    Drawable iconOf(AppInfo a) {
        Drawable d = store.customIcon(a.key);
        return d != null ? d : a.icon;
    }

    Drawable copy(Drawable d) {
        Drawable.ConstantState cs = d.getConstantState();
        return cs != null ? cs.newDrawable(getResources()).mutate() : d;
    }

    Entry appEntry(AppInfo a) {
        Entry e = new Entry();
        e.key = a.key;
        e.label = labelOf(a);
        e.icon = iconOf(a);
        return e;
    }

    Entry folderEntry(Store.Folder f) {
        Entry e = new Entry();
        e.folder = true;
        e.key = f.id;
        e.label = f.name;
        e.icon = folderIcon(f);
        return e;
    }

    Drawable folderIcon(Store.Folder f) {
        Drawable custom = store.customIcon("folder:" + f.id);
        if (custom != null) return custom;
        int s = 192;
        Bitmap bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0x66FFFFFF);
        c.drawRoundRect(0, 0, s, s, s * 0.28f, s * 0.28f, p);
        int pad = (int) (s * 0.12f), gap = (int) (s * 0.06f);
        int cell = (s - pad * 2 - gap) / 2;
        int n = 0;
        for (String k : f.items) {
            AppInfo a = appMap.get(k);
            if (a == null) continue;
            if (n == 4) break;
            Drawable d = copy(iconOf(a));
            int x = pad + (n % 2) * (cell + gap);
            int y = pad + (n / 2) * (cell + gap);
            d.setBounds(x, y, x + cell, y + cell);
            d.draw(c);
            n++;
        }
        return new BitmapDrawable(getResources(), bmp);
    }

    List<Entry> drawerEntries() {
        String q = search == null ? "" : search.getText().toString().trim().toLowerCase();
        List<Entry> out = new ArrayList<>();
        if (!q.isEmpty()) {
            for (AppInfo a : apps) {
                if (labelOf(a).toLowerCase().contains(q) || a.label.toLowerCase().contains(q)) {
                    out.add(appEntry(a));
                }
            }
        } else {
            Set<String> inFolder = new HashSet<>();
            for (Store.Folder f : store.folders) {
                inFolder.addAll(f.items);
                out.add(folderEntry(f));
            }
            for (AppInfo a : apps) if (!inFolder.contains(a.key)) out.add(appEntry(a));
        }
        sortEntries(out);
        return out;
    }

    List<Entry> folderEntries() {
        List<Entry> out = new ArrayList<>();
        Store.Folder f = store.folder(openFolderId);
        if (f != null) {
            for (String k : f.items) {
                AppInfo a = appMap.get(k);
                if (a != null) out.add(appEntry(a));
            }
        }
        sortEntries(out);
        return out;
    }

    void sortEntries(List<Entry> list) {
        Collections.sort(list, (a, b) -> {
            if (a.folder != b.folder) return a.folder ? -1 : 1;
            return a.label.compareToIgnoreCase(b.label);
        });
    }

    void refreshAll() {
        if (drawerAdapter != null) drawerAdapter.reload();
        refreshDock();
        if (folderAdapter != null && folderDialog != null && folderDialog.isShowing()) folderAdapter.reload();
    }

    // ------------------------------------------------------------ UI

    int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    void style(TextView t, float sp) {
        t.setTextColor(Color.WHITE);
        t.setTextSize(sp);
        t.setShadowLayer(6, 0, 2, 0xAA000000);
    }

    void buildUi() {
        ViewGroup.LayoutParams match = new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);

        root = new FrameLayout(this);
        home = new FrameLayout(this);
        root.addView(home, match);

        // clock + date
        LinearLayout clock = new LinearLayout(this);
        clock.setOrientation(LinearLayout.VERTICAL);
        clock.setGravity(Gravity.CENTER_HORIZONTAL);
        TextClock time = new TextClock(this);
        time.setFormat12Hour("h:mm");
        time.setFormat24Hour("HH:mm");
        style(time, 64);
        TextClock date = new TextClock(this);
        date.setFormat12Hour("EEEE, MMMM d");
        date.setFormat24Hour("EEEE, MMMM d");
        style(date, 16);
        clock.addView(time);
        clock.addView(date);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        clp.topMargin = dp(72);
        home.addView(clock, clp);

        // swipe-up handle + dock
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout hbox = new FrameLayout(this);
        hbox.setPadding(dp(40), dp(14), dp(40), dp(14));
        View handle = new View(this);
        GradientDrawable hd = new GradientDrawable();
        hd.setColor(0xAAFFFFFF);
        hd.setCornerRadius(dp(3));
        handle.setBackground(hd);
        hbox.addView(handle, new FrameLayout.LayoutParams(dp(48), dp(5)));
        hbox.setOnClickListener(v -> openDrawer());
        bottom.addView(hbox);
        dock = new LinearLayout(this);
        dock.setOrientation(LinearLayout.HORIZONTAL);
        dock.setGravity(Gravity.CENTER);
        dock.setPadding(dp(8), 0, dp(8), dp(8));
        bottom.addView(dock, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        home.addView(bottom, blp);

        GestureDetector gd = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onFling(MotionEvent a, MotionEvent b, float vx, float vy) {
                if (a != null && b != null && a.getY() - b.getY() > dp(80) && vy < -600) {
                    openDrawer();
                    return true;
                }
                return false;
            }

            @Override
            public void onLongPress(MotionEvent e) {
                homeMenu();
            }
        });
        home.setOnTouchListener((v, ev) -> gd.onTouchEvent(ev));

        // app drawer overlay
        drawer = new FrameLayout(this);
        drawer.setBackgroundColor(0xF0141420);
        drawer.setClickable(true);
        drawer.setVisibility(View.GONE);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(12), dp(12), dp(12), 0);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        search = new EditText(this);
        search.setHint("Search apps");
        search.setHintTextColor(0x88FFFFFF);
        search.setTextColor(Color.WHITE);
        search.setSingleLine();
        search.setInputType(InputType.TYPE_CLASS_TEXT);
        search.setImeOptions(EditorInfo.IME_ACTION_GO);
        search.setPadding(dp(16), dp(10), dp(16), dp(10));
        GradientDrawable sbg = new GradientDrawable();
        sbg.setColor(0x22FFFFFF);
        sbg.setCornerRadius(dp(24));
        search.setBackground(sbg);
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            public void afterTextChanged(Editable s) {
                if (drawerAdapter != null) drawerAdapter.reload();
            }
        });
        search.setOnEditorActionListener((v, actionId, ev) -> {
            if (!drawerAdapter.items.isEmpty()) onEntryClick(drawerAdapter.items.get(0));
            return true;
        });
        top.addView(search, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button newFolder = new Button(this);
        newFolder.setText("+ Folder");
        newFolder.setAllCaps(false);
        newFolder.setOnClickListener(v -> prompt("New folder", "", s -> {
            if (!s.isEmpty()) {
                store.newFolder(s);
                refreshAll();
            }
        }));
        top.addView(newFolder);
        col.addView(top);

        grid = new GridView(this);
        grid.setNumColumns(cols);
        grid.setVerticalSpacing(dp(8));
        grid.setClipToPadding(false);
        grid.setPadding(0, dp(12), 0, dp(24));
        grid.setSelector(new ColorDrawable(Color.TRANSPARENT));
        grid.setVerticalScrollBarEnabled(false);
        drawerAdapter = new EntryAdapter(this::drawerEntries, true);
        grid.setAdapter(drawerAdapter);
        grid.setOnItemClickListener((p, v, pos, id) -> onEntryClick(drawerAdapter.items.get(pos)));
        grid.setOnItemLongClickListener((p, v, pos, id) -> {
            showMenu(drawerAdapter.items.get(pos));
            return true;
        });
        col.addView(grid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        drawer.addView(col, match);
        root.addView(drawer, match);
        setContentView(root);
    }

    View makeCell(Entry e, boolean showLabel) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setGravity(Gravity.CENTER_HORIZONTAL);
        l.setPadding(dp(4), dp(8), dp(4), dp(8));
        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setImageDrawable(copy(e.icon));
        l.addView(iv, new LinearLayout.LayoutParams(dp(56), dp(56)));
        if (showLabel) {
            TextView tv = new TextView(this);
            tv.setText(e.label);
            tv.setMaxLines(1);
            tv.setEllipsize(TextUtils.TruncateAt.END);
            tv.setGravity(Gravity.CENTER);
            style(tv, 12);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(4);
            l.addView(tv, lp);
        }
        return l;
    }

    class EntryAdapter extends BaseAdapter {
        final Supplier<List<Entry>> src;
        final boolean labels;
        List<Entry> items = new ArrayList<>();

        EntryAdapter(Supplier<List<Entry>> src, boolean labels) {
            this.src = src;
            this.labels = labels;
        }

        void reload() {
            items = src.get();
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int i) {
            return items.get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public View getView(int i, View convert, ViewGroup parent) {
            return makeCell(items.get(i), labels);
        }
    }

    void refreshDock() {
        dock.removeAllViews();
        for (String k : new ArrayList<>(store.dock)) {
            AppInfo a = appMap.get(k);
            if (a == null) continue;
            Entry e = appEntry(a);
            View cell = makeCell(e, false);
            cell.setOnClickListener(v -> launch(e.key));
            cell.setOnLongClickListener(v -> {
                showMenu(e);
                return true;
            });
            dock.addView(cell, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
    }

    void openDrawer() {
        if (drawer.getVisibility() == View.VISIBLE) return;
        drawerAdapter.reload();
        drawer.setAlpha(0f);
        drawer.setTranslationY(dp(120));
        drawer.setVisibility(View.VISIBLE);
        drawer.animate().alpha(1f).translationY(0).setDuration(200).start();
    }

    void closeDrawer() {
        if (drawer.getVisibility() != View.VISIBLE) return;
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(search.getWindowToken(), 0);
        search.setText("");
        search.clearFocus();
        drawer.animate().alpha(0f).translationY(dp(120)).setDuration(160)
                .withEndAction(() -> drawer.setVisibility(View.GONE)).start();
    }

    // ------------------------------------------------------------ actions

    void onEntryClick(Entry e) {
        if (e.folder) openFolder(e.key);
        else launch(e.key);
    }

    void launch(String key) {
        AppInfo a = appMap.get(key);
        if (a == null) return;
        Intent i = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(a.cn)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            startActivity(i);
        } catch (Exception ex) {
            Toast.makeText(this, "Can't open " + a.label, Toast.LENGTH_SHORT).show();
            return;
        }
        if (folderDialog != null) folderDialog.dismiss();
        closeDrawer();
    }

    void openFolder(String id) {
        Store.Folder f = store.folder(id);
        if (f == null) return;
        openFolderId = id;

        folderAdapter = new EntryAdapter(this::folderEntries, true);
        GridView gv = new GridView(this);
        gv.setNumColumns(3);
        gv.setVerticalSpacing(dp(8));
        gv.setSelector(new ColorDrawable(Color.TRANSPARENT));
        gv.setPadding(dp(8), dp(8), dp(8), dp(8));
        gv.setAdapter(folderAdapter);
        gv.setOnItemClickListener((p, v, pos, i) -> onEntryClick(folderAdapter.items.get(pos)));
        gv.setOnItemLongClickListener((p, v, pos, i) -> {
            showMenu(folderAdapter.items.get(pos));
            return true;
        });
        TextView empty = new TextView(this);
        empty.setText("Empty folder.\nLong-press an app and choose \"Add to folder\".");
        empty.setTextColor(0xAAFFFFFF);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(dp(16), dp(24), dp(16), dp(24));
        FrameLayout wrap = new FrameLayout(this);
        wrap.addView(gv);
        wrap.addView(empty);
        gv.setEmptyView(empty);

        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle(f.name)
                .setView(wrap)
                .setPositiveButton("Close", null)
                .setNeutralButton("Rename", (d, w) -> prompt("Rename folder", f.name, s -> {
                    if (!s.isEmpty()) {
                        f.name = s;
                        store.save();
                        refreshAll();
                    }
                    openFolder(id);
                }))
                .create();
        dlg.setOnDismissListener(d -> {
            if (folderDialog == dlg) {
                folderDialog = null;
                openFolderId = null;
            }
        });
        folderDialog = dlg;
        folderAdapter.reload();
        dlg.show();
    }

    void showMenu(Entry e) {
        List<String> opts = new ArrayList<>();
        opts.add("Rename");
        opts.add("Change icon");
        if (e.folder) {
            opts.add("Reset icon");
            opts.add("Delete folder");
        } else {
            opts.add("Reset name & icon");
            opts.add("Add to folder");
            if (store.folderOf(e.key) != null) opts.add("Remove from folder");
            opts.add(store.dock.contains(e.key) ? "Remove from dock" : "Add to dock");
            opts.add("App info");
            opts.add("Uninstall");
        }
        new AlertDialog.Builder(this)
                .setTitle(e.label)
                .setItems(opts.toArray(new String[0]), (d, w) -> handle(e, opts.get(w)))
                .show();
    }

    void handle(Entry e, String action) {
        String iconKey = e.folder ? "folder:" + e.key : e.key;
        AppInfo a = e.folder ? null : appMap.get(e.key);
        switch (action) {
            case "Rename":
                prompt("Rename", e.label, s -> {
                    if (e.folder) {
                        Store.Folder f = store.folder(e.key);
                        if (f != null && !s.isEmpty()) f.name = s;
                    } else if (s.isEmpty()) {
                        store.labels.remove(e.key);
                    } else {
                        store.labels.put(e.key, s);
                    }
                    store.save();
                    refreshAll();
                });
                break;
            case "Change icon":
                new AlertDialog.Builder(this)
                        .setTitle("Change icon")
                        .setItems(new String[]{"Choose image from gallery", "Restore default"}, (d, w) -> {
                            if (w == 0) pickImage(iconKey);
                            else {
                                store.resetIcon(iconKey);
                                refreshAll();
                            }
                        }).show();
                break;
            case "Reset icon":
                store.resetIcon(iconKey);
                refreshAll();
                break;
            case "Reset name & icon":
                store.labels.remove(e.key);
                store.resetIcon(iconKey);
                refreshAll();
                break;
            case "Delete folder":
                store.deleteFolder(e.key);
                if (folderDialog != null) folderDialog.dismiss();
                refreshAll();
                break;
            case "Add to folder":
                chooseFolder(e.key);
                break;
            case "Remove from folder":
                store.removeFromFolders(e.key);
                refreshAll();
                break;
            case "Add to dock":
                if (store.dock.size() >= DOCK_MAX) {
                    Toast.makeText(this, "Dock is full (" + DOCK_MAX + ")", Toast.LENGTH_SHORT).show();
                } else {
                    store.dock.add(e.key);
                    store.save();
                    refreshAll();
                }
                break;
            case "Remove from dock":
                store.dock.remove(e.key);
                store.save();
                refreshAll();
                break;
            case "App info":
                if (a != null) {
                    startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + a.pkg)));
                }
                break;
            case "Uninstall":
                if (a != null) {
                    startActivity(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + a.pkg)));
                }
                break;
            default:
                break;
        }
    }

    void chooseFolder(String appKey) {
        List<String> names = new ArrayList<>();
        for (Store.Folder f : store.folders) names.add(f.name);
        names.add("New folder…");
        new AlertDialog.Builder(this)
                .setTitle("Add to folder")
                .setItems(names.toArray(new String[0]), (d, w) -> {
                    if (w == store.folders.size()) {
                        prompt("New folder", "", s -> {
                            if (s.isEmpty()) return;
                            Store.Folder f = store.newFolder(s);
                            store.moveToFolder(appKey, f.id);
                            refreshAll();
                        });
                    } else {
                        store.moveToFolder(appKey, store.folders.get(w).id);
                        refreshAll();
                    }
                }).show();
    }

    void homeMenu() {
        new AlertDialog.Builder(this)
                .setItems(new String[]{"Change wallpaper", "Drawer columns"}, (d, w) -> {
                    if (w == 0) {
                        try {
                            startActivity(Intent.createChooser(
                                    new Intent(Intent.ACTION_SET_WALLPAPER), "Select wallpaper"));
                        } catch (Exception ex) {
                            Toast.makeText(this, "No wallpaper picker found", Toast.LENGTH_SHORT).show();
                        }
                    } else {
                        new AlertDialog.Builder(this)
                                .setTitle("Drawer columns")
                                .setItems(new String[]{"3", "4", "5"}, (d2, w2) -> {
                                    cols = w2 + 3;
                                    store.sp.edit().putInt("cols", cols).apply();
                                    grid.setNumColumns(cols);
                                }).show();
                    }
                }).show();
    }

    void prompt(String title, String initial, Consumer<String> cb) {
        EditText et = new EditText(this);
        et.setText(initial);
        et.setSingleLine();
        et.setSelectAllOnFocus(true);
        FrameLayout wrap = new FrameLayout(this);
        wrap.setPadding(dp(20), dp(8), dp(20), 0);
        wrap.addView(et);
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(wrap)
                .setPositiveButton("OK", (d, w) -> cb.accept(et.getText().toString().trim()))
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ------------------------------------------------------------ icon picking

    void pickImage(String key) {
        store.sp.edit().putString("pending", key).apply();
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        startActivityForResult(i, REQ_PICK);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_PICK || res != RESULT_OK || data == null || data.getData() == null) return;
        String key = store.sp.getString("pending", null);
        if (key == null) return;
        try {
            Bitmap bmp = loadSquare(data.getData(), 192);
            store.setIcon(key, bmp);
            refreshAll();
        } catch (IOException ex) {
            Toast.makeText(this, "Couldn't load that image", Toast.LENGTH_SHORT).show();
        }
    }

    Bitmap loadSquare(Uri uri, int size) throws IOException {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(in, null, o);
        }
        int sample = 1;
        while (o.outWidth / (sample * 2) >= size && o.outHeight / (sample * 2) >= size) sample *= 2;
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = sample;
        Bitmap b;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            b = BitmapFactory.decodeStream(in, null, o2);
        }
        if (b == null) throw new IOException("decode failed");
        int m = Math.min(b.getWidth(), b.getHeight());
        Bitmap sq = Bitmap.createBitmap(b, (b.getWidth() - m) / 2, (b.getHeight() - m) / 2, m, m);
        return Bitmap.createScaledBitmap(sq, size, size, true);
    }
}
