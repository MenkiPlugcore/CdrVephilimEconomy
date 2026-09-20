# beta.3 RC4 Test Plan — CdrVephilimEconomy

Target build: `0.1.0-beta.3-RC4`.

RC4 adalah hardening candidate terakhir sebelum beta.3 FINAL. Fokus QA adalah memastikan role/scope RC1, approval RC2, rolling quota RC3, two-person approval RC4, dan core economy beta.1/beta.2 bekerja sebagai satu chain tanpa jalur bypass.

## Preconditions

- Paper 1.21.11 / Java 21.
- Citizens + Vault + economy provider aktif.
- Baseline beta.2 shop/stock sehat.
- Player test Staff, Manager, dan minimal satu Royal Treasurer sudah pernah join server.
- Untuk full two-person test, sediakan minimal dua reviewer berbeda.
- Minimal dua shop tersedia, misalnya `blacksmith` dan `farmer`.

## Startup / Health

- [ ] Plugin enable tanpa exception.
- [ ] `/cve status` menampilkan governance, approval, quota, dan dual approval.
- [ ] `/cve governance status` menampilkan keempat layer tersebut.
- [ ] `/cve doctor` tetap HEALTHY pada baseline normal.
- [ ] `/cve governance who <staff>` menampilkan assignment + rolling quota usage.
- [ ] `/cve shop schema` dan `/cve shop validate` tetap sehat.

## Role & Scope Regression

- [ ] ECONOMY_STAFF scope `blacksmith` hanya dapat capability Staff pada `blacksmith`.
- [ ] Staff `blacksmith` ditolak untuk mutation `farmer`.
- [ ] ECONOMY_MANAGER mendapat capability Manager sesuai scope.
- [ ] ROYAL_TREASURER scope `*` berlaku global.
- [ ] Permission beta.2 eksplisit tetap menjadi operator override.
- [ ] Grant/revoke tetap tercatat pada administrative audit.

## RC3 Runtime Wiring Regression — Wajib

RC4 memperbaiki wiring listener quota. Test ini memastikan proteksi RC3 benar-benar aktif, bukan hanya class/config tersedia.

Default Staff:

```yaml
window-minutes: 60
max-price-percent-sum: 40.0
max-stock-delta-sum: 256
cooldown-seconds: 15
```

Untuk staging, cooldown boleh sementara diturunkan lalu restart/plugin reload sesuai kebutuhan config runtime.

- [ ] Staff price `100 -> 120` direct sukses dan reserve sekitar 20 points.
- [ ] Command direct lain sebelum cooldown selesai ditolak oleh governance cooldown.
- [ ] Setelah cooldown, `120 -> 144` direct sukses; usage menjadi sekitar 40/40.
- [ ] Direct price mutation ketiga yang masih <=20% per operasi ditolak rolling quota.
- [ ] Naik lalu turun tetap dihitung absolute dan tidak mengurangi usage.
- [ ] Staff stock `ADD 128`, lalu setelah cooldown `REMOVE 64`, lalu `ADD 64` menghasilkan cumulative 256.
- [ ] Direct ADD/REMOVE berikutnya ditolak walaupun amount <=128.
- [ ] Manager memakai policy quota/cooldown Manager.
- [ ] `governance-usage.yml` dibuat dan survive restart.
- [ ] `GOVERNANCE_QUOTA_RESERVED` muncul pada admin audit bila writer tersedia.

## Quota Fail-Closed — STAGING

Runtime evidence:

```text
governance-usage.yml
governance-usage.yml.bak
governance-usage.yml.tmp
```

- [ ] Corrupt quota ledger lalu restart: quota status menjadi unhealthy.
- [ ] Direct role-only price/stock mutation diblokir, bukan berjalan tanpa quota.
- [ ] BUY/SELL player tetap bekerja bila core economy sehat.
- [ ] Royal Treasurer/full admin/explicit beta.2 permission tetap menyediakan recovery/operator path.
- [ ] Setelah file diperbaiki dan governance reload/restart, health kembali normal.

Jangan melakukan corrupt-file drill pada production.

## RC2 Approval Regression — Non-Extreme

Gunakan perubahan yang melewati role limit tetapi masih di bawah RC4 extreme threshold.

Contoh Staff harga `100 -> 150` = 50%.

