package com.eurobuddha.merchshop;

import org.json.JSONArray;
import org.json.JSONObject;
import com.eurobuddha.comms.CommsIdentity;
import com.eurobuddha.comms.MerchMessage;

import java.util.ArrayList;
import java.util.List;

/** A shop catalog + vendor card, loaded from an imported `.shop` bundle (JSON). */
public final class Catalog {

    /** {@code tokenid} is the file's own, "" when it has none: it never decides what is paid (see {@link #payTokenid}). */
    public String shopName = "Shop", shopId = "", vendorPublicId = "", vendorAddress = "", currency = "Minima", tokenid = "";
    /** Schema v2 (miniMerch NFT Studio): version marker, declared shop kind, storefront blurb. */
    public int schema = 1;
    public String shopType = "", about = "";
    public final List<Shipping> shipping = new ArrayList<>();
    public final List<Product> products = new ArrayList<>();

    /** Schema v3 collection directory: the standard top-side token icon + name + size per
     *  referenced StateNFT collection, keyed by tokenid (icon = bare b64 JPEG or ""). */
    public static class CollectionInfo { public String name = "", icon = ""; public int size = 0; }
    public final java.util.Map<String, CollectionInfo> nftCollections = new java.util.LinkedHashMap<>();

    public static class Product {
        public String id, name, description = "", mode = "units", price = "0", image = "";
        public int maxUnits = 10;
        /** Tokenid of the NFT being sold (set by miniMerch NFT Studio; empty for normal products). */
        public String nftTokenId = "";
        /** Schema v2: total editions minted, ticker, provenance link — all optional. */
        public int nftSupply = 0;
        public String nftTicker = "", nftExternalUrl = "";
        /** Schema v3 (StateNFT piece): edition index = the piece's coin state port 0 (>0 marks a
         *  piece), plus the collection's display name for grouping. */
        public int nftStateIdx = 0;
        public String nftCollection = "";
        /** Schema v3.1 (complete collection): one product = every piece the seller holds. */
        public int nftBundle = 0, nftPieces = 0;

        public boolean isNft() { return nftTokenId != null && !nftTokenId.isEmpty(); }
        public boolean isStatePiece() { return isNft() && nftStateIdx > 0; }
        public boolean isBundle() { return isNft() && nftBundle > 0; }
    }
    public static class Shipping { public String id = "", label = "", fee = "0"; }

    /** Parse a `.shop` bundle (the JSON the studio produces). */
    public static Catalog fromJson(String json) {
        Catalog c = new Catalog();
        try {
            JSONObject o = new JSONObject(json);
            c.shopName = o.optString("shopName", "Shop");
            c.shopId = o.optString("shopId", "");
            c.vendorPublicId = o.optString("vendorPublicId", "");
            c.vendorAddress = o.optString("vendorAddress", "");
            c.currency = o.optString("currency", "Minima");
            c.tokenid = o.optString("tokenid", "");
            c.schema = o.optInt("schema", 1);
            c.shopType = o.optString("shopType", "");
            c.about = o.optString("about", "");
            JSONArray sh = o.optJSONArray("shipping");
            if (sh != null) for (int i = 0; i < sh.length(); i++) {
                JSONObject s = sh.optJSONObject(i); if (s == null) continue;
                Shipping x = new Shipping(); x.id = s.optString("id"); x.label = s.optString("label"); x.fee = s.optString("fee", "0");
                c.shipping.add(x);
            }
            JSONArray ps = o.optJSONArray("products");
            if (ps != null) for (int i = 0; i < ps.length(); i++) {
                JSONObject p = ps.optJSONObject(i); if (p == null) continue;
                Product x = new Product();
                x.id = p.optString("id", "p" + i); x.name = p.optString("name", "Item");
                x.description = p.optString("description", ""); x.mode = p.optString("mode", "units");
                x.price = p.optString("price", "0"); x.maxUnits = p.optInt("maxUnits", 10);
                x.image = p.optString("image", "");
                x.nftTokenId = p.optString("nftTokenId", "");
                x.nftSupply = p.optInt("nftSupply", 0);
                x.nftTicker = p.optString("nftTicker", "");
                x.nftExternalUrl = p.optString("nftExternalUrl", "");
                x.nftStateIdx = p.optInt("nftStateIdx", 0);
                x.nftCollection = p.optString("nftCollection", "");
                x.nftBundle = p.optInt("nftBundle", 0);
                x.nftPieces = p.optInt("nftPieces", 0);
                c.products.add(x);
            }
            JSONArray cols = o.optJSONArray("nftCollections");
            if (cols != null) for (int i = 0; i < cols.length(); i++) {
                JSONObject co = cols.optJSONObject(i); if (co == null) continue;
                String tid = co.optString("tokenid", "");
                if (tid.isEmpty()) continue;
                CollectionInfo ci = new CollectionInfo();
                ci.name = co.optString("name", "");
                ci.icon = co.optString("icon", "");
                ci.size = co.optInt("size", 0);
                c.nftCollections.put(tid, ci);
            }
        } catch (Exception ignored) { /* keep defaults */ }
        return c;
    }

