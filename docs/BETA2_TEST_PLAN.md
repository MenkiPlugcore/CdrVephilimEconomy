# beta.2 Test Plan — CdrVephilimEconomy

Target build: `0.1.0-beta.2-RC2`.

beta.2 dibangun di atas frozen baseline `0.1.0-beta.1`. RC1 management smoke test sudah dinyatakan aman oleh user. Fokus RC2 adalah **formal shops.yml schema migration, Discord administrative audit, management QoL, dan regression RC1**.

## Preconditions

- Paper 1.21.11 / Java 21.
- Citizens + Vault + economy provider aktif.
- `0.1.0-beta.2-RC2` terpasang.
- `/cve doctor` tidak menunjukkan failure core sebelum pengujian management dimulai.

## Read-only / Diagnostics

- [ ] `/cve shop list` menampilkan semua shop runtime.
- [ ] `/cve shop info blacksmith` menampilkan enabled state, NPC ID, manager, listing, price, dan stock runtime.
- [ ] `/cve shop schema` menampilkan schema current `v2/v2` setelah migration.
- [ ] `/cve shop validate` strict-validate schema + shop/listing tanpa mengubah runtime stock.
- [ ] User tanpa `cdrvephilimeconomy.shop.view` tidak dapat memakai read-only management diagnostics.

## shops.yml Schema v2 / Migration

- [ ] Default RC2 `shops.yml` memiliki `meta.schema: 2`.
- [ ] File beta.1/RC1 tanpa `meta.schema` dibaca sebagai legacy schema v1.
- [ ] Saat ShopAdminService mulai, legacy v1 dimigrasikan otomatis menjadi v2.
- [ ] Migration membuat `shops.yml.schema-v1.bak` sebelum mengganti file aktif.
- [ ] Migration menambahkan `manager: ""` pada shop lama bila field belum ada.
- [ ] Migration mempertahankan seluruh shop, listing, harga, mode, NPC ID, enabled state, dan stock runtime.
- [ ] `meta.migrated-from: 1`, `meta.migrated-at`, dan `meta.updated-at` ditulis saat migration.
- [ ] Setiap mutation RC2 mempertahankan `meta.schema: 2` dan memperbarui `meta.updated-at`.
- [ ] `meta.schema` non-integer ditolak.
- [ ] Schema `0`/invalid ditolak.
- [ ] Schema lebih baru dari plugin, misalnya `meta.schema: 99`, ditolak fail-closed oleh ShopRegistry.
- [ ] Core transaksi tidak diam-diam menulis ulang file schema masa depan yang tidak dipahami.

## Create / Delete Shop

- [ ] `/cve shop create farmer 27 Farmer Kerajaan` membuat shop baru disabled dan `npc-id: -1`.
- [ ] Shop baru langsung muncul di `/cve shop list` tanpa restart.
- [ ] Duplicate shop ID ditolak dan runtime lama tetap aktif.
- [ ] Shop ID invalid ditolak.
- [ ] Inventory size non-multiple-of-9 atau di luar 9-54 ditolak.
- [ ] `/cve shop delete farmer` tanpa `CONFIRM` tidak menghapus shop.
- [ ] `/cve shop delete farmer CONFIRM` menghapus shop setelah candidate validation dan runtime reload.

## RC2 Shop QoL

- [ ] `/cve shop name <shop> <display name>` mengganti display name tanpa restart.
- [ ] Display name kosong atau >128 karakter ditolak.
- [ ] `/cve shop size <shop> 36` mengubah GUI size jika semua listing masih berada dalam range.
- [ ] Mengecilkan size sehingga ada listing di luar range ditolak dan runtime lama tetap aktif.
- [ ] `/cve shop slot <shop> <listing> <slot>` memindahkan listing tanpa restart.
- [ ] Slot duplicate ditolak.
- [ ] Slot di luar size GUI ditolak.
- [ ] Permission `cdrvephilimeconomy.shop.edit` hanya memberi perubahan display name/size, bukan stock/price/delete.

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

## Listing CRUD / Price / Mode

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
- [ ] `/cve shop removeitem ...` wajib `CONFIRM`.
- [ ] `/cve shop price ... buy|sell <value>` menerapkan harga tanpa restart.
- [ ] Harga negatif/NaN/Infinity ditolak.
- [ ] `/cve shop mode ... BUY|SELL|BUY_SELL` menerapkan arah transaksi baru.
- [ ] Candidate invalid tidak mengganti runtime lama.

