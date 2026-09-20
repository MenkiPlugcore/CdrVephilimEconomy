# beta.2 Test Plan — CdrVephilimEconomy

Target build: `0.1.0-beta.2-RC3`.

beta.2 dibangun di atas frozen baseline `0.1.0-beta.1`. RC1 management smoke test dan RC2 runtime/migration smoke test sudah dinyatakan aman oleh user. RC3 adalah kandidat hardening terakhir sebelum beta.2 final dengan fokus **crash-window administrative mutation, schema migration recovery, dan final regression**.

## Preconditions

- Paper 1.21.11 / Java 21.
- Citizens + Vault + economy provider aktif.
- `0.1.0-beta.2-RC3` terpasang.
- `/cve doctor` tidak menunjukkan failure core sebelum pengujian management dimulai.
- Backup folder plugin dibuat sebelum recovery drill destruktif.

## Read-only / Diagnostics

- [ ] `/cve shop list` menampilkan semua shop runtime.
- [ ] `/cve shop info blacksmith` menampilkan enabled state, NPC ID, manager, listing, price, dan stock runtime.
- [ ] `/cve shop schema` menampilkan schema current `v2/v2`, `schemaRecovery=OK`, dan `adminMutation=OK` pada kondisi normal.
- [ ] `/cve shop validate` strict-validate schema + shop/listing dan menampilkan recovery state tanpa mengubah runtime stock.
- [ ] User tanpa `cdrvephilimeconomy.shop.view` tidak dapat memakai read-only management diagnostics.

## shops.yml Schema v2 / Migration

- [ ] Default RC3 `shops.yml` memiliki `meta.schema: 2`.
- [ ] File beta.1/RC1 tanpa `meta.schema` dibaca sebagai legacy schema v1.
- [ ] Legacy v1 dimigrasikan menjadi v2 saat ShopAdminService diinisialisasi.
- [ ] Migration membuat `shops.yml.schema-v1.bak` sebelum mengganti file aktif.
- [ ] Backup migration identik dengan file source sebelum migration.
- [ ] Migration menambahkan `manager: ""` pada shop lama bila field belum ada.
- [ ] Migration mempertahankan shop, listing, harga, mode, NPC ID, enabled state, dan runtime stock.
- [ ] `meta.migrated-from: 1`, `meta.migrated-at`, dan `meta.updated-at` ditulis saat migration.
- [ ] Setiap mutation mempertahankan `meta.schema: 2` dan memperbarui `meta.updated-at`.
- [ ] `meta.schema` non-integer ditolak.
- [ ] Schema `0`/invalid ditolak.
- [ ] Schema lebih baru dari plugin, misalnya `meta.schema: 99`, ditolak fail-closed.
- [ ] Core transaksi tidak diam-diam menulis ulang future schema yang tidak dipahami.

## RC3 Schema Migration Recovery

Evidence:

```text
shops.yml.schema-v1.bak
shops.yml.schema.pending
shops.yml.schema.last
```

- [ ] Migration membuat pending marker sebelum replace live file.
- [ ] Pending marker memuat hash source dan candidate yang valid.
- [ ] Candidate hasil migration diverifikasi sebelum replace.
- [ ] Live `shops.yml` diverifikasi lagi setelah replace dan hash-nya harus sama dengan candidate.
- [ ] Crash/pending + live file sudah v2 valid → startup menganggap migration committed dan mengarsipkan marker.
- [ ] Crash/pending + live file masih legacy valid → migration dianggap belum committed dan aman untuk diulang.
- [ ] Crash/pending + live file corrupt + backup migration valid → backup dipulihkan lalu migration dapat dijalankan ulang.
- [ ] Crash/pending + live/backup tidak dapat dipercaya → tidak ada silent overwrite; recovery gagal secara fail-closed.
- [ ] Setelah migration normal selesai tidak ada `shops.yml.schema.pending` tertinggal.

## Create / Delete Shop

- [ ] `/cve shop create farmer 27 Farmer Kerajaan` membuat shop baru disabled dan `npc-id: -1`.
- [ ] Shop baru langsung muncul di `/cve shop list` tanpa restart.
- [ ] Duplicate shop ID ditolak dan runtime lama tetap aktif.
- [ ] Shop ID invalid ditolak.
- [ ] Inventory size non-multiple-of-9 atau di luar 9-54 ditolak.
- [ ] `/cve shop delete farmer` tanpa `CONFIRM` tidak menghapus shop.
- [ ] `/cve shop delete farmer CONFIRM` menghapus shop setelah candidate validation dan runtime reload.

## Shop QoL

- [ ] `/cve shop name <shop> <display name>` mengganti display name tanpa restart.
- [ ] Display name kosong atau >128 karakter ditolak.
- [ ] `/cve shop size <shop> 36` mengubah GUI size bila semua listing masih berada dalam range.
- [ ] Mengecilkan size sehingga listing keluar range ditolak dan runtime lama tetap aktif.
- [ ] `/cve shop slot <shop> <listing> <slot>` memindahkan listing tanpa restart.
- [ ] Slot duplicate ditolak.
- [ ] Slot di luar size GUI ditolak.
- [ ] Permission `cdrvephilimeconomy.shop.edit` hanya memberi perubahan display name/size, bukan stock/price/delete.

## Citizens / Manager / Listing Regression

