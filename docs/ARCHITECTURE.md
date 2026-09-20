# Architecture — CdrVephilimEconomy

Dokumen ini merefleksikan arsitektur aktual **`0.1.0-beta.1` FINAL**.

## Design Goals

- Ringan untuk Paper 1.21.11 / Java 21.
- NPC-only player flow.
- Tidak melakukan kalkulasi ekonomi setiap tick.
- Aman terhadap dupe, race condition, crash window, dan silent stock reset.
- Shop dapat diubah dari konfigurasi tanpa recompiling plugin.
- Perubahan config dapat diterapkan dengan safe runtime reload.
- Failure kritis memilih fail-closed daripada melanjutkan ekonomi dengan state yang tidak dapat dipercaya.

## High-Level Components

### NPC Integration

Citizens menjadi representasi NPC dan entry point interaksi player.

Tanggung jawab:
- mendeteksi interaksi player dengan Citizens NPC;
- mencocokkan NPC ID dengan shop aktif;
- memblokir akses saat persistent safety stop aktif;
- membuka GUI shop yang sesuai.

Citizens **bukan** sumber kebenaran transaksi.

### Shop Registry

`ShopRegistry` memuat dan memvalidasi `shops.yml`.

Menyimpan:
- shop ID;
- display name;
- Citizens NPC binding;
- status enabled/disabled;
- listing dan slot GUI;
- mode BUY / SELL / BUY_SELL;
- harga BUY/SELL;
- initial stock dan max stock.

Definisi invalid, duplicate slot, duplicate NPC binding, material invalid, harga invalid, atau bounds stock invalid ditolak secara fail-closed.

### GUI Layer

GUI menggunakan custom inventory holder dan hanya menjadi presentation/controller tipis.

Tanggung jawab:
- render saldo, stock, harga, bulk amount, sold-out/full state;
- cancel inventory mutation yang tidak diizinkan;
- mapping slot server-side ke listing;
- memverifikasi ulang jarak dan binding NPC sebelum transaksi;
- meneruskan request ke `TransactionService`.

Logika mutation bisnis tidak ditempatkan di GUI listener.

### Transaction Service

Komponen paling kritis.

Tanggung jawab:
- validasi amount dan mode transaksi;
- cooldown player;
- per-player in-flight guard;
- listing-level lock;
- menolak eksekusi transaksi dari thread async;
- validasi balance, inventory, stock, dan max stock;
- write-ahead transaction journal;
- mutation Vault/inventory;
- persistence stock;
- compensation rollback;
- trip persistent safety stop ketika state tidak lagi dapat dibuktikan konsisten;
- local/Discord audit.

### Economy Bridge

`EconomyBridge` mengabstraksikan Vault provider.

Tanggung jawab:
- balance check;
- withdraw;
- deposit;
- currency formatting;
- normalisasi hasil operasi provider.

### Stock Storage

Beta.1 **tidak menggunakan SQLite**. Mutable stock disimpan secara terpisah dari shop config menggunakan YAML persistence yang dijaga ketat.

File utama:

```text
stock.yml
stock.yml.bak
stock.yml.tmp
stock.yml.initialized
```

Mekanisme:
- in-memory stock map untuk runtime;
- write temporary snapshot;
- strict parse + verification;
- atomic replace bila filesystem mendukung;
- backup snapshot;
- recovery dari backup/temp pada startup;
- `stock.yml.initialized` mencegah seluruh snapshot yang hilang dianggap sebagai first boot baru;
- corrupt/untrusted storage dapat menyebabkan startup fail-closed.

### Write-Ahead Transaction Journal

Setiap BUY/SELL yang sudah melewati validasi dan akan mulai mutation membuat journal durable di:

```text
pending-transactions/
```

Stage yang dicatat antara lain:

```text
PREPARED
MONEY_WITHDRAWN
ITEM_ADDED
ITEM_REMOVED
MONEY_DEPOSITED
STOCK_PERSISTED
```

Tujuan journal bukan untuk auto-replay transaksi, melainkan menyediakan evidence yang cukup untuk mendeteksi transaksi yang terputus oleh hard crash.

Jika pending journal ditemukan pada startup, ekonomi masuk safety stop. Admin harus melakukan rekonsiliasi dan recovery eksplisit. Evidence kemudian diarsipkan ke `transaction-recovery/` dan `transaction-recovery.log`.

### Runtime Safety State

Persistent circuit breaker memakai:

