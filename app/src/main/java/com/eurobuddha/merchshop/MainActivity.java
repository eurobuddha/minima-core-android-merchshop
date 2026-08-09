package com.eurobuddha.merchshop;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.goterl.lazysodium.LazySodium;

import org.json.JSONArray;
import org.json.JSONObject;
import com.eurobuddha.comms.CommsIdentity;
import com.eurobuddha.comms.CommsScanner;
import com.eurobuddha.comms.CommsTransport;
import com.eurobuddha.comms.CryptoProvider;
import com.eurobuddha.comms.Hex;
import com.eurobuddha.comms.LocalEcCryptoProvider;
import com.eurobuddha.comms.MailText;
import com.eurobuddha.comms.MerchDb;
import com.eurobuddha.comms.MerchMessage;
import com.eurobuddha.comms.NodeApi;
import com.eurobuddha.comms.Opened;
import com.eurobuddha.comms.Sodium;
import org.minimarex.minimaapi.MinimaAPIMessages;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** miniMall Shop — the customer storefront (Maxima-free, on the comms primitive). */
public class MainActivity extends AppCompatActivity {

    private static final String CH = "merchshop";

    private LazySodium ls;
    private NodeApi node;
    private MerchDb db;
    private CryptoProvider crypto;
    private CommsScanner scanner;
    private String myId, buyerPayaddr = "";
    private boolean paired = false;
    private int chainBlock = 0;
    private ShopStore store;
    private Catalog catalog;   // the currently-open shop (null on the My Shops home)
    private ActivityResultLauncher<String[]> importLauncher;
    private final Map<String, Integer> cart = new LinkedHashMap<>();
    private final android.util.LruCache<String, Bitmap> imgCache = new android.util.LruCache<>(8 * 1024 * 1024) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ArrayDeque<View> stack = new ArrayDeque<>();

