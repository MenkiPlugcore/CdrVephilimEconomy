# CdrVephilimEconomy 0.1.0-beta.4-RC1

RC1 membuka fase **Controlled Dynamic Pricing** tanpa mengubah frozen transaction/governance contract beta.1-beta.3.

## Tujuan RC1

Harga dapat merespons kelangkaan atau kelebihan stock secara terkontrol. Base price tetap berada di `shops.yml`; `pricing.yml` hanya menentukan bagaimana base price dikalikan oleh market multiplier.

Upgrade aman secara default karena dynamic pricing **OFF** pada resource awal.

## pricing.yml

```yaml
meta:
  schema: 1

enabled: false

shops:
  blacksmith:
    iron_ingot:
      enabled: false
      target-stock-ratio: 0.50
      sensitivity: 0.50
      min-multiplier: 0.75
      max-multiplier: 1.50
```

Master switch dan listing switch harus sama-sama `true` agar sebuah listing memakai harga dinamis.

## Formula RC1

`stockRatio = currentStock / maxStock`

Jika stock berada di bawah target, pressure positif. Jika stock berada di atas target, pressure negatif. Pressure dinormalisasi ke rentang `-1..1`.

```text
rawMultiplier = 1 + sensitivity * pressure
effectivePrice = basePrice * clamp(rawMultiplier, minMultiplier, maxMultiplier)
```

Harga efektif dibulatkan ke dua angka desimal.

Dengan contoh base BUY `50`, base SELL `30`, target `50%`, sensitivity `0.50`, min `0.75`, max `1.50`:

```text
stock 0%   -> multiplier 1.50 -> BUY 75.00 / SELL 45.00
stock 50%  -> multiplier 1.00 -> BUY 50.00 / SELL 30.00
stock 100% -> raw 0.50, clamped 0.75 -> BUY 37.50 / SELL 22.50
```

BUY dan SELL memakai multiplier yang sama sehingga spread base price tetap proporsional.

## Transaction Safety

GUI menyimpan harga yang benar-benar dilihat player sebagai metadata internal pada item shop. Saat player klik, `TransactionService` menghitung ulang quote di dalam listing transaction lock.

Jika stock berubah dan current quote berbeda dari harga yang dilihat player:

```text
GUI quote lama
   -> listing lock
   -> recompute current quote
   -> mismatch
   -> PRICE_CHANGED
   -> NO money mutation
   -> NO item mutation
   -> NO stock mutation
   -> refresh GUI
```

Dengan demikian player tidak diam-diam dibebankan harga pasar yang berubah setelah GUI dirender.

Journal transaksi menyimpan **effective unit price aktual**, bukan base price, sehingga crash recovery dan audit tetap merepresentasikan nilai ekonomi yang benar-benar dieksekusi.

## Runtime / Reload Contract

- `pricing.yml` dibuat otomatis pada install RC1 bila belum ada.
- Schema yang bukan v1 ditolak.
- Nilai non-finite atau policy di luar bound ditolak.
- Startup dengan `pricing.yml` invalid dinonaktifkan secara aman.
- `/cve reload` dengan candidate `pricing.yml` invalid dibatalkan dan runtime lama tetap dipakai.
- Policy yang menunjuk shop/listing tidak dikenal menghasilkan warning.
- `/cve status` menampilkan status pricing.
- `/cve shop info <shop>` menampilkan base -> effective quote untuk listing dinamis.

## Batas Valid RC1

```text
target-stock-ratio : 0.05 - 0.95
sensitivity        : 0 - 5
min-multiplier     : >0 - 10
max-multiplier     : >0 - 10 dan >= min-multiplier
```

## Belum Termasuk RC1

RC1 sengaja belum memiliki persistent market sampling/cooldown. Harga efektif dihitung langsung dari persisted current stock. Fitur berikut dipindahkan ke RC berikutnya:

- cooldown perubahan market quote;
- durable market state lintas restart;
- anti BUY/SELL churn/manipulation;
- volume/price statistics;
- governance approval untuk edit policy dynamic pricing.

## Compatibility

Saat `pricing.yml.enabled=false`, effective price sama persis dengan static base price beta.3. Core BUY/SELL, stock persistence, transaction journal, safety lock, Shop Management, dan Governance tidak berubah kontraknya.