```text
safety.lock
safety.lock.tmp
safety.lock.last
safety-history.log
```

Safety stop dapat dipicu oleh kegagalan persistence, kegagalan rollback/compensation kritis, journal failure, atau pending transaction setelah crash.

Restart server dan `/cve reload` tidak menghapus safety stop.

Recovery hanya dilakukan secara eksplisit:

```text
/cve safety unlock CONFIRM
```

Recovery tetap ditolak jika stock snapshot tidak dapat di-flush dengan sehat.

### Inventory Mutation Safety

BUY/SELL beta.1 hanya memperlakukan item vanilla polos sebagai item yang dapat dijual untuk listing material biasa.

Inventory helper:
- mengecek capacity sebelum BUY;
- menghitung item polos sebelum SELL;
- menyimpan snapshot storage sebelum add/remove;
- mengembalikan snapshot jika operasi helper gagal setengah jalan.

### Audit Service

Target audit:
- `logs/audit.log` lokal;
- Discord webhook async opsional.

Audit mencatat transaction ID, player, shop/listing, type, amount, unit price, intended total, stock before/after, status, dan detail failure.

Discord tidak berada di jalur blocking transaksi utama dan kegagalan Discord tidak boleh menggagalkan transaksi.

### Doctor Diagnostics

`/cve doctor` melakukan health inspection non-destruktif terhadap:
- runtime services;
- Citizens;
- Vault/provider;
- config dan shop definitions;
- runtime-vs-disk;
- NPC bindings;
- stock main/backup/marker/temp;
- filesystem writability;
- audit state;
- safety state;
- pending transaction evidence.

Doctor tidak memperbaiki stock atau membuka safety lock secara otomatis.

## Transaction Safety Flow

BUY simplified:

```text
validate request
      ↓
per-player in-flight guard
      ↓
listing lock
      ↓
recheck safety + stock + balance + inventory
      ↓
write PREPARED journal
      ↓
withdraw money
      ↓
update journal stage
      ↓
give item
      ↓
update journal stage
      ↓
persist stock
      ↓
mark STOCK_PERSISTED
      ↓
audit SUCCESS
      ↓
remove pending journal
      ↓
release guards
```

SELL mengikuti prinsip yang sama dengan urutan item removal → payout → stock persistence.

Jika failure terjadi setelah mutation dimulai, plugin mencoba compensation. Bila compensation/persistence tidak dapat dipercaya, persistent safety stop diaktifkan dan evidence dipertahankan.

## Runtime Reload

`/cve reload` membangun candidate runtime terlebih dahulu.

Reload hanya mengganti runtime aktif setelah:
- `config.yml` strict-parse sukses;
- `shops.yml` valid tanpa rejected definition;
- audit service candidate dapat dibuat;
- listener candidate dapat diregistrasikan;
- stock reconcile berhasil dipersist.

GUI lama ditutup dan runtime lama dibersihkan setelah candidate siap. Safety stop tidak pernah di-reset oleh reload.

## Threading

Bukkit/Paper inventory, entity, Citizens interaction, dan Vault mutation dilakukan pada main server thread.

Transaksi dari thread async ditolak.

Operasi yang boleh async:
- Discord webhook delivery;
- pekerjaan eksternal yang tidak menyentuh Bukkit object dan tidak menjadi syarat commit transaksi.

## Performance Rules

- Tidak ada loop semua player setiap tick.
- Tidak ada recalculation harga global setiap tick.
- Tidak ada dynamic pricing di beta.1.
- Discord webhook tidak blocking main transaction path.
- Runtime data utama berada di memory dan dipersist hanya saat mutation/reconcile/flush yang diperlukan.
- Player cooldown/in-flight state dibersihkan saat player quit/runtime shutdown.

## Package Layout Aktual

```text
id.cdr.vephilimeconomy
├── CdrVephilimEconomy.java
├── audit/
├── command/
├── diagnostic/
├── economy/
├── gui/
├── npc/
├── shop/
├── storage/
├── transaction/
└── util/
```

## Future Extension Points

Mulai beta.2, fitur baru harus dibangun di atas core transaction/storage safety yang sudah dibekukan:

- admin Shop Management CRUD;
- granular permission scopes;
- configuration change audit;
- Economy Staff / governance;
- controlled dynamic pricing;
- RP market events;
- PlaceholderAPI / metrics.

Fitur lanjutan tidak boleh melewati `TransactionService`, stock persistence, journal, audit, atau safety circuit breaker hanya demi kemudahan implementasi.