- [ ] Bind/unbind Citizens NPC tetap bekerja tanpa restart.
- [ ] Enable/disable shop langsung berlaku.
- [ ] Duplicate active NPC binding ditolak.
- [ ] Manager metadata dapat set/clear dan tidak memberikan permission otomatis.
- [ ] Add/remove listing tetap transactional; remove wajib `CONFIRM`.
- [ ] BUY/SELL/BUY_SELL mode validation tetap benar.
- [ ] Price mutation langsung berlaku dan angka invalid ditolak.
- [ ] Initial/max stock config tetap aman dan existing stock tidak di-reset oleh perubahan initial-stock.

## Runtime Stock Management

- [ ] `stock set/add/remove` mengubah runtime + persisted stock.
- [ ] Stock tidak pernah negatif atau melebihi max-stock.
- [ ] Runtime stock mutation ditolak ketika economy safety stop aktif.
- [ ] Runtime stock mutation juga ditolak ketika administrative mutation recovery berada pada state ambigu/blocked.

## RC3 Administrative Mutation Crash Recovery

Evidence:

```text
shops.yml.admin.bak
shops.yml.admin.pending
shops.yml.admin.pending.last
logs/admin-recovery.log
```

- [ ] Mutation config normal membuat journal durable sebelum `shops.yml` live diganti.
- [ ] Journal menyimpan actor, action, detail, original SHA-256, candidate SHA-256, dan stage.
- [ ] Mutation normal selesai tanpa meninggalkan `shops.yml.admin.pending`.
- [ ] Runtime reload sukses → journal selesai sebagai `COMMITTED`.
- [ ] Success audit gagal setelah runtime sudah sukses → mutation tetap committed, recovery journal tidak dibiarkan ambigu.
- [ ] Reload candidate gagal lalu original + runtime rollback berhasil → journal selesai sebagai `ROLLED_BACK`.
- [ ] Pending journal + live hash sama dengan candidate saat startup → `RECOVERED_COMMITTED`, evidence diarsipkan.
- [ ] Pending journal + live hash sama dengan original → `RECOVERED_ABORTED`, evidence diarsipkan.
- [ ] Pending journal + live hash bukan original maupun candidate → admin mutation fail-closed dan marker tidak dibuang diam-diam.
- [ ] Pending/corrupt journal tidak menyebabkan config baru diterapkan tanpa bukti.
- [ ] Saat recovery blocked, seluruh config mutation admin ditolak.
- [ ] Saat recovery blocked, `/cve shop stock ...` juga ditolak.
- [ ] `/cve shop schema` dan `/cve shop validate` menampilkan status recovery sehingga admin tidak perlu menebak kondisi storage.
- [ ] Recovery yang berhasil meninggalkan history di `logs/admin-recovery.log` dan marker terakhir di `.pending.last`.

## Transactional Config Write

- [ ] Setiap perubahan config menyegarkan `shops.yml.admin.bak` sebelum live replace.
- [ ] Candidate divalidasi `ShopRegistry` sebelum mengganti file aktif.
- [ ] File `.admin.candidate` / `.admin.tmp` tidak tertinggal setelah operasi sukses.
- [ ] Candidate invalid tidak mengubah `shops.yml` aktif.
- [ ] Duplicate slot, out-of-range layout, mode/price invalid, dan stock bounds invalid tidak dapat di-commit.

## Administrative Audit

Local source of truth:

```text
plugins/CdrVephilimEconomy/logs/admin-audit.log
```

- [ ] Setiap mutation memiliki `*_REQUEST` sebelum perubahan.
- [ ] Mutation sukses memiliki `*_SUCCESS`.
- [ ] Mutation invalid/gagal memiliki `*_REJECTED` atau `*_FAILED`.
- [ ] Actor, action, detail tercatat.
- [ ] REQUEST audit gagal ditulis → mutation dibatalkan fail-closed.
- [ ] Stock change mencatat before/after.
- [ ] Migration/recovery system event meninggalkan audit bila local admin audit writable.

Discord admin audit:

- [ ] Disabled → tidak ada request network.
- [ ] Enabled + webhook valid mengirim success/failure/rejected secara async.
- [ ] `include-requests: false` tidak mengirim REQUEST.
- [ ] `include-requests: true` ikut mengirim REQUEST.
- [ ] Webhook URL tidak ditulis ke log.
- [ ] Discord error/offline tidak membatalkan mutation yang sudah aman secara lokal.

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

## Final Core Regression

- [ ] BUY 1 sukses.
- [ ] BUY bulk sukses.
- [ ] SELL 1 sukses.
- [ ] SELL bulk sukses.
- [ ] Restart mempertahankan stock.
- [ ] Restart mempertahankan shop/config v2.
- [ ] `/cve reload`, `/cve doctor`, `/cve safety status` tetap bekerja.
- [ ] Pending transaction journal dan persistent economy safety beta.1 tidak berubah.
- [ ] Upgrade RC2 → RC3 tidak mengubah balance atau runtime stock existing.

## RC3 Exit Criteria

RC3 lulus bila normal admin mutation tidak meninggalkan pending evidence, crash-window config mutation dapat direkonsiliasi secara deterministik atau fail-closed, migration v1→v2 dapat dipulihkan tanpa kehilangan definisi/stock, permission dan admin audit tetap benar, serta BUY/SELL beta.1 dan seluruh management RC1/RC2 tetap lolos regression.

Jika seluruh minimum runtime test aman, RC3 menjadi kandidat terakhir dan development dilanjutkan ke **`0.1.0-beta.2 FINAL`**, bukan menambah RC baru kecuali ditemukan bug nyata.
