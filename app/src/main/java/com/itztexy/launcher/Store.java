package com.itztexy.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Keeps custom names, custom icons, folders and the dock. Stored as JSON in SharedPreferences. */
public class Store {

    public static class Folder {
        public String id;
        public String name;
        public List<String> items = new ArrayList<>();
    }

    final Context ctx;
    final SharedPreferences sp;
    final Map<String, String> labels = new HashMap<>();
    final Map<String, String> icons = new HashMap<>();
    final List<Folder> folders = new ArrayList<>();
    final List<String> dock = new ArrayList<>();
    private final Map<String, Drawable> cache = new HashMap<>();

    Store(Context c) {
        ctx = c;
        sp = c.getSharedPreferences("launcher", Context.MODE_PRIVATE);
        load();
    }

    private void load() {
        try {
            JSONObject o = new JSONObject(sp.getString("data", "{}"));
            readMap(o.optJSONObject("labels"), labels);
            readMap(o.optJSONObject("icons"), icons);
            JSONArray fa = o.optJSONArray("folders");
            if (fa != null) {
                for (int i = 0; i < fa.length(); i++) {
                    JSONObject fo = fa.getJSONObject(i);
                    Folder f = new Folder();
                    f.id = fo.getString("id");
                    f.name = fo.getString("name");
                    JSONArray ia = fo.optJSONArray("items");
                    if (ia != null) for (int j = 0; j < ia.length(); j++) f.items.add(ia.getString(j));
                    folders.add(f);
                }
            }
            JSONArray da = o.optJSONArray("dock");
            if (da != null) for (int i = 0; i < da.length(); i++) dock.add(da.getString(i));
        } catch (JSONException ignored) {
        }
    }

    private static void readMap(JSONObject src, Map<String, String> dst) throws JSONException {
        if (src == null) return;
        for (Iterator<String> it = src.keys(); it.hasNext(); ) {
            String k = it.next();
            dst.put(k, src.getString(k));
        }
    }

    void save() {
        try {
            JSONObject o = new JSONObject();
            o.put("labels", new JSONObject(labels));
            o.put("icons", new JSONObject(icons));
            JSONArray fa = new JSONArray();
            for (Folder f : folders) {
                JSONObject fo = new JSONObject();
                fo.put("id", f.id);
                fo.put("name", f.name);
                fo.put("items", new JSONArray(f.items));
                fa.put(fo);
            }
            o.put("folders", fa);
            o.put("dock", new JSONArray(dock));
            sp.edit().putString("data", o.toString()).apply();
        } catch (JSONException ignored) {
        }
    }

    // ---------- folders ----------

    Folder folder(String id) {
        for (Folder f : folders) if (f.id.equals(id)) return f;
        return null;
    }

    Folder folderOf(String appKey) {
        for (Folder f : folders) if (f.items.contains(appKey)) return f;
        return null;
    }

    Folder newFolder(String name) {
        Folder f = new Folder();
        f.id = UUID.randomUUID().toString();
        f.name = name;
        folders.add(f);
        save();
        return f;
    }

    void deleteFolder(String id) {
        Folder f = folder(id);
        if (f == null) return;
        folders.remove(f);
        resetIcon("folder:" + id);
        save();
    }

    void removeFromFolders(String appKey) {
        for (Folder f : folders) f.items.remove(appKey);
        save();
    }

    void moveToFolder(String appKey, String folderId) {
        for (Folder f : folders) f.items.remove(appKey);
        Folder target = folder(folderId);
        if (target != null) target.items.add(appKey);
        save();
    }

    // ---------- custom icons ----------

    Drawable customIcon(String key) {
        if (cache.containsKey(key)) return cache.get(key);
        Drawable d = null;
        String name = icons.get(key);
        if (name != null) {
            Bitmap b = BitmapFactory.decodeFile(new File(iconDir(), name).getPath());
            if (b != null) d = new BitmapDrawable(ctx.getResources(), b);
        }
        cache.put(key, d);
        return d;
    }

    private File iconDir() {
        File dir = new File(ctx.getFilesDir(), "icons");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    void setIcon(String key, Bitmap bmp) {
        try {
            String name = Integer.toHexString(key.hashCode()) + "_" + System.currentTimeMillis() + ".png";
            try (FileOutputStream out = new FileOutputStream(new File(iconDir(), name))) {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
            }
            String old = icons.put(key, name);
            if (old != null) new File(iconDir(), old).delete();
            cache.remove(key);
            save();
        } catch (Exception ignored) {
        }
    }

    void resetIcon(String key) {
        String old = icons.remove(key);
        if (old != null) new File(iconDir(), old).delete();
        cache.remove(key);
        save();
    }
}