- [ ] Mutation tidak berjalan langsung; approval ID dibuat.
- [ ] Requester tidak dapat approve/reject sendiri.
- [ ] Manager scope sama dapat approve Staff request non-extreme dengan satu review.
- [ ] Setelah approve, harga berubah dan status APPROVED.
- [ ] Reviewer tanpa scope ditolak.
- [ ] Peer/lower role ditolak sesuai hierarchy.
- [ ] Cancel/reject/expiry tidak mengeksekusi mutation.
- [ ] Harga berubah lewat admin sebelum approval membuat request stale dan tidak overwrite state terbaru.
- [ ] `EXECUTING` evidence + manual recovery RC2 tetap bekerja.

## RC4 Two-Person Price Approval

Default:

```yaml
governance:
  approval:
    two-person:
      enabled: true
      price-change-percent-threshold: 100.0
      price-from-zero-requires-two: true
      require-at-least-one-senior-reviewer: true
```

Contoh Staff harga `100 -> 250` = 150%.

- [ ] Staff command menghasilkan approval PENDING; harga belum berubah.
- [ ] Reviewer #1 yang valid menjalankan `/cve governance approval approve <id>`.
- [ ] Review #1 hanya mencatat first-review evidence; harga tetap belum berubah.
- [ ] `/cve governance approval show <id>` menunjukkan dual review WAITING_SECOND + reviewer pertama.
- [ ] `governance-dual-approval.yml` berisi evidence approval tersebut.
- [ ] Reviewer #1 mencoba approve lagi -> ditolak karena reviewer kedua harus berbeda.
- [ ] Reviewer #2 berbeda tetapi valid menjalankan command yang sama.
- [ ] Jika minimal satu reviewer senior terpenuhi, approval masuk executor RC2 lalu harga berubah.
- [ ] Setelah terminal APPROVED, first-review evidence aktif dibersihkan.
- [ ] `logs/governance-dual-approval-history.log` menyimpan bukti first review yang sudah selesai.

## Senior Reviewer Requirement

Senior reviewer default adalah:
- Royal Treasurer dengan matching scope;
- `cdrvephilimeconomy.governance.admin`;
- `cdrvephilimeconomy.admin`.

`cdrvephilimeconomy.governance.approve` saja bukan senior.

- [ ] Dua reviewer non-senior tidak dapat menyelesaikan extreme approval bila requirement aktif.
- [ ] Reviewer pertama non-senior + reviewer kedua Treasurer scope sesuai dapat menyelesaikan.
- [ ] Reviewer pertama Treasurer + reviewer kedua valid non-senior dapat menyelesaikan.
- [ ] Dua admin berbeda dapat menyelesaikan bila keduanya bukan requester.
- [ ] Requester tetap tidak dapat menjadi reviewer pertama ataupun kedua.

## Price From Zero

Dengan `price-from-zero-requires-two: true`:

- [ ] Staff/Manager request harga `0 -> nilai positif` menjadi approval.
- [ ] Reviewer pertama tidak mengeksekusi mutation.
- [ ] Reviewer kedua berbeda + senior policy terpenuhi baru dapat mengeksekusi.

## RC4 Two-Person Stock Approval

Default:

```yaml
stock-delta-threshold: 4096
stock-set-requires-two: true
```

- [ ] Staff/Manager runtime `SET` menghasilkan approval dan selalu membutuhkan two-person review.
- [ ] ADD/REMOVE amount >=4096 yang masuk approval membutuhkan two-person review.
- [ ] ADD/REMOVE sensitive tetapi <4096 tetap single-review RC2.
- [ ] Reviewer pertama tidak mengubah runtime stock.
- [ ] Reviewer kedua valid baru mengeksekusi lewat `ShopAdminService`.
- [ ] Bounds 0..max-stock tetap divalidasi saat execution.

## Distinct Reviewer Identity

- [ ] Player UUID yang sama tidak dapat menjadi reviewer #1 dan #2 walaupun nama/display berubah.
- [ ] Console/operator identity tidak dapat dipakai dua kali sebagai dua reviewer berbeda.
- [ ] Reviewer requester tetap ditolak sebelum first-review evidence dibuat.

## Fingerprint / Evidence Integrity — STAGING ONLY

First review menyimpan fingerprint SHA-256 immutable approval request fields.

- [ ] Buat extreme approval dan record first review.
- [ ] Restart server: first-review evidence tetap ada.
- [ ] Approval kedua setelah restart masih membutuhkan reviewer berbeda.
- [ ] Pada staging, ubah immutable approval request fields secara manual lalu reload/restart.
- [ ] Fingerprint mismatch menghapus/menginvalidasi first review lama dan review harus dimulai ulang.
- [ ] First-review lama tidak dapat dipakai untuk request target yang berubah.

