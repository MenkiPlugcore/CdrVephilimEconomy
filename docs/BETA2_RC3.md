# CdrVephilimEconomy 0.1.0-beta.2-RC3

RC3 adalah kandidat hardening terakhir beta.2 sebelum final. Fokusnya bukan menambah fitur ekonomi baru, tetapi memastikan perubahan administratif dan migration `shops.yml` meninggalkan recovery evidence yang cukup bila server mati pada saat yang buruk.

## Added

- Durable administrative mutation journal `shops.yml.admin.pending`.
- Hash SHA-256 untuk `shops.yml` original dan candidate sebelum config live diganti.
- Stage journal `PREPARED`, `LIVE_REPLACED`, dan `RUNTIME_RELOADED`.
- Startup reconciliation otomatis untuk mutation admin yang terinterupsi.
- `shops.yml.admin.pending.last` sebagai bukti marker terakhir yang sudah direkonsiliasi.
- `logs/admin-recovery.log` untuk histori recovery admin mutation.
- Status admin mutation recovery ikut tampil pada `/cve shop schema` dan `/cve shop validate`.
- Schema migration journal `shops.yml.schema.pending` + archive `.schema.last`.
- SHA-256 verification terhadap backup migration dan candidate hasil migration.

## Changed

- Semua config mutation shop sekarang membuat journal durable sebelum atomic replace `shops.yml`.
- Setelah runtime reload sukses, journal diarsipkan sebagai `COMMITTED`.
- Jika runtime reload gagal tetapi rollback file + runtime berhasil, journal diarsipkan sebagai `ROLLED_BACK`.
- Jika hasil live file tidak dapat dibuktikan cocok dengan original/candidate, mutation admin masuk fail-closed.
- Runtime stock admin juga ditolak ketika administrative config recovery berada dalam kondisi ambigu.
- Legacy schema migration sekarang dapat mengenali pending migration yang tertinggal setelah crash.
- Pending migration dengan live schema v2 yang valid dianggap committed dan evidence diarsipkan.
- Pending migration dengan live legacy yang valid dianggap belum committed dan migration diulang.
- Bila live file rusak saat pending migration ada, backup migration yang valid dapat dipakai sebagai recovery source sebelum migration diulang.

## Fail-closed Rules

Admin mutation recovery tidak menebak konfigurasi yang benar. Jika hash live tidak sama dengan original maupun candidate, plugin mempertahankan marker dan memblokir mutation administratif. Tujuannya mencegah admin melakukan perubahan lanjutan di atas state config yang asal-usulnya tidak dapat dibuktikan.

Core BUY/SELL transaction safety beta.1 tidak diubah oleh RC3.

## Evidence Files

```text
shops.yml.schema-v1.bak
shops.yml.schema.pending
shops.yml.schema.last
shops.yml.admin.bak
shops.yml.admin.pending
shops.yml.admin.pending.last
logs/admin-recovery.log
logs/admin-audit.log
```

## RC3 QA Minimum

1. `/cve shop schema` harus menunjukkan schema v2/current dan `adminMutation=OK`.
2. `/cve shop validate` harus valid.
3. Ubah nama/harga/slot satu listing dan pastikan perubahan langsung aktif.
4. Pastikan setelah mutation sukses tidak ada `shops.yml.admin.pending` yang tertinggal.
5. Pastikan `shops.yml.admin.pending.last` dan `logs/admin-recovery.log` dapat muncul setelah recovery drill, bukan pada transaksi normal.
6. Restart server dan pastikan shop + stock tetap sama.
7. BUY 1, BUY bulk, SELL 1, SELL bulk tetap lolos.
8. `/cve doctor` dan `/cve safety status` tetap sehat.

## Exit Criteria

Jika RC3 lulus regression runtime dan recovery drill tanpa menghasilkan config parsial, stock reset, atau dupe, beta.2 dapat ditutup sebagai `0.1.0-beta.2` FINAL.
