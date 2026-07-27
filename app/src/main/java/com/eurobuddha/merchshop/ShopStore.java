package com.eurobuddha.merchshop;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Imported shops, stored as `.shop` JSON bundles in filesDir/shops/<shopId>.json. */
public final class ShopStore {

    private final File dir;

    public ShopStore(Context ctx) {
        dir = new File(ctx.getFilesDir(), "shops");
        dir.mkdirs();
    }

    /** Validate + save a bundle under its shopId; returns the parsed Catalog, or null if invalid. */
    public Catalog importBundle(String json) {
        Catalog c = Catalog.fromJson(json);
        if (!c.valid()) return null;
        String id = (c.shopId == null || c.shopId.isEmpty()) ? slug(c.shopName) : c.shopId;
        try (FileWriter w = new FileWriter(new File(dir, id + ".json"))) { w.write(json); }
        catch (Exception e) { return null; }
        return c;
    }

    public List<Catalog> all() {
        List<Catalog> out = new ArrayList<>();
        File[] fs = dir.listFiles((d, n) -> n.endsWith(".json"));
        if (fs != null) for (File f : fs) { Catalog c = read(f); if (c != null) out.add(c); }
        return out;
    }

    public Catalog get(String shopId) { return read(new File(dir, shopId + ".json")); }

    public void delete(String shopId) { new File(dir, shopId + ".json").delete(); }

    private Catalog read(File f) {
        try {
            byte[] b = new byte[(int) f.length()];
            try (FileInputStream in = new FileInputStream(f)) { int off = 0, r; while (off < b.length && (r = in.read(b, off, b.length - off)) > 0) off += r; }
            Catalog c = Catalog.fromJson(new String(b, StandardCharsets.UTF_8));
            return c.valid() ? c : null;
        } catch (Exception e) { return null; }
    }

    static String slug(String s) {
        String r = (s == null ? "shop" : s).replaceAll("[^a-zA-Z0-9]+", "-").replaceAll("^-+|-+$", "");
        return r.isEmpty() ? "shop" : r;
    }
}