## Dual Approval Storage Fail-Closed — STAGING ONLY

Runtime evidence:

```text
governance-dual-approval.yml
governance-dual-approval.yml.bak
governance-dual-approval.yml.tmp
logs/governance-dual-approval-history.log
```

- [ ] Corrupt `governance-dual-approval.yml` lalu restart/reload.
- [ ] Dual approval status menjadi unhealthy.
- [ ] Extreme approval diblokir fail-closed.
- [ ] BUY/SELL core tetap aktif bila core storage sehat.
- [ ] Non-extreme RC2 approval tidak bergantung pada first-review storage dan tetap mengikuti normal approval policy.
- [ ] Setelah storage diperbaiki + reload, extreme approval dapat direview lagi.

## Approval Crash Recovery Regression — STAGING ONLY

- [ ] `EXECUTING` approval pada startup tetap membuat approval subsystem `recoveryBlocked=true`.
- [ ] Two-person layer tidak otomatis replay mutation.
- [ ] Admin merekonsiliasi shop/stock/admin audit.
- [ ] `/cve governance approval recover <id> executed CONFIRM` bekerja.
- [ ] `/cve governance approval recover <id> not-executed CONFIRM` bekerja.
- [ ] Terminal recovery membersihkan first-review evidence bila ada.

## Config Toggle Regression

- [ ] `two-person.enabled: false` mengembalikan approval ke RC2 single-review behavior.
- [ ] Threshold price dapat diubah dan dipatuhi setelah config runtime diperbarui dengan prosedur reload yang didukung.
- [ ] `stock-set-requires-two: false` membuat SET tetap approval RC2 tetapi tidak memerlukan reviewer kedua.
- [ ] `require-at-least-one-senior-reviewer: false` tetap membutuhkan dua reviewer berbeda, tetapi tidak mensyaratkan senior.

## Administrative Audit

Pastikan event berikut muncul sesuai flow:

```text
GOVERNANCE_QUOTA_RESERVED
GOV_DUAL_APPROVAL_FIRST_REVIEW_REQUEST
GOV_DUAL_APPROVAL_FIRST_REVIEW_RECORDED
GOV_DUAL_APPROVAL_SECOND_REVIEW_READY
GOV_DUAL_APPROVAL_FIRST_REVIEW_CLEARED
GOV_APPROVAL_APPROVE_REQUEST
GOV_APPROVAL_EXECUTE_SUCCESS
```

- [ ] First-review audit REQUEST gagal -> first review tidak dicatat.
- [ ] Second-review audit gagal -> mutation tidak dijalankan oleh dual gate.
- [ ] Discord administrative audit aktif dapat menerima event governance secara async.

## Core Economy Regression

- [ ] BUY 1 sukses.
- [ ] BUY bulk sukses.
- [ ] SELL 1 sukses.
- [ ] SELL bulk sukses.
- [ ] Inventory full/stock 0/max-stock validation tetap benar.
- [ ] Stock restart persistence tetap benar.
- [ ] NPC proximity guard tetap bekerja.
- [ ] `/cve safety status` tetap normal.
- [ ] Pending transaction journal/safety recovery beta.1 tidak berubah.

## Shop Management Regression

- [ ] `/cve shop list` dan `info` normal.
- [ ] Edit satu price/listing oleh admin normal.
- [ ] Bind/unbind NPC normal.
- [ ] Enable/disable normal.
- [ ] Runtime stock admin normal.
- [ ] `shops.yml` admin mutation journal/recovery beta.2 tidak berubah.

## RC4 Exit Criteria

RC4 lulus jika:

1. quota RC3 terbukti benar-benar aktif pada command runtime;
2. repeated small mutation tidak dapat melewati rolling quota/cooldown;
3. non-extreme approval tetap single-review sesuai RC2;
4. extreme approval membutuhkan dua reviewer berbeda;
5. default policy memerlukan minimal satu senior reviewer;
6. first-review evidence persisten, fingerprint-bound, dan dibersihkan setelah terminal state;
7. corrupt quota/dual storage fail-closed pada layer terkait tanpa mematikan core BUY/SELL;
8. approval EXECUTING crash recovery tetap aman;
9. tidak ada regression pada core transaksi atau beta.2 Shop Management.

Jika kriteria ini aman pada runtime, next step adalah `0.1.0-beta.3 FINAL`. Tidak perlu RC5 kecuali ditemukan bug nyata.
