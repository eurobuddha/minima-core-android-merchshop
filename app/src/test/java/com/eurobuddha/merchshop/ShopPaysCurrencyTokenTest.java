package com.eurobuddha.merchshop;

import android.app.Dialog;
import android.widget.FrameLayout;
import android.widget.TextView;
import com.eurobuddha.comms.MerchDb;
import com.eurobuddha.comms.MerchMessage;
import com.eurobuddha.comms.NodeApi;
import java.io.File;
import java.io.FileWriter;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowLooper;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** A .shop pays the token its CURRENCY names: one that says "Minima" and carries the MxUSD token id is refused on
 *  import, on open, before the "Pay …?" question, before the order is sent and on Retry payment; nothing reaches the node. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
public class ShopPaysCurrencyTokenTest {
    static final String MXUSD = "0x7D39745FBD29049BE29850B55A18BF550E4D442F930F86266E34193D89042A90";
    static final String VENDOR = "0x" + "12".repeat(64), PAYOUT = "Mx" + "Q".repeat(58);
    MainActivity activity; NodeApi node;

    @Before public void setup() throws Exception {
        activity = Robolectric.buildActivity(MainActivity.class).get();
        activity.setTheme(androidx.appcompat.R.style.Theme_AppCompat);
        activity.getWindow().setContentView(new FrameLayout(activity));
        node = mock(NodeApi.class); set("node", node);
        set("store", new ShopStore(activity));
    }
    @After public void close() throws Exception {
        ((ExecutorService) get("io")).shutdownNow();
        ((android.os.Handler) get("ui")).removeCallbacksAndMessages(null); activity.finish();
    }
    Object get(String n) throws Exception { Field f = MainActivity.class.getDeclaredField(n); f.setAccessible(true); return f.get(activity); }
    void set(String n, Object v) throws Exception { Field f = MainActivity.class.getDeclaredField(n); f.setAccessible(true); f.set(activity, v); }
    Object call(String n, Class<?>[] types, Object... args) throws Exception {
        Method m = MainActivity.class.getDeclaredMethod(n, types); m.setAccessible(true); return m.invoke(activity, args);
    }
    static String shop(String currency, String tokenid) throws Exception {
        JSONObject o = new JSONObject().put("shopId", "fixture").put("shopName", "Fixture shop").put("vendorPublicId", VENDOR)
                .put("vendorAddress", PAYOUT).put("currency", currency)
                .put("products", new JSONArray().put(new JSONObject().put("id", "one").put("name", "One").put("price", "1.5")));
        if (tokenid != null) o.put("tokenid", tokenid);
        return o.toString();
    }
    String lastDialogText() {
        Dialog d = ShadowDialog.getLatestDialog(); assertNotNull("a dialog was shown", d);
        TextView t = d.findViewById(android.R.id.message); return t == null ? "" : t.getText().toString();
    }
    @SuppressWarnings("unchecked") void openCart(String json) throws Exception {
        set("catalog", Catalog.fromJson(json)); ((Map<String, Integer>) get("cart")).put("one", 2);
    }

    @Test public void theCurrencyDecidesTheToken() throws Exception {
        assertEquals("0x00", Catalog.fromJson(shop("Minima", null)).payTokenid());
        assertEquals("0x00", Catalog.fromJson(shop("Minima", "0x00")).payTokenid());
        assertEquals(MXUSD, Catalog.fromJson(shop("USDT", null)).payTokenid());
        assertEquals(MXUSD, Catalog.fromJson(shop("mxUSDT", MXUSD.toLowerCase())).payTokenid());
        Catalog bad = Catalog.fromJson(shop("Minima", MXUSD));
        assertNull(bad.payTokenid());
        assertTrue(bad.tokenProblem(), bad.tokenProblem().contains(MXUSD));
        assertNotNull(Catalog.fromJson(shop("USDT", "0x00")).tokenProblem());
        assertNotNull(Catalog.fromJson(shop("BTC", null)).tokenProblem());
    }

    @Test public void importRefusesAShopWhoseTokenIsNotItsCurrencys() throws Exception {
        ShopStore store = (ShopStore) get("store");
        assertNull(store.importBundle(shop("Minima", MXUSD)));
        assertNull(store.get("fixture"));
        assertNotNull(store.importBundle(shop("Minima", null)));
    }

    @Test public void openRefusesAStoredShopWhoseTokenIsNotItsCurrencys() throws Exception {
        File dir = new File(activity.getFilesDir(), "shops"); dir.mkdirs();
        try (FileWriter w = new FileWriter(new File(dir, "fixture.json"))) { w.write(shop("Minima", MXUSD)); }   // stored before the fix
        call("openShop", new Class<?>[]{String.class}, "fixture");
        assertNull("the shop is not opened", get("catalog"));
        assertTrue(lastDialogText().contains(MXUSD));
    }

