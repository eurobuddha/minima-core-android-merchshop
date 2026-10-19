package com.eurobuddha.merchshop;

import android.content.Context;
import java.io.File;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class ShopStoreIsolationTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private ShopStore store() {
        Context context = mock(Context.class);
        when(context.getFilesDir()).thenReturn(temporary.getRoot());
        return new ShopStore(context);
    }
    private String bundle(String id) throws Exception {
        return new JSONObject().put("shopId",id).put("shopName","Disposable shop")
            .put("vendorPublicId","0x" + "12".repeat(64))
            .put("products",new JSONArray().put(new JSONObject().put("id","one").put("price","1"))).toString();
    }
    @Test public void normalShopRoundTripUsesItsExistingFolder() throws Exception {
        ShopStore store = store();
        assertNotNull(store.importBundle(bundle("normal")));
        assertTrue(new File(temporary.getRoot(),"shops/normal.json").isFile());
        assertFalse(new File(temporary.getRoot(),"pandamonium_mall_shops").exists());
        assertEquals("normal",store.get("normal").shopId);
        assertEquals(1,store.all().size());
        store.delete("normal");
        assertNull(store.get("normal"));
    }
    @Test public void traversalCannotReadOverwriteOrDeleteAnotherHostFile() throws Exception {
        ShopStore store = store();
        File outside = new File(temporary.getRoot(),"node.json");
        byte[] original=bundle("sentinel").getBytes(StandardCharsets.UTF_8);
        Files.write(outside.toPath(),original);
        for (String id : new String[]{"../node","..\\node","nested/node"}) {
            assertNull(store.importBundle(bundle(id)));
            assertNull(store.get(id));
            store.delete(id);
            assertArrayEquals(original,Files.readAllBytes(outside.toPath()));
        }
    }
    @Test public void symlinkCannotExposeOrOverwriteAnotherHostFile() throws Exception {
        ShopStore store=store();
        File outside=new File(temporary.getRoot(),"node.json");
        byte[] original=bundle("sentinel").getBytes(StandardCharsets.UTF_8);
        Files.write(outside.toPath(),original);
        Files.createSymbolicLink(new File(temporary.getRoot(),"shops/link.json").toPath(),outside.toPath());
        assertNull(store.get("link"));
        assertTrue(store.all().isEmpty());
        assertNull(store.importBundle(bundle("link")));
        store.delete("link");
        assertArrayEquals(original,Files.readAllBytes(outside.toPath()));
    }
}
