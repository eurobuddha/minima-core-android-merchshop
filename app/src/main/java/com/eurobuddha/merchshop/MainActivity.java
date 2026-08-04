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
            if (db.order(m.ref) == null) return false;
            db.setStatus(m.ref, m.status);
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
        LinearLayout col = column();
        LinearLayout head = header(catalog.shopName, true);    // back → My Shops
        head.addView(iconBtn("✉", this::openGeneralThread));   // message the vendor (pre-sales)
        col.addView(head);

        TextView ver = new TextView(this);
        ver.setText("Pays in " + catalog.currency);
        ver.setTextColor(Design.DIM2); ver.setTextSize(11f); ver.setPadding(dp(16), dp(2), dp(16), dp(6));
        col.addView(ver);

        if (crypto == null) {
            TextView s = new TextView(this);
            s.setText(paired ? "Connecting to your node…" : "Enable miniMall Shop in Minima Core → Apps to order.");
            s.setTextColor(Design.DIM); s.setTextSize(13f); s.setPadding(dp(16), dp(8), dp(16), dp(8));
            col.addView(s);
        }

        RecyclerView rv = new RecyclerView(this);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(new ProductAdapter());
        col.addView(rv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        col.addView(buildCartBar());
        return col;
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

        TextView price = new TextView(this);
        price.setText(p.price + " " + catalog.currency); price.setTextColor(Design.ACCENT); price.setTextSize(20f);
        price.setTypeface(null, Typeface.BOLD); price.setPadding(0, dp(14), 0, dp(8)); body.addView(price);

        if (p.nftTokenId != null && !p.nftTokenId.isEmpty()) {
            TextView nft = new TextView(this);
            nft.setText("NFT · delivered on-chain after payment");
            nft.setTextColor(Design.DIM); nft.setTextSize(12f); nft.setPadding(0, 0, 0, dp(8));
            body.addView(nft);
        }

        if (!p.description.isEmpty()) {
            TextView d = new TextView(this); d.setText(p.description); d.setTextColor(Design.TEXT); d.setTextSize(15f);
            d.setLineSpacing(dp(3), 1f); body.addView(d);
        }

        body.addView(sectionLabel("Quantity"));
        body.addView(stepper(p));

        TextView checkout = button("Go to checkout", true);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.topMargin = dp(20); checkout.setLayoutParams(alp); checkout.setPadding(dp(16), dp(13), dp(16), dp(13));
        checkout.setOnClickListener(v -> { if (cartCount() > 0) openCheckout(); else toast("Add an item first (use + above)."); });
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
    private void openCheckout() {
        if (crypto == null) { toast("Connect your node first."); return; }
        if (!catalog.configured()) { toast("This is a demo build — the vendor key is a placeholder."); return; }
        LinearLayout col = new LinearLayout(this); col.setOrientation(LinearLayout.VERTICAL); col.setPadding(dp(20), dp(8), dp(20), dp(8));

        col.addView(sectionLabel("Delivery method"));
        chosenShipping = catalog.shipping.isEmpty() ? "digital" : catalog.shipping.get(0).id;
        final LinearLayout ships = new LinearLayout(this); ships.setOrientation(LinearLayout.VERTICAL);
        final TextView tot = new TextView(this);
        tot.setTextColor(Design.TEXT); tot.setTextSize(14f); tot.setTypeface(null, Typeface.BOLD); tot.setPadding(0, dp(14), 0, 0); tot.setLineSpacing(dp(3), 1f);
        final Runnable updateTotal = () -> {
            BigDecimal items = cartTotal(), ship = shippingFee(chosenShipping), grand = items.add(ship);
            tot.setText("Items   " + items.toPlainString() + " " + catalog.currency
                    + "\nShipping   " + ship.toPlainString() + " " + catalog.currency
                    + "\nTotal   " + grand.toPlainString() + " " + catalog.currency);
        };
        final Runnable[] redraw = new Runnable[1];
        redraw[0] = () -> {
            ships.removeAllViews();
            for (final Catalog.Shipping sh : catalog.shipping) {
                TextView b = button(sh.label + feeLabel(sh), sh.id.equals(chosenShipping));
                b.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                ((LinearLayout.LayoutParams) b.getLayoutParams()).bottomMargin = dp(6);
                b.setOnClickListener(v -> { chosenShipping = sh.id; redraw[0].run(); });
                ships.addView(b);
            }
            updateTotal.run();
        };
        redraw[0].run();
        col.addView(ships);

        col.addView(sectionLabel("Delivery address / email"));
        final EditText addr = input("Where should this go? (or email for digital)");
        addr.setMinLines(2); col.addView(addr);

        col.addView(sectionLabel("Message (optional)"));
        final EditText note = input("Anything the vendor should know"); col.addView(note);

        col.addView(tot);

        new AlertDialog.Builder(this).setTitle("Checkout").setView(wrapScroll(col))
                .setPositiveButton("Place order & pay", (d, w) -> {
                    String a = addr.getText().toString().trim();
                    if (!"digital".equals(chosenShipping) && a.isEmpty()) { toast("Add a delivery address."); return; }
                    confirmAndPlace(chosenShipping, a, note.getText().toString().trim());
                })
                .setNegativeButton("Cancel", null).show();
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
            CommsTransport.sendMessage(node, crypto, catalog.vendorPublicId, m.toWire(), new CommsTransport.SendCb() {
                @Override public void onSent(String txid) {
                    CommsTransport.sendPayment(node, catalog.vendorAddress, totalStr, catalog.tokenid, ref, new CommsTransport.SendCb() {
                        @Override public void onSent(String ptx) {
                            ui.post(() -> { dismiss(progress); cart.clear(); toast("Order placed!"); showMyOrders(); });
                        }
                        @Override public void onFailed(String e) {
                            db.setMeta("unpaid:" + ref, "1");   // flag so the order screen offers Retry payment
                            ui.post(() -> { dismiss(progress); toast("Order sent, but payment failed — open the order to retry."); showMyOrders(); });
                        }
                    });
                }
                @Override public void onFailed(String e) { ui.post(() -> { dismiss(progress); toast("Order failed: " + e); }); }
            });
        });
    }

    private void retryPayment(final MerchDb.Order o) {
        final AlertDialog progress = new AlertDialog.Builder(this)
                .setTitle("Retrying payment").setMessage("Sending " + o.amount + " " + o.currency + "…").setCancelable(false).create();
        progress.show();
        CommsTransport.sendPayment(node, catalog.vendorAddress, o.amount, o.tokenid, o.ref, new CommsTransport.SendCb() {
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
        body.addView(Design.pill(this, inquiry ? "ENQUIRY" : o.status, statusColor(o.status), Design.ON_ACCENT));
        if (!inquiry) {
            body.addView(sectionLabel("Order"));
            body.addView(card(o.product.isEmpty() ? o.ref : o.product));
            body.addView(kv("Total", o.amount + " " + o.currency));
            body.addView(kv("Reference", o.ref));
            body.addView(kv("Delivery", o.shipping));
            if (!o.delivery.isEmpty()) body.addView(kv("To", o.delivery));
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
    private void notifyNew(int n) {
        try {
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, "android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) return;
            androidx.core.app.NotificationCompat.Builder b = new androidx.core.app.NotificationCompat.Builder(this, CH)
                    .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("Order update").setContentText("Your order status changed").setAutoCancel(true);
            NotificationManagerCompat.from(this).notify(3, b.build());
        } catch (Exception ignored) {}
    }
}
