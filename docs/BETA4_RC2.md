# CdrVephilimEconomy 0.1.0-beta.4-RC2

RC2 mengeraskan **Controlled Dynamic Pricing** dengan quote sampling/cooldown, durable market state, dan anti BUY/SELL churn.

## Perubahan Utama

### Durable Market State

Dynamic multiplier tidak lagi dihitung ulang menjadi harga pasar baru pada setiap perubahan stock. Runtime menyimpan sample terakhir ke:

```text
market-state.yml
market-state.yml.bak
market-state.yml.tmp
```

Per listing disimpan:

- sampled stock;
- sampled multiplier;
- sampled-at timestamp;
- policy fingerprint;
- sample count.

State bertahan lintas restart sehingga restart tidak dapat dipakai untuk mereset market cooldown.

### Quote Sampling

Default `pricing.yml`:

```yaml
stability:
  quote-cooldown-seconds: 30
  min-stock-change-to-resample: 8
  reversal-cooldown-seconds: 15
```

Multiplier baru hanya dipersist bila:

1. belum ada sample sebelumnya; atau
2. policy fingerprint berubah; atau
3. cooldown sudah lewat **dan** stock berubah minimal sebesar `min-stock-change-to-resample` dari sample terakhir.

Artinya BUY/SELL kecil yang terjadi cepat tidak membuat harga bergerak setiap klik.

### Policy Fingerprint

Sample market diikat ke fingerprint parameter pricing + `max-stock`. Jika target, sensitivity, min/max multiplier, atau max-stock berubah, sample lama tidak dipakai sebagai state yang sah dan harga disampling ulang segera.

### Anti Churn

Untuk listing dynamic, player yang baru melakukan BUY tidak dapat langsung SELL listing yang sama, dan sebaliknya, selama `reversal-cooldown-seconds`.

Guard ini hanya menahan **opposite-direction reversal**. BUY berulang sebagai demand nyata atau SELL berulang sebagai supply nyata tidak diblokir oleh guard tersebut.

### Fail-Closed Dynamic Layer

Jika `market-state.yml` corrupt atau tidak dapat dipersist:

- dynamic pricing masuk status `BLOCKED`;
- quote kembali ke static base price dari `shops.yml`;
- core BUY/SELL tidak dimatikan hanya karena market-state gagal;
- file state tidak ditimpa diam-diam dengan market baru.

Dengan demikian kerusakan layer dynamic tidak boleh membuat harga liar atau membuat supply/transaction baseline ikut rusak.

## Stale Quote Safety

Proteksi RC1 tetap aktif. GUI menyimpan quote yang dilihat player dan TransactionService menghitung ulang quote sebelum mutation. Jika sampled market berubah sebelum klik, transaksi ditolak sebagai `PRICE_CHANGED` sebelum uang/item/stock berubah dan GUI direfresh.

## Compatibility

- `pricing.yml` RC1 tanpa section `stability` tetap valid dan menggunakan default RC2.
- Dynamic pricing tetap default OFF untuk instalasi/upgrade baru.
- Base price tetap berada di `shops.yml`.
- beta.1 transaction journal/safety lock, beta.2 Shop Management, dan beta.3 Governance tidak diubah kontraknya.

## Scope Berikutnya

RC2 belum mencakup statistik market/volume transaksi atau governance khusus untuk mutation parameter `pricing.yml`. Itu masuk RC berikutnya.
