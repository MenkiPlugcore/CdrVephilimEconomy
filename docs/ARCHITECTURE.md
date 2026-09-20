# Architecture — CdrVephilimEconomy

## Design Goals

- Ringan untuk server Paper.
- Tidak melakukan kalkulasi ekonomi setiap tick.
- Aman terhadap dupe dan race condition.
- Shop dapat ditambah tanpa recompiling plugin.
- Integrasi eksternal dibuat modular.
- Player-facing flow tetap NPC-only.

## High-Level Components

### NPC Integration

Tanggung jawab:
- mendeteksi interaksi player dengan NPC;
- mencocokkan Citizens NPC ID dengan shop ID;
- membuka shop yang sesuai.

Citizens hanya menjadi representasi NPC. Citizens tidak menjadi sumber kebenaran transaksi.

### Shop Registry

Menyimpan definisi shop:
- shop ID;
- display name;
- NPC binding;
- daftar listing;
- status enabled/disabled;
- metadata pengelola.

### Listing Service

Menyimpan aturan item:
- item identity;
- BUY/SELL mode;
- buy price;
- sell price;
- stock;
- max stock;
- enabled state.

### Transaction Service

Komponen paling kritis.

Tanggung jawab:
- validasi request;
- validasi saldo;
- validasi inventory;
- validasi stok;
- lock listing bila diperlukan;
- melakukan mutation economy + inventory + stock;
- rollback saat terjadi kegagalan;
- menghasilkan transaction result;
- mengirim event audit.

Tidak boleh ada logika transaksi kritis di GUI listener.

### Economy Bridge

Abstraksi untuk Vault/economy provider.

Tanggung jawab:
- get balance;
- withdraw;
- deposit;
- normalisasi hasil operasi economy.

### Storage

Fase awal dapat menggunakan SQLite untuk data runtime seperti stok dan audit lokal.

Konfigurasi definisi shop dapat menggunakan YAML agar mudah diedit, sementara data mutable seperti stok disimpan terpisah agar aman dari reload konfigurasi.

### Audit Service

Dua target:
- local/database audit;
- Discord webhook async.

Discord tidak boleh berada di jalur blocking transaksi utama.

### Permission Service

Menggunakan permission nodes dan kompatibel dengan LuckPerms.

Player biasa tidak mendapatkan command shop.

## Suggested Package Layout

```text
id.cdr.vephilimeconomy
├── CdrVephilimEconomy.java
├── npc/
│   ├── NpcIntegration.java
│   └── CitizensNpcIntegration.java
├── shop/
│   ├── Shop.java
│   ├── ShopListing.java
│   ├── ShopRegistry.java
│   └── ShopService.java
├── transaction/
│   ├── TransactionService.java
│   ├── TransactionRequest.java
│   ├── TransactionResult.java
│   └── TransactionType.java
├── economy/
│   ├── EconomyBridge.java
│   └── VaultEconomyBridge.java
├── storage/
│   ├── Storage.java
│   ├── SqliteStorage.java
│   └── repository/
├── audit/
│   ├── AuditService.java
│   ├── LocalAuditSink.java
│   └── DiscordAuditSink.java
├── gui/
│   ├── ShopGui.java
│   └── ShopGuiListener.java
├── permission/
├── config/
└── util/
```

Nama package final dapat disesuaikan saat bootstrap project.

## Transaction Safety

Urutan BUY ideal:

```text
validate NPC/shop/listing
      ↓
acquire listing lock
      ↓
re-read current stock
      ↓
check balance
      ↓
check inventory capacity
      ↓
withdraw money
      ↓
give item
      ↓
decrement stock
      ↓
persist
      ↓
audit
      ↓
release lock
```

Implementasi final harus mempertimbangkan rollback untuk kegagalan setelah mutation dimulai.

SELL menggunakan prinsip yang sama, tetapi item player harus diverifikasi dan diamankan sebelum deposit final dilakukan.

## Threading

Bukkit/Paper inventory dan entity API harus dipanggil pada main thread kecuali API secara eksplisit menyatakan aman async.

Operasi yang cocok async:
- Discord webhook;
- batch audit persistence tertentu;
- pekerjaan I/O yang sudah dipisahkan dari Bukkit object.

Jangan menyimpan atau mengakses Bukkit entity/inventory object dari async task tanpa kontrol yang benar.

## Performance Rules

- Tidak ada loop semua player setiap tick.
- Tidak ada recalculation harga global setiap tick.
- Tidak ada query database berat pada setiap inventory render bila data dapat di-cache aman.
- Discord logging tidak blocking main thread.
- Config reload tidak menghapus stok runtime tanpa migration yang eksplisit.

## Future Extension Points

- DynamicPricingStrategy.
- Economy approval workflow.
- Per-shop manager scopes.
- RP market events.
- PlaceholderAPI.
- Metrics/admin dashboard.

Fitur lanjutan harus masuk sebagai modul di atas core transaction engine, bukan mengubah fondasi transaksi yang sudah stabil.