    private LinearLayout root;
    private FrameLayout container;
    private View pairingBanner;
    private BroadcastReceiver notifyReceiver;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        ls = Sodium.get();
        db = new MerchDb(this);
        store = new ShopStore(this);

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Design.BG);
        pairingBanner = buildPairingBanner();
        pairingBanner.setVisibility(View.GONE);
        container = new FrameLayout(this);
        root.addView(pairingBanner, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(container, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        applyInsets();
        ensureChannel();
        requestNotifPermission();

        importLauncher = registerForActivityResult(new ActivityResultContracts.OpenDocument(),
                uri -> { if (uri != null) importShop(uri); });
        showHome();
        handleIncomingShop(getIntent());   // launched by tapping a .shop file?

        node = new NodeApi(this, this::onPaired);
        notifyReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                try {
                    String event = new JSONObject(i.getStringExtra(MinimaAPIMessages.MINIMA_API_NOTIFY_DATA)).optString("event", "");
                    if ("NEWBLOCK".equals(event) || "NEWBALANCE".equals(event)) { fetchBlock(); requestScan(); }
                } catch (Exception ignored) {}
            }
        };
        ContextCompat.registerReceiver(this, notifyReceiver,
                new IntentFilter(MinimaAPIMessages.MINIMA_API_NOTIFY), ContextCompat.RECEIVER_EXPORTED);
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (notifyReceiver != null) try { unregisterReceiver(notifyReceiver); } catch (Exception ignored) {}
        if (node != null) node.onDestroy();
        io.shutdownNow();
    }

    @Override public void onBackPressed() { if (stack.size() > 1) pop(); else super.onBackPressed(); }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingShop(intent);
    }

    /** Launched (or re-launched) by tapping a shared .shop file → import it. */
    private void handleIncomingShop(Intent intent) {
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction())) return;
        android.net.Uri uri = intent.getData();
        if (uri != null) importShop(uri);
    }

    private void applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            root.setPadding(0, bars.top, 0, Math.max(bars.bottom, ime.bottom));
            return insets;
        });
        ViewCompat.requestApplyInsets(root);
        new WindowInsetsControllerCompat(getWindow(), root).setAppearanceLightStatusBars(false);
    }

    // ---- navigation ----
    private void push(View v) { stack.push(v); showTop(); }
    private void pop() { if (stack.size() > 1) { stack.pop(); showTop(); } }
    private void showHome() { stack.clear(); stack.push(buildHome()); showTop(); }
    private void openShop(String shopId) {
        Catalog c = store.get(shopId);
        if (c == null) { toast("Couldn't open that shop."); return; }
        catalog = c; cart.clear();
        push(buildStorefront());
    }
    private void showTop() { container.removeAllViews(); container.addView(stack.peek()); }
    private void refreshTop(View rebuilt) { stack.pop(); stack.push(rebuilt); showTop(); }

    // ---- pairing + identity ----
    private void onPaired(boolean enabled) {
        paired = enabled;
        pairingBanner.setVisibility(enabled ? View.GONE : View.VISIBLE);
        if (enabled) {
            if (chainBlock == 0) fetchBlock();
            fetchBuyerAddr();
            if (crypto == null) setupIdentity(); else requestScan();
        }
    }

    private void fetchBuyerAddr() {
        node.cmd("getaddress", new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                JSONObject r = j.optJSONObject("response");
                if (r != null) { String a = r.optString("miniaddress", r.optString("address", "")); if (!a.isEmpty()) buyerPayaddr = a; }
            }
            @Override public void onError(String m) {}
        });
    }

    private void fetchBlock() {
        node.cmd("block", new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                JSONObject r = j.optJSONObject("response");
                if (r != null) try { chainBlock = Integer.parseInt(r.optString("block", "0")); } catch (Exception ignored) {}
            }
            @Override public void onError(String m) {}
        });
    }

    private void setupIdentity() {
        node.cmd("vault action:seed", new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                JSONObject r = j.optJSONObject("response");
                String ikm = r == null ? "" : r.optString("seed", r.optString("phrase", ""));
                if (ikm.isEmpty()) askForSeed(); else deriveIdentity(ikm);
            }
            @Override public void onError(String m) {
                if (NodeApi.ERR_NOT_ENABLED.equals(m)) { pairingBanner.setVisibility(View.VISIBLE); return; }
                askForSeed();
            }
        });
    }

    private void deriveIdentity(final String ikm) {
        io.execute(() -> {
            try {
                byte[] seed = ikm.startsWith("0x") ? Hex.from(ikm) : ikm.getBytes(StandardCharsets.UTF_8);
                CommsIdentity id = CommsIdentity.fromSeed(ls, seed);
                ui.post(() -> { adoptIdentity(id); requestScan(); });
            } catch (Exception e) { ui.post(() -> toast("Identity error: " + e.getMessage())); }
        });
    }

    private void adoptIdentity(CommsIdentity id) {
        crypto = new LocalEcCryptoProvider(ls, id);
        myId = id.publicId();
        scanner = new CommsScanner(node, crypto, db, CommsTransport.MINIMERCH_ADDRESS,
                this::routeIncoming, (ok, n) -> onScanDone(n));
    }

    private void askForSeed() {
        final EditText in = input("Your Minima seed phrase");
        in.setMinLines(3);
        new AlertDialog.Builder(this)
                .setTitle("Set up to shop")
                .setMessage("A shopping key is derived from your Minima seed so the vendor can confirm your order and message you. Paste your seed once — it's used only to derive the key and is never stored.")
                .setView(in)
                .setPositiveButton("Continue", (d, w) -> { String s = in.getText().toString().trim(); if (!s.isEmpty()) deriveIdentity(s); })
                .show();
    }

    // ---- scanning ----
    private void requestScan() { if (crypto != null && scanner != null) scanner.scan(chainBlock); }

    /** Vendor → me: order status updates + replies, sealed to my box key, threaded by order ref. */
    private boolean routeIncoming(String coinid, Opened opened, JSONObject coin) {
        MerchMessage m = MerchMessage.fromWire(opened.plaintext);
        if (m == null || !opened.fromPublicId.equals(m.from) || !myId.equals(m.to)) return false;
        if (MerchMessage.STATUS_UPDATE.equals(m.type)) {
            MerchDb.Order o = db.order(m.ref);
            if (o == null) return false;
            db.setStatus(m.ref, m.status);
            // NFT order marked shipped/delivered = the token left the vendor's wallet — say so.
            if ((MerchDb.SHIPPED.equals(m.status) || MerchDb.DELIVERED.equals(m.status)) && !nftLines(o).isEmpty())
                nftDeliveredNotify = true;
            return true;
        }
        if (MerchMessage.REPLY.equals(m.type)) {
            return db.insertChat(m.ref, true, m.message, m.randomid, m.date > 0 ? m.date : System.currentTimeMillis());
        }
        return false;
    }

    private void onScanDone(int newCount) {
        ui.post(() -> {
            if (newCount <= 0) return;
            notifyNew(newCount);
            View top = stack.peek();
            Object tag = top == null ? null : top.getTag();
            if ("orders".equals(tag)) refreshTop(buildMyOrders());
            else if (tag instanceof String && ((String) tag).startsWith("order:")) refreshTop(buildOrderDetail(((String) tag).substring(6)));
        });
    }

    // ---- storefront ----
    // ---- My Shops home ----
    private View buildHome() {
        LinearLayout col = column();
        LinearLayout head = header("miniMall", false);
        head.addView(iconBtn("🧾", this::showMyOrders));   // my orders (across all shops)
        col.addView(head);

        TextView ver = new TextView(this);
        ver.setText("miniMall Shop v" + BuildConfig.VERSION_NAME);
        ver.setTextColor(Design.DIM2); ver.setTextSize(10f); ver.setPadding(dp(16), dp(2), dp(16), dp(4));
        col.addView(ver);

        if (crypto == null) {
            TextView s = new TextView(this);
            s.setText(paired ? "Connecting to your node…" : "Enable miniMall Shop in Minima Core → Apps to order.");
            s.setTextColor(Design.DIM); s.setTextSize(13f); s.setPadding(dp(16), dp(8), dp(16), dp(8));
            col.addView(s);
        }

        final List<Catalog> shops = store.all();
        if (shops.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("No shops yet.\n\nTap “Add a shop” below and pick a .shop file a vendor shared with you.");
            empty.setTextColor(Design.DIM2); empty.setGravity(Gravity.CENTER); empty.setTextSize(14f);
            empty.setPadding(dp(24), dp(56), dp(24), 0);
            col.addView(empty, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        } else {
            RecyclerView rv = new RecyclerView(this);
            rv.setLayoutManager(new LinearLayoutManager(this));
            rv.setAdapter(new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                @NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup p, int t) {
                    LinearLayout row = new LinearLayout(MainActivity.this); row.setOrientation(LinearLayout.VERTICAL);
                    row.setPadding(dp(16), dp(14), dp(16), dp(14));
                    row.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                    return new RecyclerView.ViewHolder(row) {};
                }
                @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int pos) {
                    final Catalog c = shops.get(pos);
                    LinearLayout row = (LinearLayout) h.itemView; row.removeAllViews();
                    TextView name = new TextView(MainActivity.this);
                    name.setText(c.shopName); name.setTextColor(Design.TEXT); name.setTextSize(16f); name.setTypeface(null, Typeface.BOLD);
                    row.addView(name);
                    TextView sub = new TextView(MainActivity.this);
                    sub.setText(c.products.size() + " product" + (c.products.size() == 1 ? "" : "s") + "  ·  " + c.currency);
                    sub.setTextColor(Design.DIM); sub.setTextSize(12f); sub.setPadding(0, dp(3), 0, 0);
                    row.addView(sub);
                    row.setOnClickListener(v -> openShop(shopKey(c)));
                    row.setOnLongClickListener(v -> { confirmRemoveShop(c); return true; });
                }
                @Override public int getItemCount() { return shops.size(); }
            });
            col.addView(rv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        }

        TextView add = button("＋  Add a shop", true);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        int m = dp(16); alp.setMargins(m, m, m, m); add.setLayoutParams(alp); add.setPadding(dp(16), dp(14), dp(16), dp(14));
        add.setOnClickListener(v -> importLauncher.launch(new String[]{"application/json", "application/octet-stream", "*/*"}));
        col.addView(add);
        return col;
    }

    private static String shopKey(Catalog c) {
        return (c.shopId == null || c.shopId.isEmpty()) ? ShopStore.slug(c.shopName) : c.shopId;
    }

    private void importShop(android.net.Uri uri) {
        io.execute(() -> {
            try (java.io.InputStream is = getContentResolver().openInputStream(uri)) {
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192]; int n; while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
                String json = new String(bos.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
                final Catalog c = store.importBundle(json);
                ui.post(() -> {
                    if (c == null) toast("That doesn't look like a valid .shop file.");
                    else { toast("Added " + c.shopName); showHome(); }
                });
            } catch (Exception e) { ui.post(() -> toast("Couldn't read that file.")); }
        });
    }

    private void confirmRemoveShop(final Catalog c) {
        new AlertDialog.Builder(this).setTitle("Remove " + c.shopName + "?")
                .setMessage("Removes the shop from your list. Your past orders stay in My Orders.")
                .setPositiveButton("Remove", (d, w) -> io.execute(() -> { store.delete(shopKey(c)); ui.post(() -> refreshTop(buildHome())); }))
                .setNegativeButton("Cancel", null).show();
    }

    private View buildStorefront() {
        final boolean nftShop = catalog.isNftShop();
        LinearLayout col = column();
        LinearLayout head = header(catalog.shopName, true);    // back → My Shops
        head.addView(iconBtn("✉", this::openGeneralThread));   // message the vendor (pre-sales)
        col.addView(head);

        if (nftShop) {
            // collection header, marketplace-style
            TextView sub = new TextView(this);
            sub.setText("NFT SHOP · PAYS IN " + catalog.currency.toUpperCase());
            sub.setTextColor(Design.ACCENT); sub.setTextSize(11f); sub.setTypeface(null, Typeface.BOLD);
            sub.setPadding(dp(16), dp(4), dp(16), 0);
            col.addView(sub);
            if (!catalog.about.trim().isEmpty()) {
                TextView abt = new TextView(this);
                abt.setText(catalog.about.trim());
                abt.setTextColor(Design.DIM); abt.setTextSize(13f); abt.setPadding(dp(16), dp(4), dp(16), dp(4));
                col.addView(abt);
            }
        } else {
            TextView ver = new TextView(this);
            ver.setText("Pays in " + catalog.currency);
            ver.setTextColor(Design.DIM2); ver.setTextSize(11f); ver.setPadding(dp(16), dp(2), dp(16), dp(6));
            col.addView(ver);
        }

        if (crypto == null) {
            TextView s = new TextView(this);
            s.setText(paired ? "Connecting to your node…" : "Enable miniMall Shop in Minima Core → Apps to order.");
            s.setTextColor(Design.DIM); s.setTextSize(13f); s.setPadding(dp(16), dp(8), dp(16), dp(8));
            col.addView(s);
        }

        RecyclerView rv = new RecyclerView(this);
        if (nftShop) {
            final NftGridAdapter ad = new NftGridAdapter();
            androidx.recyclerview.widget.GridLayoutManager glm = new androidx.recyclerview.widget.GridLayoutManager(this, 2);
            glm.setSpanSizeLookup(new androidx.recyclerview.widget.GridLayoutManager.SpanSizeLookup() {
                @Override public int getSpanSize(int pos) { return ad.rows.get(pos) instanceof ColHeader ? 2 : 1; }
            });
            rv.setLayoutManager(glm);
            rv.setPadding(dp(10), dp(4), dp(10), dp(10));
            rv.setClipToPadding(false);
            rv.setAdapter(ad);
        } else {
            rv.setLayoutManager(new LinearLayoutManager(this));
            rv.setAdapter(new ProductAdapter());
        }
        col.addView(rv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        col.addView(buildCartBar());
        return col;
    }

    /** A full-width collection section header in the NFT grid — carries the tokenid so the
     *  standard top-side token icon renders beside the name (the cards carry the stamped art). */
    private static final class ColHeader {
        final String tokenid, label;
        ColHeader(String tokenid, String label) { this.tokenid = tokenid; this.label = label; }
    }

    /** 2-col art-first grid for NFT shops: square art, name, edition chip, price. Tap → item page.
     *  StateNFT bundles + pieces group under full-width collection headers with the token icon. */
    private class NftGridAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        final List<Object> rows = new ArrayList<>();

        NftGridAdapter() {
            // plain products first, then each collection: header + bundle + pieces in edition order
            for (Catalog.Product p : catalog.products) if (!p.isStatePiece() && !p.isBundle()) rows.add(p);
            LinkedHashMap<String, List<Catalog.Product>> cols = new LinkedHashMap<>();
            for (Catalog.Product p : catalog.products) {
                if (!p.isStatePiece() && !p.isBundle()) continue;
                List<Catalog.Product> list = cols.get(p.nftTokenId);
                if (list == null) { list = new ArrayList<>(); cols.put(p.nftTokenId, list); }
                list.add(p);
            }
            for (Map.Entry<String, List<Catalog.Product>> e : cols.entrySet()) {
                List<Catalog.Product> items = e.getValue();
                items.sort((a, b) -> {
                    if (a.isBundle() != b.isBundle()) return a.isBundle() ? -1 : 1;   // bundle first
                    return Integer.compare(a.nftStateIdx, b.nftStateIdx);
                });
                Catalog.Product first = items.get(0);
                String name = !first.nftCollection.isEmpty() ? first.nftCollection : "Collection";
                Catalog.CollectionInfo ci = catalog.nftCollections.get(e.getKey());
                if (ci != null && !ci.name.isEmpty()) name = ci.name;
                int pieceCount = 0;
                for (Catalog.Product p : items) pieceCount += p.isBundle() ? Math.max(1, p.nftPieces) : 1;
                rows.add(new ColHeader(e.getKey(), name.toUpperCase() + " · " + pieceCount + (pieceCount == 1 ? " PIECE" : " PIECES")));
                rows.addAll(items);
            }
        }

        @Override public int getItemViewType(int pos) { return rows.get(pos) instanceof ColHeader ? 1 : 0; }

        @NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            if (type == 1) {
                LinearLayout row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(10), dp(16), dp(10), dp(4));
                row.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                return new RecyclerView.ViewHolder(row) {};
            }
            LinearLayout card = new LinearLayout(MainActivity.this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerView.ViewHolder(card) {};
        }
        @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int pos) {
            if (rows.get(pos) instanceof ColHeader) {
                final ColHeader hd = (ColHeader) rows.get(pos);
                LinearLayout row = (LinearLayout) h.itemView; row.removeAllViews();
                Catalog.CollectionInfo ci = catalog.nftCollections.get(hd.tokenid);
                if (ci != null && !ci.icon.isEmpty()) {
                    final ImageView ic = new ImageView(MainActivity.this);
                    ic.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    ic.setBackground(Design.roundBg(MainActivity.this, Design.SURFACE2, 7));
                    ic.setClipToOutline(true);
                    LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(26), dp(26));
                    ilp.rightMargin = dp(8); ic.setLayoutParams(ilp);
                    row.addView(ic);
                    Bitmap cb = imgCache.get("colicon:" + hd.tokenid);
                    if (cb != null) ic.setImageBitmap(cb);
                    else {
                        ic.setTag(hd.tokenid);
                        final String iconB64 = ci.icon;
                        io.execute(() -> {
                            Bitmap b = decodeSampled(iconB64, dp(52));
                            if (b != null) { imgCache.put("colicon:" + hd.tokenid, b); ui.post(() -> { if (hd.tokenid.equals(ic.getTag())) ic.setImageBitmap(b); }); }
                        });
                    }
                }
                TextView t = new TextView(MainActivity.this);
                t.setText(hd.label);
                t.setTextColor(Design.DIM2); t.setTextSize(11f); t.setTypeface(null, Typeface.BOLD);
                row.addView(t);
                return;
            }
            final Catalog.Product p = (Catalog.Product) rows.get(pos);
            LinearLayout card = (LinearLayout) h.itemView; card.removeAllViews();
            LinearLayout inner = new LinearLayout(MainActivity.this);
            inner.setOrientation(LinearLayout.VERTICAL);
            inner.setBackground(Design.roundBg(MainActivity.this, Design.SURFACE, 12));
            inner.setPadding(dp(6), dp(6), dp(6), dp(10));
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            ilp.setMargins(dp(6), dp(6), dp(6), dp(6));
            inner.setLayoutParams(ilp);

            final ImageView art = new ImageView(MainActivity.this) {
                @Override protected void onMeasure(int w, int hh) { super.onMeasure(w, w); }   // square
            };
            art.setScaleType(ImageView.ScaleType.CENTER_CROP);
            art.setBackground(Design.roundBg(MainActivity.this, Design.SURFACE2, 10));
            art.setClipToOutline(true);
            inner.addView(art, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            if (p.image != null && !p.image.isEmpty()) {
                Bitmap c = imgCache.get(p.id);
                if (c != null) art.setImageBitmap(c);
                else {
                    art.setTag(p.id);
                    io.execute(() -> {
                        Bitmap b = decodeSampled(p.image, dp(220));
                        if (b != null) { imgCache.put(p.id, b); ui.post(() -> { if (p.id.equals(art.getTag())) art.setImageBitmap(b); }); }
                    });
                }
            }

            TextView nm = new TextView(MainActivity.this);
            nm.setText(p.name); nm.setTextColor(Design.TEXT); nm.setTextSize(14f); nm.setTypeface(null, Typeface.BOLD);
            nm.setSingleLine(true); nm.setEllipsize(android.text.TextUtils.TruncateAt.END);
            nm.setPadding(dp(4), dp(8), dp(4), 0);
            inner.addView(nm);

            TextView ed = new TextView(MainActivity.this);
            ed.setText(editionChip(p)); ed.setTextColor(Design.DIM); ed.setTextSize(11.5f);
            ed.setPadding(dp(4), dp(2), dp(4), 0);
            inner.addView(ed);

            TextView pr = new TextView(MainActivity.this);
            pr.setText(p.price + " " + catalog.currency);
            pr.setTextColor(Design.ACCENT); pr.setTextSize(13f); pr.setTypeface(null, Typeface.BOLD);
            pr.setPadding(dp(4), dp(2), dp(4), 0);
            inner.addView(pr);

            inner.setOnClickListener(v -> push(buildProductDetail(p)));
            card.addView(inner);
        }
        @Override public int getItemCount() { return rows.size(); }
    }

    /** Edition scarcity chip: "◈ complete · N pieces" for a whole collection, "◈ #idx / N" for a
     *  StateNFT piece, "◈ 1 of 1" for a unique token, "◈ ×N eds" for the editions on sale. */
    private String editionChip(Catalog.Product p) {
        if (p.isBundle()) return p.nftPieces > 0 ? "◈ complete · " + p.nftPieces + " pieces" : "◈ complete collection";
        if (p.isStatePiece()) return p.nftSupply > 0 ? "◈ #" + p.nftStateIdx + " / " + p.nftSupply : "◈ #" + p.nftStateIdx;
        if (p.nftSupply == 1 || (p.nftSupply == 0 && p.maxUnits == 1)) return "◈ 1 of 1";
        return "◈ ×" + p.maxUnits + " eds";
    }

    private LinearLayout cartBarRef;
    private LinearLayout buildCartBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL); bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(Design.SURFACE); bar.setPadding(dp(16), dp(12), dp(16), dp(12));
        cartBarRef = bar;
        refreshCartBar();
        return bar;
    }

    private void refreshCartBar() {
        if (cartBarRef == null) return;
        cartBarRef.removeAllViews();
        int count = cartCount();
        TextView info = new TextView(this);
        info.setText(count == 0 ? "Cart empty" : count + " item" + (count == 1 ? "" : "s") + "  ·  " + cartTotal().toPlainString() + " " + catalog.currency);
        info.setTextColor(Design.TEXT); info.setTextSize(14f);
        cartBarRef.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView checkout = button("Checkout", count > 0);
        checkout.setEnabled(count > 0);
        checkout.setOnClickListener(v -> { if (count > 0) openCheckout(); });
        cartBarRef.addView(checkout);
    }

    private class ProductAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup p, int t) {
            LinearLayout row = new LinearLayout(MainActivity.this);
            row.setOrientation(LinearLayout.VERTICAL); row.setPadding(dp(16), dp(14), dp(16), dp(14));
            row.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerView.ViewHolder(row) {};
        }
        @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int pos) {
            Catalog.Product p = catalog.products.get(pos);
            LinearLayout row = (LinearLayout) h.itemView; row.removeAllViews();
            LinearLayout hwrap = new LinearLayout(MainActivity.this); hwrap.setOrientation(LinearLayout.HORIZONTAL);
            if (p.image != null && !p.image.isEmpty()) {
                final ImageView iv = new ImageView(MainActivity.this);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                iv.setBackground(Design.roundBg(MainActivity.this, Design.SURFACE2, 10)); iv.setClipToOutline(true);
                LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(66), dp(66)); ip.rightMargin = dp(12);
                iv.setLayoutParams(ip); hwrap.addView(iv);
                Bitmap c = imgCache.get(p.id);
                if (c != null) iv.setImageBitmap(c);
                else io.execute(() -> {                          // decode a small thumbnail OFF the UI thread
                    Bitmap b = decodeSampled(p.image, dp(140));
                    if (b != null) { imgCache.put(p.id, b); ui.post(() -> iv.setImageBitmap(b)); }
                });
            }
            LinearLayout content = new LinearLayout(MainActivity.this); content.setOrientation(LinearLayout.VERTICAL);
            TextView name = new TextView(MainActivity.this);
            name.setText(p.name); name.setTextColor(Design.TEXT); name.setTextSize(16f); name.setTypeface(null, Typeface.BOLD);
            content.addView(name);
            if (!p.description.isEmpty()) {
                TextView d = new TextView(MainActivity.this);
                d.setText(p.description); d.setTextColor(Design.DIM); d.setTextSize(13f); d.setPadding(0, dp(3), 0, 0);
                content.addView(d);
            }
            LinearLayout ctl = new LinearLayout(MainActivity.this); ctl.setOrientation(LinearLayout.HORIZONTAL);
            ctl.setGravity(Gravity.CENTER_VERTICAL); ctl.setPadding(0, dp(8), 0, 0);
            TextView price = new TextView(MainActivity.this);
            price.setText(p.price + " " + catalog.currency); price.setTextColor(Design.ACCENT); price.setTextSize(15f); price.setTypeface(null, Typeface.BOLD);
            ctl.addView(price, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            ctl.addView(stepper(p));
            content.addView(ctl);
            hwrap.addView(content, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(hwrap);
            row.setOnClickListener(v -> push(buildProductDetail(p)));   // tap row → product detail
        }
        @Override public int getItemCount() { return catalog.products.size(); }
    }

    private View stepper(final Catalog.Product p) {
        LinearLayout s = new LinearLayout(this); s.setOrientation(LinearLayout.HORIZONTAL); s.setGravity(Gravity.CENTER_VERTICAL);
        final TextView qty = new TextView(this);
        Runnable render = () -> qty.setText(String.valueOf(cart.getOrDefault(p.id, 0)));
        TextView minus = roundBtn("–"); TextView plus = roundBtn("+");
        qty.setTextColor(Design.TEXT); qty.setTextSize(16f); qty.setGravity(Gravity.CENTER);
        qty.setMinWidth(dp(36));
        minus.setOnClickListener(v -> { int q = cart.getOrDefault(p.id, 0); if (q > 0) { if (q - 1 == 0) cart.remove(p.id); else cart.put(p.id, q - 1); render.run(); refreshCartBar(); } });
        plus.setOnClickListener(v -> { int q = cart.getOrDefault(p.id, 0); if (q < p.maxUnits) { cart.put(p.id, q + 1); render.run(); refreshCartBar(); } else toast("Max " + p.maxUnits); });
        render.run();
        s.addView(minus); s.addView(qty); s.addView(plus);
        return s;
    }

    /** Decode a base64 image sampled down to ~reqPx on its longest side (caps memory + decode time). */
    private static Bitmap decodeSampled(String b64, int reqPx) {
        try {
            byte[] bytes = android.util.Base64.decode(b64, android.util.Base64.NO_WRAP);
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
            int sample = 1, dim = Math.max(o.outWidth, o.outHeight);
            while (reqPx > 0 && dim / sample > reqPx) sample *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o2);
        } catch (Exception e) { return null; }
    }

    private View buildProductDetail(final Catalog.Product p) {
        LinearLayout col = column();
        col.addView(header(p.name, true));
        ScrollView sv = new ScrollView(this);
        LinearLayout body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL); body.setPadding(dp(16), dp(12), dp(16), dp(24));
        sv.addView(body); col.addView(sv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        if (p.image != null && !p.image.isEmpty()) {
            final ImageView iv = new ImageView(this);
            iv.setAdjustViewBounds(true); iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setBackground(Design.roundBg(this, Design.SURFACE2, 14)); iv.setClipToOutline(true);
            iv.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(240)));
            iv.setOnClickListener(v -> showFullImage(p));
            body.addView(iv);
            TextView tap = new TextView(this); tap.setText("Tap photo to enlarge"); tap.setTextColor(Design.DIM2);
            tap.setTextSize(11f); tap.setGravity(Gravity.CENTER); tap.setPadding(0, dp(6), 0, 0); body.addView(tap);
            io.execute(() -> { Bitmap b = decodeSampled(p.image, 1200); if (b != null) ui.post(() -> iv.setImageBitmap(b)); });
        }

        // price + edition chip
        LinearLayout pr = new LinearLayout(this); pr.setOrientation(LinearLayout.HORIZONTAL); pr.setGravity(Gravity.CENTER_VERTICAL);
        pr.setPadding(0, dp(14), 0, dp(8));
        TextView price = new TextView(this);
        price.setText(p.price + " " + catalog.currency); price.setTextColor(Design.ACCENT); price.setTextSize(20f);
        price.setTypeface(null, Typeface.BOLD);
        pr.addView(price, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (p.isNft()) {
            TextView chip = new TextView(this);
            chip.setText(editionChip(p)); chip.setTextColor(Design.DIM); chip.setTextSize(13f);
            pr.addView(chip);
        }
        body.addView(pr);

        if (p.isNft()) {
            TextView nft = new TextView(this);
            nft.setText("⚡ Sent to your wallet automatically after payment");
            nft.setTextColor(Design.IN); nft.setTextSize(12.5f); nft.setPadding(0, 0, 0, dp(8));
            body.addView(nft);
        }

        if (!p.description.isEmpty()) {
            TextView d = new TextView(this); d.setText(p.description); d.setTextColor(Design.TEXT); d.setTextSize(15f);
            d.setLineSpacing(dp(3), 1f); body.addView(d);
        }

        // token provenance (NFT items)
        if (p.isNft()) {
            body.addView(sectionLabel("Token"));
            if (p.isBundle()) {
                body.addView(kv("Contents", "Complete collection — " + (p.nftPieces > 0 ? p.nftPieces + " pieces" : "every piece the seller holds")));
                if (!p.nftCollection.isEmpty()) body.addView(kv("Collection", p.nftCollection));
                TextView bd = new TextView(this);
                bd.setText("Each piece arrives in your wallet as its own on-chain transfer — they land one by one after payment.");
                bd.setTextColor(Design.DIM); bd.setTextSize(12.5f); bd.setPadding(0, dp(4), 0, dp(4)); bd.setLineSpacing(dp(2), 1f);
                body.addView(bd);
            } else if (p.isStatePiece()) {
                body.addView(kv("Edition", "#" + p.nftStateIdx + (p.nftSupply > 0 ? " of " + p.nftSupply : "")));
                if (!p.nftCollection.isEmpty()) body.addView(kv("Collection", p.nftCollection));
            }
            body.addView(copyRow("Token ID", shortId(p.nftTokenId), p.nftTokenId));
            if (p.nftSupply > 0) body.addView(kv("Supply", p.nftSupply + (p.nftTicker.isEmpty() ? "" : " · " + p.nftTicker)));
            else if (!p.nftTicker.isEmpty()) body.addView(kv("Ticker", p.nftTicker));
            final String ext = p.nftExternalUrl;
            if (ext != null && (ext.startsWith("http://") || ext.startsWith("https://"))) {
                LinearLayout lr = new LinearLayout(this); lr.setOrientation(LinearLayout.HORIZONTAL); lr.setPadding(0, dp(5), 0, dp(5));
                TextView kk = new TextView(this); kk.setText("Website"); kk.setTextColor(Design.DIM); kk.setTextSize(13f);
                kk.setLayoutParams(new LinearLayout.LayoutParams(dp(96), ViewGroup.LayoutParams.WRAP_CONTENT));
                TextView lv = new TextView(this); lv.setText(ext.replaceFirst("^https?://", "") + " ↗");
                lv.setTextColor(Design.ACCENT); lv.setTextSize(13f);
                lv.setOnClickListener(v -> { try { startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(ext))); } catch (Exception ignored) {} });
                lr.addView(kk); lr.addView(lv);
                body.addView(lr);
            }
        }

        // quantity: hidden for 1-of-1s (there's only one); stepper otherwise
        if (p.maxUnits > 1) {
            body.addView(sectionLabel("Quantity"));
            body.addView(stepper(p));
        }

        TextView checkout = button(p.isNft() ? "Buy · " + p.price + " " + catalog.currency : "Go to checkout", true);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.topMargin = dp(20); checkout.setLayoutParams(alp); checkout.setPadding(dp(16), dp(13), dp(16), dp(13));
        checkout.setOnClickListener(v -> {
            if (cart.getOrDefault(p.id, 0) == 0) {
                if (p.isNft()) { cart.put(p.id, 1); refreshCartBar(); }   // Buy = take one, straight to checkout
                else { toast("Add an item first (use + above)."); return; }
            }
            openCheckout();
        });
        body.addView(checkout);
        return col;
    }

    private void showFullImage(Catalog.Product p) {
        if (p.image == null || p.image.isEmpty()) return;
        final ImageView iv = new ImageView(this);
        iv.setAdjustViewBounds(true);
        ScrollView sv = new ScrollView(this); sv.addView(iv);
        new AlertDialog.Builder(this).setView(sv).setPositiveButton("Close", null).show();
        io.execute(() -> { Bitmap b = decodeSampled(p.image, 2048); if (b != null) ui.post(() -> iv.setImageBitmap(b)); });
    }

    private int cartCount() { int n = 0; for (int q : cart.values()) n += q; return n; }
    private BigDecimal cartTotal() {
        BigDecimal t = BigDecimal.ZERO;
        for (Catalog.Product p : catalog.products) {
            int q = cart.getOrDefault(p.id, 0); if (q <= 0) continue;
            try { t = t.add(new BigDecimal(p.price).multiply(BigDecimal.valueOf(q))); } catch (Exception ignored) {}
        }
        return t;
    }

    private BigDecimal shippingFee(String shipId) {
        for (Catalog.Shipping s : catalog.shipping) if (s.id.equals(shipId)) {
            try { return new BigDecimal(s.fee == null || s.fee.isEmpty() ? "0" : s.fee); } catch (Exception e) { return BigDecimal.ZERO; }
        }
        return BigDecimal.ZERO;
    }
    private BigDecimal grandTotal(String shipId) { return cartTotal().add(shippingFee(shipId)); }
    private String feeLabel(Catalog.Shipping sh) {
        try {
            BigDecimal f = new BigDecimal(sh.fee == null || sh.fee.isEmpty() ? "0" : sh.fee);
            return f.signum() > 0 ? "  +" + f.toPlainString() : "  · free";
        } catch (Exception e) { return ""; }
    }

    // ---- checkout ----
    private String chosenShipping = "";

    /** Any cart line that is an NFT / any that is physical — drives the checkout layout. */
    private boolean cartHasNft() {
        for (Catalog.Product p : catalog.products) if (cart.getOrDefault(p.id, 0) > 0 && p.isNft()) return true;
        return false;
    }
    private boolean cartHasPhysical() {
        for (Catalog.Product p : catalog.products) if (cart.getOrDefault(p.id, 0) > 0 && !p.isNft()) return true;
        return false;
    }

    /** The delivery-method picker only earns its place when there's a real choice with a real fee:
     *  hidden for NFT-only carts, empty shipping lists, and the single free digital compat row. */
    private boolean shippingPickerNeeded(boolean nftOnly) {
        if (nftOnly || catalog.shipping.isEmpty()) return false;
        if (catalog.shipping.size() == 1) {
            Catalog.Shipping s = catalog.shipping.get(0);
            boolean free; try { free = new BigDecimal(s.fee == null || s.fee.isEmpty() ? "0" : s.fee).signum() <= 0; } catch (Exception e) { free = true; }
            if ("digital".equals(s.id) && free) return false;
        }
        return true;
    }

    private void openCheckout() {
        if (crypto == null) { toast("Connect your node first."); return; }
        if (!catalog.configured()) { toast("This is a demo build — the vendor key is a placeholder."); return; }
        if (cartCount() == 0) { toast("Cart is empty."); return; }
        push(buildCheckout());
        // NFT delivery needs the buyer's wallet address; re-fetch if pairing hadn't landed it yet.
        if (cartHasNft() && buyerPayaddr.isEmpty()) ensurePayaddr();
    }

    /** Full-screen checkout (replaces the old dialog: validation no longer dismisses and eats input).
     *  NFT lines are delivered to the buyer's own wallet address — never typed, always shown. */
    private View buildCheckout() {
        final boolean nft = cartHasNft(), physical = cartHasPhysical();
        final boolean nftOnly = nft && !physical;

        LinearLayout col = column();
        col.setTag("checkout");
        col.addView(header("Checkout", true));

        ScrollView sv = new ScrollView(this);
        final LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL); body.setPadding(dp(16), dp(8), dp(16), dp(24));
        sv.addView(body); col.addView(sv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // order summary
        for (Catalog.Product p : catalog.products) {
            int q = cart.getOrDefault(p.id, 0); if (q <= 0) continue;
            LinearLayout li = new LinearLayout(this); li.setOrientation(LinearLayout.HORIZONTAL); li.setPadding(0, dp(3), 0, dp(3));
            TextView n = new TextView(this);
            n.setText(p.name + " × " + q + (p.isNft() ? "   ◈" : ""));
            n.setTextColor(Design.TEXT); n.setTextSize(14f);
            n.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            li.addView(n);
            TextView a = new TextView(this);
            try { a.setText(new BigDecimal(p.price).multiply(BigDecimal.valueOf(q)).toPlainString() + " " + catalog.currency); } catch (Exception e) { a.setText(""); }
            a.setTextColor(Design.DIM); a.setTextSize(14f);
            li.addView(a);
            body.addView(li);
        }

        // delivery method (physical shops with real options only)
        chosenShipping = nftOnly ? "digital" : (catalog.shipping.isEmpty() ? "digital" : catalog.shipping.get(0).id);
        final TextView tot = new TextView(this);
        tot.setTextColor(Design.TEXT); tot.setTextSize(14f); tot.setTypeface(null, Typeface.BOLD); tot.setPadding(0, dp(14), 0, 0); tot.setLineSpacing(dp(3), 1f);
        final TextView pay = button("", true);
        final Runnable updateTotal = () -> {
            BigDecimal items = cartTotal(), ship = shippingFee(chosenShipping), grand = items.add(ship);
            tot.setText((ship.signum() > 0
                    ? "Items   " + items.toPlainString() + " " + catalog.currency + "\nShipping   " + ship.toPlainString() + " " + catalog.currency + "\n" : "")
                    + "Total   " + grand.toPlainString() + " " + catalog.currency);
            pay.setText("Pay " + grand.toPlainString() + " " + catalog.currency);
        };
        if (shippingPickerNeeded(nftOnly)) {
            body.addView(sectionLabel("Delivery method"));
            final LinearLayout ships = new LinearLayout(this); ships.setOrientation(LinearLayout.VERTICAL);
            final Runnable[] redraw = new Runnable[1];
            redraw[0] = () -> {
                ships.removeAllViews();
                for (final Catalog.Shipping sh : catalog.shipping) {
                    TextView b = button(sh.label + feeLabel(sh), sh.id.equals(chosenShipping));
                    b.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                    ((LinearLayout.LayoutParams) b.getLayoutParams()).bottomMargin = dp(6);
                    b.setOnClickListener(v -> { chosenShipping = sh.id; redraw[0].run(); updateTotal.run(); });
                    ships.addView(b);
                }
            };
            redraw[0].run();
            body.addView(ships);
        }

        // NFT delivery — the buyer's own wallet, straight from their node
        if (nft) {
            body.addView(sectionLabel("Delivered to your wallet"));
            body.addView(buildWalletRow());
            TextView why = new TextView(this);
            why.setText("The NFT is sent on-chain to this address automatically once your payment confirms.");
            why.setTextColor(Design.DIM); why.setTextSize(12.5f); why.setPadding(0, dp(6), 0, 0); why.setLineSpacing(dp(2), 1f);
            body.addView(why);
        }

        // postal address only for physical items
        final EditText addr;
        if (physical) {
            body.addView(sectionLabel(nft ? "Delivery address (physical items)" : "Delivery address / email"));
            addr = input(nft ? "Where should the physical items go?" : "Where should this go? (or email for digital)");
            addr.setMinLines(2); body.addView(addr);
        } else addr = null;

        body.addView(sectionLabel("Message to seller (optional)"));
        final EditText note = input("Anything the vendor should know"); body.addView(note);

        updateTotal.run();
        body.addView(tot);

        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.topMargin = dp(18); pay.setLayoutParams(plp); pay.setPadding(dp(16), dp(13), dp(16), dp(13));
        pay.setOnClickListener(v -> {
            String a = addr == null ? "" : addr.getText().toString().trim();
            if (physical && a.isEmpty() && (nft || !"digital".equals(chosenShipping))) { toast("Add a delivery address for the physical items."); return; }
            if (!physical && !"digital".equals(chosenShipping) && a.isEmpty()) { toast("Add a delivery address."); return; }
            if (nft && buyerPayaddr.isEmpty()) { toast("Waiting for your wallet address — check your node."); ensurePayaddr(); return; }
            confirmAndPlace(nftOnly ? "digital" : chosenShipping, a, note.getText().toString().trim());
        });
        body.addView(pay);
        return col;
    }

    /** The buyer's own receiving address (delivery destination for NFTs), tap to copy. */
    private View buildWalletRow() {
        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(Design.roundBg(this, Design.SURFACE, 12)); row.setPadding(dp(14), dp(12), dp(14), dp(12));
        TextView a = new TextView(this);
        a.setText(buyerPayaddr.isEmpty() ? "Reading your wallet address…" : "◈ " + shortId(buyerPayaddr));
        a.setTextColor(buyerPayaddr.isEmpty() ? Design.DIM : Design.TEXT); a.setTextSize(14f); a.setTypeface(null, Typeface.BOLD);
        a.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(a);
        if (!buyerPayaddr.isEmpty()) {
            TextView cp = new TextView(this); cp.setText("📋"); cp.setTextSize(15f); cp.setPadding(dp(8), 0, 0, 0);
            row.addView(cp);
            row.setOnClickListener(v -> copy(buyerPayaddr));
        } else {
            TextView retry = button("Retry", false);
            retry.setOnClickListener(v -> ensurePayaddr());
            row.addView(retry);
        }
        return row;
    }

    /** Re-fetch the wallet address and refresh the checkout if it's the visible screen. */
    private void ensurePayaddr() {
        node.cmd("getaddress", new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                JSONObject r = j.optJSONObject("response");
                if (r != null) {
                    String a = r.optString("miniaddress", r.optString("address", ""));
                    if (!a.isEmpty()) buyerPayaddr = a;
                }
                refreshCheckoutIfTop();
            }
            @Override public void onError(String m) {
                toast("Couldn't read your wallet address — NFT delivery needs it. Check your node and retry.");
                refreshCheckoutIfTop();
            }
        });
    }
    private void refreshCheckoutIfTop() {
        View top = stack.peek();
        if (top != null && "checkout".equals(top.getTag())) refreshTop(buildCheckout());
    }

    private void confirmAndPlace(final String shipId, final String delivery, final String note) {
        final String total = grandTotal(shipId).toPlainString();
        new AlertDialog.Builder(this)
                .setTitle("Pay " + total + " " + catalog.currency + "?")
                .setMessage("This sends your encrypted order to " + catalog.shopName + " and pays " + total + " " + catalog.currency + ". Payment is final.")
                .setPositiveButton("Pay", (d, w) -> placeOrder(shipId, delivery, note))
                .setNegativeButton("Back", null).show();
    }

    private void placeOrder(String shipId, String delivery, String note) {
        final JSONArray items = new JSONArray();
        final StringBuilder summary = new StringBuilder();
        BigDecimal total = BigDecimal.ZERO;
        try {
            for (Catalog.Product p : catalog.products) {
                int q = cart.getOrDefault(p.id, 0); if (q <= 0) continue;
                BigDecimal line = new BigDecimal(p.price).multiply(BigDecimal.valueOf(q));
                total = total.add(line);
                JSONObject it = new JSONObject();
                it.put("product", p.name); it.put("size", ""); it.put("quantity", q);
                it.put("unitPrice", p.price); it.put("lineTotal", line.toPlainString());
                // NFT listings carry the tokenid so the vendor's Inbox can send the NFT on-chain.
                if (p.nftTokenId != null && !p.nftTokenId.isEmpty()) it.put("nftTokenId", p.nftTokenId);
                // StateNFT piece: the edition index identifies the exact coin the Inbox must transfer
                if (p.nftStateIdx > 0) it.put("nftStateIdx", p.nftStateIdx);
                // Complete collection: the Inbox transfers every held piece, one txn each
                if (p.nftBundle > 0) it.put("nftBundle", 1);
                items.put(it);
                if (summary.length() > 0) summary.append(", ");
                summary.append(p.name).append(" x").append(q);
            }
        } catch (Exception e) { toast("Order build error."); return; }
        if (items.length() == 0) { toast("Cart is empty."); return; }
        total = total.add(shippingFee(shipId));   // grand total = items + shipping

        final String ref = "ORD-" + MailText.randomId().substring(0, 8).toUpperCase();
        final String totalStr = total.toPlainString();
        final MerchMessage m = new MerchMessage();
        m.type = MerchMessage.ORDER; m.ref = ref; m.randomid = MailText.randomId();
        m.from = myId; m.to = catalog.vendorPublicId; m.date = System.currentTimeMillis();
        m.items = items.toString(); m.product = summary.toString();
        m.amount = totalStr; m.currency = catalog.currency; m.tokenid = catalog.tokenid;
        m.shipping = shipId; m.delivery = delivery; m.message = note; m.buyerPayaddr = buyerPayaddr;
        m.shopId = catalog.shopId; m.shopName = catalog.shopName;

        final AlertDialog progress = new AlertDialog.Builder(this)
                .setTitle("Placing order").setMessage("Sending your order and payment…").setCancelable(false).create();
        progress.show();

        io.execute(() -> {
            db.upsertOrder(m, "buyer", "");
            db.setMeta("vendoraddr:" + ref, catalog.vendorAddress);   // so Retry payment works without the live catalog
            CommsTransport.sendMessage(node, crypto, catalog.vendorPublicId, m.toWire(), new CommsTransport.SendCb() {
                @Override public void onSent(String txid) {
                    CommsTransport.sendPayment(node, catalog.vendorAddress, totalStr, catalog.tokenid, ref, new CommsTransport.SendCb() {
                        @Override public void onSent(String ptx) {
                            ui.post(() -> { dismiss(progress); cart.clear(); pop(); toast("Order placed!"); showMyOrders(); });
                        }
                        @Override public void onFailed(String e) {
                            db.setMeta("unpaid:" + ref, "1");   // flag so the order screen offers Retry payment
                            ui.post(() -> { dismiss(progress); cart.clear(); pop(); toast("Order sent, but payment failed — open the order to retry."); showMyOrders(); });
                        }
                    });
                }
                @Override public void onFailed(String e) { ui.post(() -> { dismiss(progress); toast("Order failed: " + e); }); }
            });
        });
    }

    private void retryPayment(final MerchDb.Order o) {
        // Resolve the vendor address WITHOUT the live catalog (null when reached from Home → My Orders):
        // per-order meta first, then the stored shop, then the open catalog.
        String vaddr = db.getMeta("vendoraddr:" + o.ref, "");
        if (vaddr.isEmpty() && o.shopId != null && !o.shopId.isEmpty()) {
            Catalog c = store.get(o.shopId);
            if (c != null) vaddr = c.vendorAddress;
        }
        if (vaddr.isEmpty() && catalog != null) vaddr = catalog.vendorAddress;
        if (vaddr.isEmpty()) { toast("Can't retry from here — open the shop and re-order."); return; }
        final AlertDialog progress = new AlertDialog.Builder(this)
                .setTitle("Retrying payment").setMessage("Sending " + o.amount + " " + o.currency + "…").setCancelable(false).create();
        progress.show();
        CommsTransport.sendPayment(node, vaddr, o.amount, o.tokenid, o.ref, new CommsTransport.SendCb() {
            @Override public void onSent(String txid) {
                io.execute(() -> db.setMeta("unpaid:" + o.ref, ""));
                ui.post(() -> { dismiss(progress); toast("Payment sent."); refreshTop(buildOrderDetail(o.ref)); });
            }
            @Override public void onFailed(String e) { ui.post(() -> { dismiss(progress); toast("Payment failed: " + e); }); }
        });
    }

    // ---- my orders ----
    private void showMyOrders() { push(buildMyOrders()); }

    private View buildMyOrders() {
        LinearLayout col = column(); col.setTag("orders");
        col.addView(header("My orders", true));
        RecyclerView rv = new RecyclerView(this);
        rv.setLayoutManager(new LinearLayoutManager(this));
        final OrderAdapter adapter = new OrderAdapter(new ArrayList<>());
        rv.setAdapter(adapter);
        col.addView(rv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        io.execute(() -> { List<MerchDb.Order> os = db.orders(); ui.post(() -> { adapter.data.clear(); adapter.data.addAll(os); adapter.notifyDataSetChanged(); }); });
        return col;
    }

    private class OrderAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        final List<MerchDb.Order> data;
        OrderAdapter(List<MerchDb.Order> d) { data = d; }
        @NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup p, int t) {
            LinearLayout row = new LinearLayout(MainActivity.this); row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(16), dp(12), dp(16), dp(12));
            row.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerView.ViewHolder(row) {};
        }
        @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int pos) {
            MerchDb.Order o = data.get(pos);
            LinearLayout row = (LinearLayout) h.itemView; row.removeAllViews();
            LinearLayout top = new LinearLayout(MainActivity.this); top.setOrientation(LinearLayout.HORIZONTAL); top.setGravity(Gravity.CENTER_VERTICAL);
            TextView t = new TextView(MainActivity.this);
            t.setText(o.product.isEmpty() ? o.ref : o.product); t.setTextColor(Design.TEXT); t.setTextSize(15f); t.setTypeface(null, Typeface.BOLD);
            t.setMaxLines(1); t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            top.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            top.addView(Design.pill(MainActivity.this, o.status, statusColor(o.status), Design.ON_ACCENT));
            row.addView(top);
            TextView sub = new TextView(MainActivity.this);
            sub.setText(o.amount + " " + o.currency + "  ·  " + timeAgo(o.date));
            sub.setTextColor(Design.DIM); sub.setTextSize(12f); sub.setPadding(0, dp(3), 0, 0);
            row.addView(sub);
            row.setOnClickListener(v -> push(buildOrderDetail(o.ref)));
        }
        @Override public int getItemCount() { return data.size(); }
    }

    private View buildOrderDetail(String ref) {
        MerchDb.Order o = db.order(ref);
        LinearLayout col = column();
        col.setTag("order:" + ref);
        col.addView(header(o == null ? "Order" : (o.product.isEmpty() ? o.ref : o.product), true));
        ScrollView sv = new ScrollView(this);
        LinearLayout body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL); body.setPadding(dp(16), dp(8), dp(16), dp(24));
        sv.addView(body); col.addView(sv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        if (o == null) { body.addView(card("Order not found.")); return col; }

        final boolean inquiry = MerchDb.INQUIRY.equals(o.status) || o.ref.startsWith("INQ-");
        final List<String[]> nfts = inquiry ? new ArrayList<>() : nftLines(o);
        final boolean badStatus = MerchDb.UNDERPAID.equals(o.status) || MerchDb.WRONG_TOKEN.equals(o.status);

        if (!nfts.isEmpty() && !badStatus) body.addView(buildTimeline(o.status));   // Placed → Paid → Delivered
        else body.addView(Design.pill(this, inquiry ? "ENQUIRY" : o.status, statusColor(o.status), Design.ON_ACCENT));

        if (!inquiry) {
            body.addView(sectionLabel("Order"));
            body.addView(card(o.product.isEmpty() ? o.ref : o.product));
            body.addView(kv("Total", o.amount + " " + o.currency));
            body.addView(kv("Reference", o.ref));
            if (nfts.isEmpty()) {
                body.addView(kv("Delivery", o.shipping));
                if (!o.delivery.isEmpty()) body.addView(kv("To", o.delivery));
            } else {
                for (String[] line : nfts) {
                    body.addView(copyRow(line[0] + " ×" + line[2], "NFT · " + shortId(line[1]), line[1]));
                }
                if (o.payaddr != null && !o.payaddr.isEmpty())
                    body.addView(copyRow("Delivered to", shortId(o.payaddr), o.payaddr));
                if (!o.delivery.isEmpty()) body.addView(kv("To", o.delivery));   // mixed order: physical address
            }
            if ("1".equals(db.getMeta("unpaid:" + o.ref, ""))) {
                TextView retry = button("Retry payment", true);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rlp.topMargin = dp(14); retry.setLayoutParams(rlp); retry.setPadding(dp(16), dp(12), dp(16), dp(12));
                retry.setOnClickListener(v -> retryPayment(o));
                body.addView(retry);
            }
        }

        body.addView(sectionLabel(inquiry ? "Conversation" : "Messages with the vendor"));
        List<String[]> chat = db.chat(o.ref);
        if (chat.isEmpty()) { TextView none = new TextView(this); none.setText("No messages yet — say hello below."); none.setTextColor(Design.DIM2); none.setTextSize(13f); body.addView(none); }
        for (String[] c : chat) {
            boolean incoming = "1".equals(c[0]);
            TextView t = new TextView(this);
            t.setText((incoming ? "Vendor: " : "You: ") + c[1]);
            t.setTextColor(incoming ? Design.TEXT : Design.DIM); t.setTextSize(14f); t.setPadding(0, dp(3), 0, dp(3));
            body.addView(t);
        }

        col.addView(composeBar(o));   // fixed bottom: message the vendor
        return col;
    }

    private View composeBar(final MerchDb.Order o) {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL); bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(Design.SURFACE); bar.setPadding(dp(10), dp(8), dp(10), dp(8));
        final EditText box = input("Message the vendor…");
        box.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView send = button("Send", true);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.leftMargin = dp(8); send.setLayoutParams(slp);
        send.setOnClickListener(v -> {
            String t = box.getText().toString().trim();
            if (t.isEmpty()) return;
            box.setText("");
            sendBuyerReply(o, t);
        });
        bar.addView(box); bar.addView(send);
        return bar;
    }

    /** Open (and lazily create) the general, non-order conversation with the vendor. */
    private void openGeneralThread() {
        if (crypto == null) { toast("Connect your node first."); return; }
        if (!catalog.configured()) { toast("Demo build — the vendor key is a placeholder."); return; }
        final String ref = "INQ-" + myId.substring(2, Math.min(18, myId.length()));   // 16 hex = 64-bit buyer tag
        io.execute(() -> {
            if (db.order(ref) == null) {
                MerchMessage seed = new MerchMessage();
                seed.type = MerchMessage.INQUIRY; seed.ref = ref;
                seed.from = myId; seed.to = catalog.vendorPublicId; seed.date = System.currentTimeMillis();
                seed.product = "Messages with " + catalog.shopName;
                seed.currency = catalog.currency; seed.tokenid = catalog.tokenid; seed.buyerPayaddr = buyerPayaddr;
                seed.shopId = catalog.shopId; seed.shopName = catalog.shopName;
                db.upsertOrder(seed, "buyer", "");   // local-only until the first message is sent
            }
            ui.post(() -> push(buildOrderDetail(ref)));
        });
    }

    private void sendBuyerReply(final MerchDb.Order o, final String text) {
        if (crypto == null) { toast("Connect your node first."); return; }
        final boolean inquiry = MerchDb.INQUIRY.equals(o.status) || o.ref.startsWith("INQ-");
        final String rid = MailText.randomId();
        io.execute(() -> {
            db.insertChat(o.ref, false, text, rid, System.currentTimeMillis());
            MerchMessage m = new MerchMessage();
            m.type = inquiry ? MerchMessage.INQUIRY : MerchMessage.BUYER_REPLY;
            m.ref = o.ref; m.message = text;
            m.from = myId; m.to = o.counterparty; m.randomid = rid; m.date = System.currentTimeMillis();
            if (inquiry) { m.product = "General enquiry"; m.buyerPayaddr = buyerPayaddr; m.shopId = catalog.shopId; m.shopName = catalog.shopName; }
            CommsTransport.sendMessage(node, crypto, o.counterparty, m.toWire(), new CommsTransport.SendCb() {
                @Override public void onSent(String txid) {}
                @Override public void onFailed(String e) { ui.post(() -> toast("Send failed: " + e)); }
            });
            ui.post(() -> refreshTop(buildOrderDetail(o.ref)));
        });
    }

    // ---- NFT order helpers ----

    /** Order lines carrying an NFT tokenid, as {name, tokenid, quantity}. Empty on plain orders. */
    private List<String[]> nftLines(MerchDb.Order o) {
        List<String[]> out = new ArrayList<>();
        if (o == null || o.items == null || o.items.isEmpty()) return out;
        try {
            JSONArray a = new JSONArray(o.items);
            for (int i = 0; i < a.length(); i++) {
                JSONObject it = a.optJSONObject(i); if (it == null) continue;
                String tokenid = it.optString("nftTokenId", "");
                if (tokenid.isEmpty()) continue;
                int q = Math.max(1, it.optInt("quantity", 1));
                out.add(new String[]{it.optString("product", "NFT"), tokenid, String.valueOf(q)});
            }
        } catch (Exception ignored) {}
        return out;
    }

    /** Placed → Paid → Delivered progress line for NFT orders. SHIPPED means the NFT left the
     *  vendor's wallet — shown to the buyer as Delivered. */
    private View buildTimeline(String status) {
        int stage = 1;
        if (MerchDb.PAID.equals(status) || MerchDb.CONFIRMED.equals(status)) stage = 2;
        else if (MerchDb.SHIPPED.equals(status) || MerchDb.DELIVERED.equals(status)) stage = 3;

        LinearLayout wrap = new LinearLayout(this); wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(0, dp(4), 0, dp(6));
        LinearLayout dots = new LinearLayout(this); dots.setOrientation(LinearLayout.HORIZONTAL); dots.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout labels = new LinearLayout(this); labels.setOrientation(LinearLayout.HORIZONTAL);
        String[] names = {"Placed", "Paid", "Delivered"};
        for (int i = 1; i <= 3; i++) {
            boolean done = i <= stage;
            TextView dot = new TextView(this);
            dot.setText(done ? "●" : "◌"); dot.setTextSize(15f);
            dot.setTextColor(done ? (i == 3 ? Design.IN : Design.ACCENT) : Design.DIM2);
            dots.addView(dot);
            if (i < 3) {
                View line = new View(this);
                line.setBackgroundColor(i < stage ? Design.ACCENT : Design.SURFACE2);
                LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(0, dp(2), 1f);
                ll.leftMargin = dp(6); ll.rightMargin = dp(6);
                line.setLayoutParams(ll);
                dots.addView(line);
            }
            TextView lab = new TextView(this);
            lab.setText(names[i - 1]); lab.setTextSize(11.5f);
            lab.setTextColor(done ? Design.TEXT : Design.DIM2);
            lab.setGravity(i == 1 ? Gravity.START : (i == 3 ? Gravity.END : Gravity.CENTER));
            labels.addView(lab, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        wrap.addView(dots);
        wrap.addView(labels);
        return wrap;
    }

    private static String shortId(String s) {
        if (s == null || s.length() < 14) return s == null ? "" : s;
        return s.substring(0, 8) + "…" + s.substring(s.length() - 4);
    }
    private void copy(String s) {
        ((android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE))
                .setPrimaryClip(android.content.ClipData.newPlainText("minimall", s));
        toast("Copied");
    }
    private View copyRow(String k, String shown, final String full) {
        LinearLayout r = new LinearLayout(this); r.setOrientation(LinearLayout.HORIZONTAL); r.setPadding(0, dp(5), 0, dp(5));
        r.setGravity(Gravity.CENTER_VERTICAL);
        TextView kk = new TextView(this); kk.setText(k); kk.setTextColor(Design.DIM); kk.setTextSize(13f);
        kk.setLayoutParams(new LinearLayout.LayoutParams(dp(96), ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView vv = new TextView(this); vv.setText(shown + "  📋"); vv.setTextColor(Design.TEXT); vv.setTextSize(13f);
        vv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        r.addView(kk); r.addView(vv);
        r.setOnClickListener(v -> copy(full));
        return r;
    }

    // ---- helpers ----
    private int statusColor(String s) {
        if (MerchDb.DELIVERED.equals(s) || MerchDb.SHIPPED.equals(s)) return Design.IN;
        if (MerchDb.PAID.equals(s) || MerchDb.CONFIRMED.equals(s)) return Design.ACCENT;
        if (MerchDb.UNDERPAID.equals(s) || MerchDb.WRONG_TOKEN.equals(s)) return Design.RED;
        return Design.DIM2;
    }
    private static String timeAgo(long ms) {
        if (ms <= 0) return "";
        long d = System.currentTimeMillis() - ms;
        if (d < 60_000) return "just now";
        if (d < 3_600_000) return (d / 60_000) + "m ago";
        if (d < 86_400_000) return (d / 3_600_000) + "h ago";
        return (d / 86_400_000) + "d ago";
    }

    private int dp(int v) { return Design.dp(this, v); }
    private LinearLayout column() {
        LinearLayout c = new LinearLayout(this); c.setOrientation(LinearLayout.VERTICAL); c.setBackgroundColor(Design.BG);
        c.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return c;
    }
    private LinearLayout header(String title, boolean back) {
        LinearLayout h = new LinearLayout(this); h.setOrientation(LinearLayout.HORIZONTAL); h.setGravity(Gravity.CENTER_VERTICAL);
        h.setBackgroundColor(Design.SURFACE); h.setPadding(dp(8), dp(10), dp(8), dp(10));
        if (back) h.addView(iconBtn("‹", this::pop));
        TextView t = new TextView(this); t.setText(title); t.setTextColor(Design.TEXT); t.setTextSize(18f); t.setTypeface(null, Typeface.BOLD);
        t.setPadding(dp(8), 0, 0, 0); t.setMaxLines(1); t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        h.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return h;
    }
    private TextView iconBtn(String glyph, Runnable onClick) {
        TextView b = new TextView(this); b.setText(glyph); b.setTextColor(Design.TEXT); b.setTextSize(20f);
        b.setGravity(Gravity.CENTER); b.setPadding(dp(10), dp(2), dp(10), dp(2)); b.setOnClickListener(v -> onClick.run());
        return b;
    }
    private TextView sectionLabel(String s) {
        TextView t = new TextView(this); t.setText(s.toUpperCase()); t.setTextColor(Design.DIM2); t.setTextSize(11f);
        t.setTypeface(null, Typeface.BOLD); t.setPadding(0, dp(16), 0, dp(6)); return t;
    }
    private TextView card(String s) {
        TextView t = new TextView(this); t.setText(s); t.setTextColor(Design.TEXT); t.setTextSize(14f);
        t.setBackground(Design.roundBg(this, Design.SURFACE, 12)); t.setPadding(dp(14), dp(12), dp(14), dp(12)); return t;
    }
    private View kv(String k, String v) {
        LinearLayout r = new LinearLayout(this); r.setOrientation(LinearLayout.HORIZONTAL); r.setPadding(0, dp(5), 0, dp(5));
        TextView kk = new TextView(this); kk.setText(k); kk.setTextColor(Design.DIM); kk.setTextSize(13f);
        kk.setLayoutParams(new LinearLayout.LayoutParams(dp(96), ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView vv = new TextView(this); vv.setText(v); vv.setTextColor(Design.TEXT); vv.setTextSize(13f);
        vv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        r.addView(kk); r.addView(vv); return r;
    }
    private TextView button(String text, boolean active) {
        TextView b = new TextView(this); b.setText(text); b.setTextSize(13f); b.setGravity(Gravity.CENTER);
        b.setTextColor(active ? Design.ON_ACCENT : Design.TEXT);
        b.setBackground(Design.roundBg(this, active ? Design.ACCENT : Design.SURFACE2, 10));
        b.setPadding(dp(16), dp(10), dp(16), dp(10)); return b;
    }
    private TextView roundBtn(String glyph) {
        TextView b = new TextView(this); b.setText(glyph); b.setTextColor(Design.TEXT); b.setTextSize(18f); b.setGravity(Gravity.CENTER);
        b.setBackground(Design.roundBg(this, Design.SURFACE2, 8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(38), dp(38)); lp.leftMargin = dp(4); lp.rightMargin = dp(4);
        b.setLayoutParams(lp); return b;
    }
    private EditText input(String hint) {
        EditText e = new EditText(this); e.setHint(hint); e.setHintTextColor(Design.DIM2); e.setTextColor(Design.TEXT); e.setTextSize(14f);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        e.setBackground(Design.roundBg(this, Design.SURFACE2, 10)); e.setPadding(dp(14), dp(10), dp(14), dp(10)); return e;
    }
    private ScrollView wrapScroll(View v) { ScrollView s = new ScrollView(this); s.addView(v); return s; }
    private View buildPairingBanner() {
        TextView t = new TextView(this); t.setText("Enable miniMall Shop in Minima Core → Apps to order.");
        t.setTextColor(Design.ON_ACCENT); t.setBackgroundColor(Design.ACCENT); t.setTextSize(13f); t.setPadding(dp(16), dp(10), dp(16), dp(10));
        return t;
    }
    private void dismiss(AlertDialog d) { try { if (d != null && d.isShowing()) d.dismiss(); } catch (Exception ignored) {} }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(new NotificationChannel(CH, "Orders", NotificationManager.IMPORTANCE_DEFAULT));
        }
    }
    private void requestNotifPermission() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, "android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED)
            ActivityCompat.requestPermissions(this, new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
    }
    private boolean nftDeliveredNotify = false;   // set by routeIncoming when an NFT order goes SHIPPED

    private void notifyNew(int n) {
        try {
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, "android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) return;
            boolean nftDelivered = nftDeliveredNotify; nftDeliveredNotify = false;
            androidx.core.app.NotificationCompat.Builder b = new androidx.core.app.NotificationCompat.Builder(this, CH)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(nftDelivered ? "NFT delivered" : "Order update")
                    .setContentText(nftDelivered ? "Your NFT was sent — check your wallet" : "Your order status changed")
                    .setAutoCancel(true);
            NotificationManagerCompat.from(this).notify(3, b.build());
        } catch (Exception ignored) {}
    }
}
