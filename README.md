# miniMerch Shop (native Android)

A native Android **on-chain storefront** — browse a vendor's shop and place **encrypted orders directly on the
Minima chain**, no server or marketplace operator. Part of the miniMerch family (Shop · Inbox · Studio). Package
`com.eurobuddha.merchshop`.

## How it works

- **Load a shop** — import a vendor's portable **`.shop`** catalog (products, prices, shipping) or scan their vendor
  card. The card carries the vendor's `publicId` (seed-derived X25519 + Ed25519 keys).
- **Order** — build a cart and place an order. The sealed order is posted as a dust coin at the shared **MINIMERCH**
  sentinel `0x4D494E494D45524348`, encrypted (`crypto_box_seal`) so only the vendor can read it, and Ed25519-signed.
- **Pay** — send the payment as a **separate, reference-stamped value transfer** to the vendor's own address
  (the order ref in coin state), so payment and order are linked on-chain. An order is **paid** once the matching
  token's cumulative amount reaches the order total.
- **Track** — the vendor's sealed status replies (received / shipped / …) appear against your order.

Everything is seed-derived and on-chain, so it **interoperates with the [miniMerch Inbox](https://github.com/eurobuddha/minima-core-android-merchinbox)/[Studio](https://github.com/eurobuddha/minima-core-android-merchstudio)
apps, the desktop miniMall module, and the web miniMerch family** (same sentinel + `.shop` schema + sealed-box crypto).

## Build

Requires a **JDK 17/21** (the Android Studio JBR works):

```sh
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleRelease
```

Install, then enable **miniMerch Shop** in Minima Core → Apps to authorize the IPC.

Current: **v0.3.0** · package `com.eurobuddha.merchshop`.