    /** A well-formed bundle has a valid vendor key and at least one product. */
    public boolean valid() {
        return vendorPublicId != null && !vendorPublicId.contains("PLACEHOLDER")
                && CommsIdentity.isValidPublicId(vendorPublicId) && !products.isEmpty();
    }

    /** Why this shop cannot be paid, or null. A .shop file is the vendor's: one that says "Minima" and carries the
     *  MxUSD token id (or any token id that is not its currency's) would have the buyer pay that token under the
     *  Minima label, so it is refused on import, on open, before the "Pay …?" question and before the order is sent. */
    public String tokenProblem() { return MerchMessage.tokenProblem(currency, tokenid); }

    /** The token this shop is paid in, from its CURRENCY alone (null when {@link #tokenProblem} is not null). */
    public String payTokenid() { return tokenProblem() == null ? MerchMessage.tokenFor(currency) : null; }

    /** A price or fee as a plain decimal: digits with at most one point, no sign, exponent or spaces, at most 30
     *  whole and 44 decimal places (Minima's limit). ".5" and "5." are kept: they read exactly as before. */
    public static boolean plain(String v) {
        return v != null && v.matches("\\d{1,30}(\\.\\d{0,44})?|\\.\\d{1,44}");
    }

    /** Why a price, fee or stock in this shop cannot be used, or null. Checked with {@link #tokenProblem} on import,
     *  on open and before the "Pay …?" question; valid prices give exactly the totals they gave before. */
    public String priceProblem() {
        for (Product p : products) {
            if (!plain(p.price)) return "The price of \"" + cut(p.name) + "\" is not a plain number (" + cut(p.price) + "). Nothing was sent.";
            if (p.maxUnits < 1) return "The stock of \"" + cut(p.name) + "\" is not a whole number above zero. Nothing was sent.";
        }
        for (Shipping s : shipping)
            if (!plain(s.fee == null || s.fee.isEmpty() ? "0" : s.fee)) return "The fee of the delivery method \"" + cut(s.label) + "\" is not a plain number (" + cut(s.fee) + "). Nothing was sent.";
        return null;
    }

    /** The first problem this shop has (token, then prices), or null. */
    public String problem() { String t = tokenProblem(); return t != null ? t : priceProblem(); }

    /** A vendor-supplied name or price in a message (not an identifier): at most 60 characters. */
    private static String cut(String s) { return s == null ? "" : s.length() <= 60 ? s : s.substring(0, 60) + "…"; }

    /** Kept for compatibility with the old single-shop call sites. */
    public boolean configured() { return valid(); }

    /** An NFT shop: declared by the v2 export, or inferred when EVERY product is an NFT. */
    public boolean isNftShop() {
        if ("nft".equals(shopType)) return true;
        if (products.isEmpty()) return false;
        for (Product p : products) if (!p.isNft()) return false;
        return true;
    }
}
