# beta.2 Test Plan — CdrVephilimEconomy

Target build: `0.1.0-beta.2-RC1`.

beta.2 dibangun di atas frozen baseline `0.1.0-beta.1`. Fokus RC1 adalah **shop management yang transactional, permission-aware, dan audited** tanpa mengubah core BUY/SELL engine.

## Preconditions

- Paper 1.21.11 / Java 21.
- Citizens + Vault + economy provider aktif.
- `0.1.0-beta.2-RC1` terpasang.
- `/cve doctor` tidak menunjukkan failure core sebelum pengujian management dimulai.

## Read-only Commands

- [ ] `/cve shop list` menampilkan semua shop runtime.
- [ ] `/cve shop info blacksmith` menampilkan enabled state, NPC ID, manager, listing, price, dan stock runtime.
- [ ] User tanpa `cdrvephilimeconomy.shop.view` tidak dapat melihat detail melalui command management.

## Create / Delete Shop

- [ ] `/cve shop create farmer 27 Farmer Kerajaan` membuat shop baru dalam keadaan disabled dan `npc-id: -1`.
- [ ] Shop baru langsung muncul di `/cve shop list` tanpa restart.
- [ ] Duplicate shop ID ditolak dan runtime lama tetap aktif.
- [ ] Shop ID invalid ditolak.
- [ ] Inventory size non-multiple-of-9 atau di luar 9-54 ditolak.
- [ ] `/cve shop delete farmer` tanpa `CONFIRM` tidak menghapus shop.
- [ ] `/cve shop delete farmer CONFIRM` menghapus shop setelah candidate validation dan runtime reload.

## Citizens Binding / Toggle

- [ ] `/cve shop bind <shop> <npc-id>` membind Citizens NPC tanpa restart.
- [ ] `/cve shop bind <shop> -1` melakukan unbind.
- [ ] Duplicate active NPC binding ditolak oleh candidate validation.
- [ ] `/cve shop enable <shop>` mengaktifkan shop.
- [ ] `/cve shop disable <shop>` menonaktifkan shop dan NPC tidak lagi membuka GUI.

## Manager Metadata

- [ ] `/cve shop manager <shop> <name>` menyimpan metadata penanggung jawab.
- [ ] `/cve shop manager <shop> none` menghapus metadata manager.
- [ ] Manager tampil pada `/cve shop list` dan `/cve shop info`.
- [ ] Manager metadata tidak otomatis memberi permission transaksi/admin.

## Listing CRUD

Syntax RC1:

```text
/cve shop additem <shop> <listing> <material|hand> <slot> <mode> <buy> <sell> <initial> <max>
```

- [ ] `material=hand` membaca material dari main hand player.
- [ ] Material eksplisit seperti `IRON_INGOT` dapat dipakai dari console/admin.
- [ ] Listing baru muncul di GUI tanpa restart.
- [ ] Slot duplicate/out-of-range ditolak.
- [ ] BUY mode dengan buy-price <= 0 ditolak.
- [ ] SELL mode dengan sell-price <= 0 ditolak.
- [ ] Stock bounds invalid ditolak.
- [ ] `/cve shop removeitem <shop> <listing>` tanpa `CONFIRM` tidak menghapus listing.
- [ ] `/cve shop removeitem <shop> <listing> CONFIRM` menghapus listing dan runtime stock entry terkait.

## Price / Mode

- [ ] `/cve shop price <shop> <listing> buy <value>` menerapkan harga BUY tanpa restart.
- [ ] `/cve shop price <shop> <listing> sell <value>` menerapkan harga SELL tanpa restart.
- [ ] Harga negatif/NaN/Infinity ditolak.
- [ ] `/cve shop mode <shop> <listing> BUY|SELL|BUY_SELL` menerapkan arah transaksi baru.
- [ ] Candidate invalid tidak mengganti runtime lama.

## Stock Management

- [ ] `/cve shop stock <shop> <listing> set <amount>` mengubah stock runtime + persisted stock.
- [ ] `/cve shop stock <shop> <listing> add <amount>` bekerja selama hasil <= max-stock.
- [ ] `/cve shop stock <shop> <listing> remove <amount>` bekerja selama hasil >= 0.
- [ ] Stock admin tidak pernah menjadi negatif atau melewati max-stock.
- [ ] Runtime stock mutation ditolak ketika economy safety stop aktif.
- [ ] `/cve shop initialstock ...` mengubah bootstrap value konfigurasi tanpa mereset stock listing existing.
- [ ] `/cve shop maxstock ...` mengubah batas stock; jika batas diperkecil, reconcile clamp runtime stock secara aman.

## Transactional Config Write

- [ ] Setiap perubahan konfigurasi membuat/menyegarkan `shops.yml.admin.bak` sebelum replace.
- [ ] Candidate config divalidasi dengan ShopRegistry sebelum mengganti `shops.yml` aktif.
- [ ] File temp admin tidak tertinggal setelah operasi sukses.
- [ ] Jika candidate invalid, `shops.yml` aktif tidak berubah.
- [ ] Jika runtime reload gagal setelah replace, service mencoba mengembalikan file original dan reload runtime lama.

## Administrative Audit

File: `plugins/CdrVephilimEconomy/logs/admin-audit.log`.

- [ ] Setiap mutation memiliki record `*_REQUEST` sebelum perubahan dilakukan.
- [ ] Mutation sukses memiliki record `*_SUCCESS`.
- [ ] Mutation invalid/gagal memiliki record `*_REJECTED` atau `*_FAILED`.
- [ ] Actor, action, dan detail perubahan tercatat.
- [ ] Jika REQUEST audit tidak dapat ditulis, mutation dibatalkan fail-closed.
- [ ] Stock change mencatat before/after.

## Granular Permissions

- [ ] `cdrvephilimeconomy.admin` tetap memiliki seluruh akses.
- [ ] `cdrvephilimeconomy.shop.view` hanya memberi list/info.
- [ ] `.shop.create` hanya memberi create.
- [ ] `.shop.delete` hanya memberi delete.
- [ ] `.shop.bind` hanya memberi bind/unbind.
- [ ] `.shop.toggle` hanya memberi enable/disable.
- [ ] `.shop.item` memberi add/remove/mode listing.
- [ ] `.shop.price` memberi price mutation.
- [ ] `.shop.stock` memberi runtime/config stock mutation.
- [ ] `.shop.manager` memberi manager metadata mutation.
- [ ] Staff dengan permission granular tidak perlu diberi full `cdrvephilimeconomy.admin`.

## Core Regression

Setelah management testing:

- [ ] BUY 1 tetap sukses.
- [ ] BUY bulk tetap sukses.
- [ ] SELL 1 tetap sukses.
- [ ] SELL bulk tetap sukses.
- [ ] Restart mempertahankan stock.
- [ ] `/cve reload`, `/cve doctor`, dan `/cve safety status` tetap bekerja.
- [ ] Pending transaction journal dan persistent safety behavior beta.1 tidak berubah.

## RC1 Exit Criteria

RC1 dianggap aman untuk lanjut jika management commands tidak dapat menghasilkan config parsial, invalid candidate tidak mengganti runtime sehat, permission granular bekerja, seluruh mutation administratif memiliki audit trail, dan regression BUY/SELL beta.1 tetap lolos.
