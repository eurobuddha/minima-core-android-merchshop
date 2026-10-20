package com.eurobuddha.merchshop;

import android.content.ClipboardManager;
import android.content.Context;
import android.text.Editable;
import android.text.Spanned;
import android.view.*;
import android.widget.*;
import com.eurobuddha.comms.NodeApi;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Reuses MallShopLanguagesTest's checkout fixture and InboxReplyDraftTest's real Editable checks.
 * Builds UI only: MainActivity.onCreate, identity setup and the node are never started. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
public class CheckoutRefreshTest {
    MainActivity activity;NodeApi node;FrameLayout container;
    final List<NodeApi.Cb> callbacks=new ArrayList<>();
    static final String ADDRESS="0x"+"34".repeat(32),RAW="  中文 Русский Українська\n  unsent  ";
    @Before public void setup()throws Exception {
        activity=Robolectric.buildActivity(MainActivity.class).get();
        activity.setTheme(androidx.appcompat.R.style.Theme_AppCompat);
        container=new FrameLayout(activity);activity.getWindow().setContentView(container);set("container",container);
        node=mock(NodeApi.class);set("node",node);
        doAnswer(i->{assertEquals("getaddress",i.getArgument(0));callbacks.add(i.getArgument(1));return null;}).when(node).cmd(anyString(),any());
    }
    @After public void close()throws Exception {
        ((ExecutorService)get("io")).shutdownNow();
        ((android.os.Handler)get("ui")).removeCallbacksAndMessages(null);activity.finish();
    }
    Object get(String name)throws Exception{Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);return f.get(activity);}
    void set(String name,Object value)throws Exception{Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);f.set(activity,value);}
    Object call(String name)throws Exception{Method m=MainActivity.class.getDeclaredMethod(name);m.setAccessible(true);return m.invoke(activity);}
    @SuppressWarnings("unchecked") ArrayDeque<View> stack()throws Exception{return (ArrayDeque<View>)get("stack");}
    @SuppressWarnings("unchecked") Map<String,Integer> cart()throws Exception{return (Map<String,Integer>)get("cart");}
    static List<TextView> texts(View view){List<TextView> out=new ArrayList<>();if(view instanceof TextView)out.add((TextView)view);if(view instanceof ViewGroup){ViewGroup g=(ViewGroup)view;for(int i=0;i<g.getChildCount();i++)out.addAll(texts(g.getChildAt(i)));}return out;}
    static List<EditText> fields(View page){List<EditText> out=new ArrayList<>();for(TextView t:texts(page))if(t instanceof EditText)out.add((EditText)t);return out;}
    static TextView exact(View page,String text){return texts(page).stream().filter(t->text.equals(t.getText().toString())).findFirst().orElseThrow(()->new AssertionError(text));}
    View checkout(boolean nft,boolean physical)throws Exception {
        Catalog c=new Catalog();c.currency="USD";c.shopName="Public fixture";
        if(physical){Catalog.Product p=new Catalog.Product();p.id="physical";p.name="Parcel";p.price="0012.34000";c.products.add(p);cart().put(p.id,2);}
        if(nft){Catalog.Product p=new Catalog.Product();p.id="nft";p.name="Art";p.price="1.20000";p.nftTokenId="0x"+"56".repeat(32);c.products.add(p);cart().put(p.id,1);}
        for(String id:new String[]{"digital","postal"}){Catalog.Shipping s=new Catalog.Shipping();s.id=id;s.label=id.equals("postal")?"Postal":"Free";s.fee=id.equals("postal")?"1.20000":"0";c.shipping.add(s);}
        set("catalog",c);View page=(View)call("buildCheckout");stack().push(page);container.removeAllViews();container.addView(page);return page;
    }
    static JSONObject reply()throws Exception{return new JSONObject().put("status",true).put("response",new JSONObject().put("miniaddress",ADDRESS));}
    @Test public void repeatedDelayedAddressAndErrorsPreserveTheExactForm()throws Exception {
        View page=checkout(true,true);List<EditText> fields=fields(page);assertEquals(2,fields.size());
        EditText address=fields.get(0),note=fields.get(1);address.setText(RAW);address.setSelection(2,7);address.requestFocus();note.setText("Pay 中文");
        Editable text=address.getText();Object composing=new Object();text.setSpan(composing,2,4,Spanned.SPAN_COMPOSING);
        exact(page,"Postal  +1.20000").performClick();Map<String,Integer> cart=new LinkedHashMap<>(cart());Object catalog=get("catalog");
        for(int repeat=0;repeat<4;repeat++){
            call("ensurePayaddr");NodeApi.Cb callback=callbacks.remove(0);
            callback.onResult(reply());call("ensurePayaddr");callbacks.remove(0).onError("public failure");
            assertSame(page,stack().peek());assertSame(address,fields(page).get(0));assertSame(note,fields(page).get(1));
            assertSame(text,address.getText());assertEquals(RAW,address.getText().toString());assertEquals("Pay 中文",note.getText().toString());
            assertEquals(2,address.getSelectionStart());assertEquals(7,address.getSelectionEnd());assertEquals(2,text.getSpanStart(composing));assertEquals(4,text.getSpanEnd(composing));assertTrue(address.hasFocus());
            assertEquals("postal",get("chosenShipping"));assertEquals(cart,cart());assertSame(catalog,get("catalog"));assertEquals(ADDRESS,get("buyerPayaddr"));
            exact(page,"Pay 27.08000 USD");
            View wallet=page.findViewWithTag("checkoutWallet");assertNotNull(wallet);assertTrue(texts(wallet).get(0).getText().toString().startsWith("◈ "));
        }
        verify(node,times(8)).cmd(eq("getaddress"),any());verifyNoMoreInteractions(node);
    }
    @Test public void nftOnlyNoteAndWalletCopySurvivePairingAddressRefresh()throws Exception {
        View page=checkout(true,false);EditText note=fields(page).get(0);note.setText(RAW);note.setSelection(3);note.requestFocus();Editable text=note.getText();
        call("fetchBuyerAddr");callbacks.remove(0).onResult(reply());
        assertSame(page,stack().peek());assertSame(note,fields(page).get(0));assertSame(text,note.getText());assertEquals(3,note.getSelectionStart());assertEquals("digital",get("chosenShipping"));
        page.findViewWithTag("checkoutWallet").performClick();ClipboardManager clipboard=(ClipboardManager)activity.getSystemService(Context.CLIPBOARD_SERVICE);
        assertEquals(ADDRESS,clipboard.getPrimaryClip().getItemAt(0).getText().toString());verify(node).cmd(eq("getaddress"),any());verifyNoMoreInteractions(node);
    }
    @Test public void physicalOnlyCheckoutIsUnchangedByAnAddressResult()throws Exception {
        View page=checkout(false,true);EditText address=fields(page).get(0);address.setText(RAW);exact(page,"Postal  +1.20000").performClick();
        call("ensurePayaddr");callbacks.remove(0).onResult(reply());assertSame(page,stack().peek());assertSame(address,fields(page).get(0));assertEquals(RAW,address.getText().toString());assertEquals("postal",get("chosenShipping"));assertNull(page.findViewWithTag("checkoutWallet"));
    }
    @Test public void aResultCannotReplaceAnotherPageOrRefreshAClosingHost()throws Exception {
        View checkout=checkout(true,true);View wallet=checkout.findViewWithTag("checkoutWallet");call("ensurePayaddr");
        LinearLayout other=new LinearLayout(activity);other.setTag("orders");stack().push(other);callbacks.remove(0).onResult(reply());
        assertSame(other,stack().peek());assertSame(wallet,checkout.findViewWithTag("checkoutWallet"));
        stack().pop();activity.finish();call("refreshCheckoutIfTop");assertSame(checkout,stack().peek());assertSame(wallet,checkout.findViewWithTag("checkoutWallet"));
    }
    @Test public void connectionLabelTracksReadinessWithoutReplacingPagesOrDrafts()throws Exception {
        ShopStore store=mock(ShopStore.class);when(store.all()).thenReturn(Collections.emptyList());set("store",store);
        View home=(View)call("buildHome");stack().push(home);View draft=checkout(true,true);
        EditText address=fields(draft).get(0);address.setText(RAW);address.setSelection(2);Editable original=address.getText();
        View storefront=(View)call("buildStorefront");stack().push(storefront);
        TextView a=home.findViewWithTag("shopConnectionStatus"),b=storefront.findViewWithTag("shopConnectionStatus");assertNotNull(a);assertNotNull(b);
        for(int repeat=0;repeat<4;repeat++) {
            set("crypto",null);set("paired",false);call("refreshConnectionStatus");
            assertEquals(View.VISIBLE,a.getVisibility());assertEquals("Enable miniMall Shop in Minima Core → Apps to order.",a.getText().toString());
            set("paired",true);call("refreshConnectionStatus");assertEquals("Connecting to your node…",b.getText().toString());
            set("crypto",mock(com.eurobuddha.comms.CryptoProvider.class));call("refreshConnectionStatus");assertEquals(View.GONE,a.getVisibility());assertEquals(View.GONE,b.getVisibility());
            assertSame(storefront,stack().peek());assertSame(original,address.getText());assertEquals(RAW,address.getText().toString());assertEquals(2,address.getSelectionStart());
        }
        verifyNoInteractions(node);
    }
    @Test public void identityCompletionHidesStaleOfflineLabelAndDisconnectRevealsIt()throws Exception {
        ShopStore store=mock(ShopStore.class);when(store.all()).thenReturn(Collections.emptyList());set("store",store);set("paired",true);
        View home=(View)call("buildHome");stack().push(home);TextView status=home.findViewWithTag("shopConnectionStatus");assertEquals(View.VISIBLE,status.getVisibility());
        set("ls",mock(com.goterl.lazysodium.LazySodium.class));set("db",mock(com.eurobuddha.comms.MerchDb.class));
        Class<?> identity=com.eurobuddha.comms.CommsIdentity.class;
        Method method=MainActivity.class.getDeclaredMethod("adoptIdentity",identity);method.setAccessible(true);
        method.invoke(activity,com.eurobuddha.comms.CommsIdentity.fromKeys(new byte[32],new byte[32],new byte[32],new byte[64]));
        assertEquals(View.GONE,status.getVisibility());assertSame(home,stack().peek());
        set("pairingBanner",new View(activity));method=MainActivity.class.getDeclaredMethod("onPaired",boolean.class);method.setAccessible(true);method.invoke(activity,false);
        assertEquals(View.VISIBLE,status.getVisibility());assertSame(home,stack().peek());verifyNoInteractions(node);
    }
}
