# beta.4 RC1 Test Plan — CdrVephilimEconomy

Target build: `0.1.0-beta.4-RC1`.

## Preconditions

- Paper 1.21.11 / Java 21.
- Citizens + Vault + economy provider aktif.
- beta.3 FINAL baseline sehat.
- Minimal satu BUY_SELL listing dengan known stock/max-stock.
- Backup data folder sebelum destructive recovery test.

## Safe Upgrade Default

Resource baru memakai:

```yaml
enabled: false
```

- [ ] Upgrade dari beta.3 membuat `pricing.yml` bila belum ada.
- [ ] Dengan root `enabled: false`, harga BUY/SELL sama dengan static base price di `shops.yml`.
- [ ] `/cve status` menunjukkan pricing OFF.
- [ ] Core BUY 1/bulk dan SELL 1/bulk tetap sama seperti beta.3.

## Enable Dynamic Listing

Untuk staging, gunakan satu listing, misalnya `blacksmith/iron_ingot`:

```yaml
enabled: true
shops:
  blacksmith:
    iron_ingot:
      enabled: true
      target-stock-ratio: 0.50
      sensitivity: 0.50
      min-multiplier: 0.75
      max-multiplier: 1.50
```

- [ ] `/cve reload` sukses.
- [ ] `/cve status` menunjukkan pricing ON.
- [ ] `/cve shop info blacksmith` menunjukkan base -> effective quote.
- [ ] GUI menampilkan label harga dinamis dan market percentage.

## Quote Formula

Dengan base BUY 50 / SELL 30 dan max-stock 256:

- [ ] Stock 128 (50%) menghasilkan multiplier 1.00, BUY 50, SELL 30.
- [ ] Stock sangat rendah menaikkan quote, maksimal multiplier 1.50.
- [ ] Stock penuh menurunkan quote tetapi tidak melewati multiplier minimum 0.75.
- [ ] Harga efektif dibulatkan ke dua desimal.
- [ ] BUY dan SELL memakai multiplier yang sama sehingga spread base tetap proporsional.

## Transaction Execution

- [ ] BUY mengurangi saldo memakai effective quote, bukan base price.
- [ ] SELL menambah saldo memakai effective quote, bukan base price.
- [ ] Transaction audit mencatat effective unit price.
- [ ] Pending transaction journal, bila diperiksa pada staging failure drill, memakai effective unit price.
- [ ] Stock persistence tetap akurat setelah restart.

## Stale GUI Quote Guard

Gunakan dua player pada listing yang sama.

1. Player A dan B membuka GUI pada quote yang sama.
2. A melakukan transaksi sehingga stock berubah.
3. B klik item dari GUI lama.

Expected:

- [ ] B mendapat `PRICE_CHANGED` / pesan refresh harga.
- [ ] Uang B tidak berubah.
- [ ] Inventory B tidak berubah.
- [ ] Stock tidak berubah karena klik stale tersebut.
- [ ] GUI B direfresh dengan current quote.
- [ ] Klik berikutnya pada quote baru dapat berjalan normal.

## Bounds / Validation

Masing-masing candidate invalid berikut harus membuat reload ditolak dan runtime lama tetap aktif:

- [ ] `meta.schema` bukan 1.
- [ ] `target-stock-ratio < 0.05` atau `> 0.95`.
- [ ] `sensitivity < 0` atau `> 5`.
- [ ] `min-multiplier <= 0`.
- [ ] `max-multiplier < min-multiplier`.
- [ ] multiplier >10.
- [ ] malformed YAML.

Policy ke shop/listing tidak dikenal hanya menghasilkan warning dan tidak membuat core shop rusak.

## Static/Dynamic Isolation

- [ ] Listing tanpa policy tetap static.
- [ ] Policy listing `enabled: false` tetap static.
- [ ] Root `enabled: false` membuat seluruh listing static walaupun child policy enabled.
- [ ] Mengaktifkan satu listing tidak memengaruhi listing lain.

## Core Safety Regression

- [ ] Insufficient money ditolak sebelum mutation.
- [ ] Insufficient stock ditolak.
- [ ] Inventory full ditolak.
- [ ] Max stock SELL ditolak.
- [ ] Listing lock/in-flight guard tetap aktif.
- [ ] NPC proximity guard tetap aktif.
- [ ] Safety stop tetap memblokir transaksi.
- [ ] `/cve doctor`, `/cve safety status`, `/cve shop schema`, `/cve shop validate` tetap bekerja.

## Governance Regression

Dynamic quote tidak mengubah governance base-price management pada RC1.

- [ ] Staff/Manager price edit guardrail beta.3 tetap bekerja terhadap base price `shops.yml`.
- [ ] Approval queue beta.3 tetap bekerja.
- [ ] Rolling quota/cooldown governance tetap bekerja.
- [ ] Two-person extreme approval tetap bekerja.

## RC1 Exit Criteria

RC1 lulus bila default upgrade tidak mengubah harga, bounded quote mengikuti current stock, effective price dipakai konsisten oleh GUI/transaksi/journal/audit, stale quote tidak dapat mengeksekusi nilai yang tidak dilihat player, invalid pricing config tidak mengganti runtime sehat, dan regression beta.1-beta.3 tetap aman.
