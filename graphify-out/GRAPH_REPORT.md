# Graph Report - apks/merchshop  (2026-08-04)

## Corpus Check
- 28 files · ~14,895 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 302 nodes · 735 edges · 18 communities (15 shown, 3 thin omitted)
- Extraction: 93% EXTRACTED · 7% INFERRED · 0% AMBIGUOUS · INFERRED: 50 edges (avg confidence: 0.8)
- Token cost: 0 input · 0 output

## Community Hubs (Navigation)
- MainActivity
- CommsScanner
- CommsIdentity
- MerchDb
- .buildOrderDetail
- NodeApi
- Catalog
- .onBindViewHolder
- .pill
- Util
- Images
- .placeOrder
- QrUtil
- Sodium
- gradlew
- miniMerch Shop (native Android)

## God Nodes (most connected - your core abstractions)
1. `MainActivity` - 86 edges
2. `MerchDb` - 23 edges
3. `NodeApi` - 20 edges
4. `CommsScanner` - 18 edges
5. `Catalog` - 13 edges
6. `CommsIdentity` - 12 edges
7. `SendCb` - 10 edges
8. `CryptoProvider` - 10 edges
9. `Cb` - 10 edges
10. `ShopStore` - 10 edges

## Surprising Connections (you probably didn't know these)
- `CommsScanner` --references--> `NodeApi`  [EXTRACTED]
  apks/merchshop/app/src/main/java/com/eurobuddha/comms/CommsScanner.java → apks/merchshop/app/src/main/java/com/eurobuddha/comms/NodeApi.java
- `MainActivity` --references--> `CommsScanner`  [EXTRACTED]
  apks/merchshop/app/src/main/java/com/eurobuddha/merchshop/MainActivity.java → apks/merchshop/app/src/main/java/com/eurobuddha/comms/CommsScanner.java
- `MerchDb` --implements--> `MetaStore`  [EXTRACTED]
  apks/merchshop/app/src/main/java/com/eurobuddha/comms/MerchDb.java → apks/merchshop/app/src/main/java/com/eurobuddha/comms/CommsScanner.java
- `LocalEcCryptoProvider` --implements--> `CryptoProvider`  [EXTRACTED]
  apks/merchshop/app/src/main/java/com/eurobuddha/comms/LocalEcCryptoProvider.java → apks/merchshop/app/src/main/java/com/eurobuddha/comms/CryptoProvider.java
- `MainActivity` --references--> `CryptoProvider`  [EXTRACTED]
  apks/merchshop/app/src/main/java/com/eurobuddha/merchshop/MainActivity.java → apks/merchshop/app/src/main/java/com/eurobuddha/comms/CryptoProvider.java

## Import Cycles
- None detected.

## Communities (18 total, 3 thin omitted)

### Community 0 - "MainActivity"
Cohesion: 0.11
Nodes (14): ActivityResultLauncher, AlertDialog, Bitmap, FrameLayout, Handler, LazySodium, Uri, MainActivity (+6 more)

### Community 1 - "CommsScanner"
Cohesion: 0.11
Nodes (8): CommsScanner, JSONObject, Listener, MetaStore, Router, CryptoProvider, Opened, JSONArray

### Community 2 - "CommsIdentity"
Cohesion: 0.10
Nodes (10): BackupCrypto, SecureRandom, CommsIdentity, LazySodium, Hex, Hkdf, LazySodium, Override (+2 more)

### Community 3 - "MerchDb"
Cohesion: 0.15
Nodes (6): Context, Override, MerchDb, Order, SQLiteDatabase, SQLiteOpenHelper

### Community 4 - ".buildOrderDetail"
Cohesion: 0.22
Nodes (5): Product, TextView, LinearLayout, ScrollView, View

### Community 5 - "NodeApi"
Cohesion: 0.14
Nodes (10): CommsTransport, JSONObject, SendCb, Cb, Context, Handler, JSONObject, NodeApi (+2 more)

### Community 6 - "Catalog"
Cohesion: 0.22
Nodes (4): Catalog, Shipping, Context, ShopStore

### Community 7 - ".onBindViewHolder"
Cohesion: 0.21
Nodes (7): Adapter, Override, OrderAdapter, ProductAdapter, NonNull, ViewGroup, ViewHolder

### Community 8 - ".pill"
Cohesion: 0.21
Nodes (7): Avatars, Context, FrameLayout, Design, Context, TextView, GradientDrawable

### Community 10 - "Images"
Cohesion: 0.42
Nodes (4): Images, Bitmap, Context, Uri

### Community 11 - ".placeOrder"
Cohesion: 0.13
Nodes (4): SecureRandom, MailText, MerchMessage, JSONObject

### Community 14 - "gradlew"
Cohesion: 0.60
Nodes (3): gradlew script, die(), warn()

### Community 17 - "miniMerch Shop (native Android)"
Cohesion: 0.50
Nodes (3): Build, How it works, miniMerch Shop (native Android)

## Knowledge Gaps
- **2 isolated node(s):** `How it works`, `Build`
  These have ≤1 connection - possible missing edges or undocumented components.
- **3 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `MainActivity` connect `MainActivity` to `CommsScanner`, `MerchDb`, `.buildOrderDetail`, `NodeApi`, `Catalog`, `.onBindViewHolder`, `.placeOrder`?**
  _High betweenness centrality (0.423) - this node is a cross-community bridge._
- **Why does `NodeApi` connect `NodeApi` to `MainActivity`, `CommsScanner`, `.onBindViewHolder`?**
  _High betweenness centrality (0.099) - this node is a cross-community bridge._
- **Why does `MerchDb` connect `MerchDb` to `MainActivity`, `CommsScanner`, `.placeOrder`?**
  _High betweenness centrality (0.098) - this node is a cross-community bridge._
- **What connects `How it works`, `Build` to the rest of the system?**
  _2 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `MainActivity` be split into smaller, more focused modules?**
  _Cohesion score 0.11092436974789915 - nodes in this community are weakly interconnected._
- **Should `CommsScanner` be split into smaller, more focused modules?**
  _Cohesion score 0.11083743842364532 - nodes in this community are weakly interconnected._
- **Should `CommsIdentity` be split into smaller, more focused modules?**
  _Cohesion score 0.09682539682539683 - nodes in this community are weakly interconnected._