## Stock Management

- [ ] `/cve shop stock <shop> <listing> set <amount>` mengubah stock runtime + persisted stock.
- [ ] `add` bekerja selama hasil <= max-stock.
- [ ] `remove` bekerja selama hasil >= 0.
- [ ] Stock admin tidak pernah negatif atau melewati max-stock.
- [ ] Runtime stock mutation ditolak ketika economy safety stop aktif.
- [ ] `/cve shop initialstock ...` tidak mereset stock listing existing.
- [ ] `/cve shop maxstock ...` dapat clamp runtime stock secara aman ketika max diperkecil.

## Transactional Config Write

- [ ] Setiap perubahan config membuat/menyegarkan `shops.yml.admin.bak` sebelum replace.
- [ ] Candidate divalidasi ShopRegistry sebelum mengganti file aktif.
- [ ] File `.admin.candidate` / `.admin.tmp` tidak tertinggal setelah sukses.
- [ ] Candidate invalid tidak mengubah `shops.yml` aktif.
- [ ] Jika runtime reload gagal setelah replace, service mencoba restore original dan reload runtime lama.

## Administrative Audit — Local

File: `plugins/CdrVephilimEconomy/logs/admin-audit.log`.

- [ ] Setiap mutation memiliki `*_REQUEST` sebelum perubahan.
- [ ] Mutation sukses memiliki `*_SUCCESS`.
- [ ] Mutation invalid/gagal memiliki `*_REJECTED` atau `*_FAILED`.
- [ ] Actor, action, detail tercatat.
- [ ] REQUEST audit gagal ditulis → mutation dibatalkan fail-closed.
- [ ] Stock change mencatat before/after.
- [ ] Migration v1→v2 meninggalkan `SHOPS_SCHEMA_MIGRATION_SUCCESS` bila local admin audit writable.

## Administrative Audit — Discord

Config:

```yaml
admin-audit:
  discord:
    enabled: true
    webhook-url: "..."
    include-requests: false
```

- [ ] Dengan Discord admin audit disabled, tidak ada request network.
- [ ] Enabled + webhook valid mengirim `*_SUCCESS`, `*_FAILED`, dan `*_REJECTED` secara async.
- [ ] `include-requests: false` tidak mengirim `*_REQUEST` ke Discord.
- [ ] `include-requests: true` ikut mengirim REQUEST.
- [ ] Webhook URL tidak ditulis ke console maupun admin-audit.log.
- [ ] Discord offline/error HTTP tidak membatalkan mutation yang local audit + persistence-nya sudah sukses.
- [ ] Mengubah config Discord lalu `/cve reload` langsung dipakai oleh admin audit berikutnya tanpa restart.

## Granular Permissions

- [ ] `cdrvephilimeconomy.admin` memiliki seluruh akses.
- [ ] `.shop.view` hanya list/info/schema/validate.
- [ ] `.shop.create` hanya create.
- [ ] `.shop.delete` hanya delete.
- [ ] `.shop.bind` hanya bind/unbind.
- [ ] `.shop.toggle` hanya enable/disable.
- [ ] `.shop.edit` hanya display name/size.
- [ ] `.shop.item` memberi add/remove/mode/slot listing.
- [ ] `.shop.price` memberi price mutation.
- [ ] `.shop.stock` memberi runtime/config stock mutation.
- [ ] `.shop.manager` memberi manager metadata mutation.

## Core Regression

- [ ] BUY 1 tetap sukses.
- [ ] BUY bulk tetap sukses.
- [ ] SELL 1 tetap sukses.
- [ ] SELL bulk tetap sukses.
- [ ] Restart mempertahankan stock.
- [ ] `/cve reload`, `/cve doctor`, `/cve safety status` tetap bekerja.
- [ ] Pending transaction journal dan persistent safety behavior beta.1 tidak berubah.
- [ ] Upgrade RC1 → RC2 tidak mengubah balance atau runtime stock existing.

## RC2 Exit Criteria

RC2 lulus bila migration v1→v2 tidak kehilangan definisi maupun stock, schema masa depan ditolak fail-closed, Discord admin audit tidak memblokir main transaction path, QoL commands tetap transactional, permission granular benar, dan regression BUY/SELL beta.1 + RC1 management tetap aman.
