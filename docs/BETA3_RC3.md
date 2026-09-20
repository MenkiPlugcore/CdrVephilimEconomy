# CdrVephilimEconomy 0.1.0-beta.3-RC3

RC3 menambahkan **rolling governance quota + cooldown anti-abuse** di atas role/scope RC1 dan sensitive approval RC2.

Tujuan utama RC3 adalah menutup bypass limit per operasi. Sebelum RC3, Staff dapat melakukan beberapa perubahan kecil yang masing-masing masih di bawah 20%/128 tetapi totalnya menjadi besar dalam waktu singkat. RC3 menghitung direct mutation secara kumulatif dalam rolling window.

## Default Policy

```yaml
governance:
  quota:
    enabled: true
    retention-hours: 48

    economy-staff:
      window-minutes: 60
      max-price-percent-sum: 40.0
      max-stock-delta-sum: 256
      cooldown-seconds: 15

    economy-manager:
      window-minutes: 60
      max-price-percent-sum: 100.0
      max-stock-delta-sum: 4096
      cooldown-seconds: 5
```

Quota hanya berlaku pada **role-only direct mutation**. Full admin, Royal Treasurer, dan staff yang sengaja diberi permission beta.2 eksplisit tetap dianggap operator override.

## Price Rolling Quota

Impact harga dihitung sebagai absolute percentage dari harga saat ini.

Contoh Staff:

```text
100 -> 120 = 20 quota points
120 -> 144 = 20 quota points
Total rolling usage = 40 / 40
```

Perubahan direct berikutnya diblokir sampai event lama keluar dari rolling window.

Perubahan yang sejak awal melebihi limit per operasi RC1 tetap masuk approval RC2 dan tidak menggunakan direct quota reservation.

## Stock Rolling Quota

Untuk runtime stock, quota menghitung total absolute delta direct ADD/REMOVE.

Contoh Staff:

```text
ADD 128    = 128
REMOVE 64  = 64
ADD 64     = 64
Total      = 256 / 256
```

`SET` dan delta di atas limit per operasi tetap masuk approval queue RC2.

## Cooldown

Cooldown bersifat global per economy staff account untuk direct price/stock mutation.

Default:
- Economy Staff: 15 detik.
- Economy Manager: 5 detik.

Tujuannya mencegah command burst dan perubahan beruntun terlalu cepat meskipun rolling quota masih tersedia.

## Durable Usage Evidence

RC3 menambah:

```text
governance-usage.yml
governance-usage.yml.bak
governance-usage.yml.tmp
```

Setiap direct mutation yang lolos quota membuat reservation durable sebelum command mutation dieksekusi. Event menyimpan:
- timestamp;
- player/role;
- kind `PRICE_PERCENT` atau `STOCK_DELTA`;
- quota weight;
- shop/listing;
- detail mutation.

Reservation juga meninggalkan event `GOVERNANCE_QUOTA_RESERVED` di administrative audit ketika writer tersedia.

## Conservative Reservation

Quota reservation dipersist sebelum mutation shop dilakukan. Ini disengaja untuk fail-closed anti-abuse.

Jika downstream mutation ternyata gagal setelah reservation berhasil, quota reservation tetap dihitung sampai rolling window berakhir. Konsekuensinya adalah kemungkinan false-positive quota charge sementara, tetapi retry/crash tidak dapat dipakai untuk memperoleh mutation gratis dari quota.

## Corrupt / Unwritable Ledger

Jika `governance-usage.yml` tidak dapat dibaca atau reservation tidak dapat dipersist:
- governance assignment tetap dapat dibaca;
- BUY/SELL core tidak dihentikan;
- approval subsystem tidak otomatis rusak;
- **direct role-only price/stock mutation diblokir fail-closed**;
- admin/operator override tetap tersedia untuk recovery.

`/cve governance status` menampilkan health quota bersama governance health. `/cve governance who <player>` menampilkan rolling usage player sesuai role saat ini.

## Interaction dengan Approval RC2

RC3 tidak mengganti approval queue.

- per-operation sensitive change -> approval RC2;
- direct change yang masih kecil -> rolling quota RC3;
- rolling quota/cooldown habis -> direct mutation diblokir sampai window/cooldown pulih atau operator melakukan perubahan;
- approved mutation dieksekusi melalui approval workflow dan tidak dianggap direct role mutation.

## Next

Setelah RC3 runtime QA, beta.3 tinggal hardening governance terakhir: opsi two-person approval untuk perubahan sangat sensitif, regression/security audit, lalu finalisasi `0.1.0-beta.3`.