    @Test public void theQuestionIsNeverAskedAndNothingIsSent() throws Exception {
        openCart(shop("Minima", MXUSD));
        call("confirmAndPlace", new Class<?>[]{String.class, String.class, String.class}, "digital", "buyer@example.invalid", "");
        assertTrue(lastDialogText(), lastDialogText().startsWith("This shop says it is paid in Minima but names another token:\n" + MXUSD));
        call("placeOrder", new Class<?>[]{String.class, String.class, String.class, String.class}, "digital", "buyer@example.invalid", "", "3.0");
        ShadowLooper.idleMainLooper();
        verify(node, never()).cmd(anyString(), any());
    }

    @Test public void theQuestionNamesAmountTokenAndAddressWhole() throws Exception {
        openCart(shop("USDT", null));
        call("confirmAndPlace", new Class<?>[]{String.class, String.class, String.class}, "digital", "buyer@example.invalid", "");
        String text = lastDialogText();
        assertTrue(text, text.contains("pays 3.0 MxUSD"));
        assertTrue(text, text.contains("\nAmount sent\n3.0\n"));
        assertTrue(text, text.contains("\nToken: MxUSD\n" + MXUSD + "\n"));
        assertTrue(text, text.endsWith("\nVendor's payment address\n" + PAYOUT));
        verify(node, never()).cmd(anyString(), any());
    }

    static List<String> texts(android.view.View v) {
        List<String> out = new ArrayList<>();
        if (v instanceof TextView) out.add(((TextView) v).getText().toString());
        if (v instanceof android.view.ViewGroup) for (int i = 0; i < ((android.view.ViewGroup) v).getChildCount(); i++) out.addAll(texts(((android.view.ViewGroup) v).getChildAt(i)));
        return out;
    }

    @Test public void anMxUsdShopReadsMxUsdNeverTheWireValue() throws Exception {
        openCart(shop("USDT", null));
        String all = String.join("\n", texts((android.view.View) call("buildCheckout", new Class<?>[]{})));
        assertTrue(all, all.contains("3.0 MxUSD"));
        assertFalse(all, all.contains("USDT"));
    }

    @Test public void pricesAndFeesArePlainDecimals() throws Exception {
        for (String ok : new String[]{"1", "1.5", "0012.340000", ".5", "5.", "0"}) assertTrue(ok, Catalog.plain(ok));
        for (String bad : new String[]{"1e3", "+5", "-5", " 5", "5 ", "1,5", "", "0x10", "1.2.3", ".", "NaN", null}) assertFalse(String.valueOf(bad), Catalog.plain(bad));
        assertNull(((ShopStore) get("store")).importBundle(shop("Minima", null).replace("\"price\":\"1.5\"", "\"price\":\"1e3\"")));
        Catalog c = Catalog.fromJson(shop("USDT", null)); assertNull(c.problem());
        c.products.get(0).price = "-1"; assertTrue(c.problem(), c.problem().startsWith("The price of \"One\" is not a plain number (-1)."));
    }

    @Test public void aNonPlainFeeOrAZeroTotalNeverReachesTheQuestion() throws Exception {
        openCart(shop("Minima", null));
        Catalog.Shipping s = new Catalog.Shipping(); s.id = "postal"; s.label = "Postal"; s.fee = "1E2";
        ((Catalog) get("catalog")).shipping.add(s);
        call("confirmAndPlace", new Class<?>[]{String.class, String.class, String.class}, "postal", "buyer@example.invalid", "");
        assertTrue(lastDialogText(), lastDialogText().startsWith("The fee of the delivery method \"Postal\" is not a plain number (1E2)."));
        openCart(shop("Minima", null)); ((Catalog) get("catalog")).products.get(0).price = "0";
        call("confirmAndPlace", new Class<?>[]{String.class, String.class, String.class}, "digital", "buyer@example.invalid", "");
        assertEquals("The total is zero. Nothing was sent.", lastDialogText());
        verify(node, never()).cmd(anyString(), any());
    }

    @Test public void theWalletRowShowsTheWholeAddress() throws Exception {
        String mine = "Mx" + "Z".repeat(58);
        set("buyerPayaddr", mine);
        TextView first = (TextView) ((android.view.ViewGroup) call("buildWalletRow", new Class<?>[]{})).getChildAt(0);
        assertEquals("◈ " + mine, first.getText().toString());
    }

    @Test public void retryPaymentRefusesAnOrderWhoseTokenIsNotItsCurrencys() throws Exception {
        MerchDb.Order o = new MerchDb.Order();
        o.ref = "ORD-FIXTURE"; o.amount = "3.0"; o.currency = "Minima"; o.tokenid = MXUSD; o.shopId = "fixture";
        call("retryPayment", new Class<?>[]{MerchDb.Order.class}, o);
        assertTrue(lastDialogText().contains(MXUSD));
        verify(node, never()).cmd(anyString(), any());
        assertEquals(MerchMessage.MXUSD_TOKEN, MXUSD);
    }
}
