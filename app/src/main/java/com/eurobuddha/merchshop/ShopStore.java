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
        try (FileWriter w = new FileWriter(fileFor(id))) { w.write(json); }
        catch (Exception e) { return null; }
        return c;
    }

    public List<Catalog> all() {
        List<Catalog> out = new ArrayList<>();
        File[] fs = dir.listFiles((d, n) -> n.endsWith(".json"));
        if (fs != null) for (File f : fs) { Catalog c = read(f); if (c != null) out.add(c); }
        return out;
    }

    public Catalog get(String shopId) {
        try { return read(fileFor(shopId)); } catch (java.io.IOException invalid) { return null; }
    }

    public void delete(String shopId) {
        try { fileFor(shopId).delete(); } catch (java.io.IOException invalid) { /* Refuse escaped paths. */ }
    }

    /** Canonical containment reused from the host's NodeFiles.resolveInBase. */
    private File fileFor(String id) throws java.io.IOException {
        if (id == null || id.isEmpty() || id.indexOf('/') >= 0 || id.indexOf('\\') >= 0)
            throw new java.io.IOException("Invalid shop identifier");
        File target = new File(dir, id + ".json").getCanonicalFile();
        if (!dir.getCanonicalFile().equals(target.getParentFile()))
            throw new java.io.IOException("Shop path outside its folder");
        return target;
    }

    private Catalog read(File f) {
        try {
            if (!dir.getCanonicalFile().equals(f.getCanonicalFile().getParentFile())
                    || f.length() > 16 * 1024 * 1024) return null;
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
