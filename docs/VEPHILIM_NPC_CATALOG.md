# Vephilim Roleplay — Official NPC Economy Catalog

Catalog ini adalah baseline harga NPC sebelum production hardening `v1.0.0`.

## Semantik transaksi

- `BUY`: player membeli item dari NPC.
- `SELL`: player menjual item ke NPC.
- Hanya listing yang ada di `shops.yml` yang dapat ditransaksikan. Item blacklist tidak dimasukkan sebagai listing.
- Semua shop default `enabled: false` dan `npc-id: -1` agar tidak aktif sebelum Citizens NPC dibind dan diverifikasi.

## 1. Penjual Makanan — BUY only

| Item | Harga BUY |
| --- | ---: |
| Bread | $2 |
| Cooked Chicken | $3 |
| Cooked Mutton | $4 |
| Cooked Porkchop | $5 |
| Cooked Beef | $5 |
| Cooked Rabbit | $4 |
| Cooked Cod | $3 |
| Cooked Salmon | $4 |
| Baked Potato | $3 |

Blacklist: Golden Apple, Enchanted Golden Apple, Golden Carrot, Suspicious Stew.

Fresh-install stock: `512` per listing, `max-stock: 2048`.

## 2. Pembeli Ore Kerajaan — SELL only

| Item | Harga SELL |
| --- | ---: |
| Coal | $3 |
| Raw Iron | $5 |
| Iron Ingot | $7 |
| Raw Copper | $2 |
| Copper Ingot | $3 |
| Raw Gold | $8 |
| Gold Ingot | $12 |
| Redstone | $4 |
| Lapis Lazuli | $4 |
| Diamond | $75 |
| Emerald | $50 |

Blacklist: Nether Quartz, Nether Gold Ore, Ancient Debris, Netherite Scrap, Netherite Ingot, dan seluruh ore/resource Nether yang tidak secara eksplisit dilisting.

Fresh-install stock: `0`, `max-stock: 4096`.

## 3. Farmer Kerajaan — SELL only

| Item | Harga SELL |
| --- | ---: |
| Wheat | $3 |
| Carrot | $3 |
| Potato | $3 |
| Beetroot | $3 |
| Pumpkin | $6 |
| Sugar Cane | $4 |
| Cocoa Beans | $5 |
| Sweet Berries | $4 |
| Glow Berries | $4 |

Blacklist: Melon, Melon Slice, Nether Wart, Chorus Fruit, Chorus Flower, dan seluruh hasil farm Nether yang tidak dilisting.

Fresh-install stock: `0`, `max-stock: 4096`.

## 4. Nelayan Kerajaan — SELL only

| Item | Harga SELL |
| --- | ---: |
| Cod | $4 |
| Salmon | $5 |
| Tropical Fish | $8 |
| Pufferfish | $10 |
| Ink Sac | $5 |

Blacklist: Nautilus Shell, Heart of the Sea, Enchanted Fishing Rod, dan fishing treasure langka yang tidak dilisting.

Fresh-install stock: `0`, `max-stock: 4096`.

## Shop IDs

```text
food
ore
farmer
fisherman
```

## Deployment note untuk server yang sudah berjalan

Default `src/main/resources/shops.yml` hanya menjadi template untuk instalasi baru. Server yang sudah memiliki `plugins/CdrVephilimEconomy/shops.yml` tidak boleh ditimpa otomatis karena file tersebut dapat berisi NPC binding, manager, listing tambahan, dan stock relationship milik server.

Untuk deployment existing server, merge empat shop di atas ke `shops.yml` live atau buat shop/listing melalui `/cve shop ...`, lalu bind Citizens NPC dan enable setelah review. `initial-stock` hanya men-seed listing yang benar-benar baru; runtime stock existing tetap berada di `stock.yml`.
