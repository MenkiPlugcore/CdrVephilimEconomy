# beta.3 RC3 Test Plan — CdrVephilimEconomy

Target build: `0.1.0-beta.3-RC3`.

## Preconditions

- Paper 1.21.11 / Java 21.
- Citizens + Vault + economy provider aktif.
- Baseline beta.2 shop/stock sehat.
- Player test Staff, Manager, dan Treasurer sudah pernah join server.
- Minimal dua shop tersedia, misalnya `blacksmith` dan `farmer`.

## Governance Storage

- [ ] `governance.yml` schema v1 tetap sehat.
- [ ] `/cve governance status` menunjukkan governance healthy=true.
- [ ] Governance YAML corrupt membuat role-based governance fail-closed.
- [ ] Full admin tetap dapat recovery.
- [ ] `/cve governance reload` memuat ulang governance + quota ledger.

## Role & Scope Regression

- [ ] ECONOMY_STAFF `blacksmith` hanya dapat mutation capability Staff pada `blacksmith`.
- [ ] Mutation `farmer` ditolak.
- [ ] ECONOMY_MANAGER mendapat capability Manager sesuai scope.
- [ ] ROYAL_TREASURER scope `*` berlaku global.
- [ ] Permission beta.2 eksplisit tetap menjadi operator override.

## Approval RC2 Regression

- [ ] Staff price change >20% membuat approval, tidak direct.
- [ ] Manager price change >50% membuat approval.
- [ ] Staff stock ADD/REMOVE >128 membuat approval.
- [ ] Manager stock ADD/REMOVE >1024 membuat approval.
- [ ] Staff/Manager stock SET membuat approval.
- [ ] Anti-self-approval tetap aktif.
- [ ] Reviewer hierarchy + scope tetap benar.
- [ ] Approval expiry/cancel/reject tetap benar.
- [ ] `EXECUTING` evidence dan manual recovery tetap fail-closed.

## RC3 Quota Storage

Runtime evidence baru:

```text
governance-usage.yml
governance-usage.yml.bak
governance-usage.yml.tmp
```

- [ ] Startup pertama membuat `governance-usage.yml` schema v1.
- [ ] Mutation direct berikutnya membuat `.bak`.
- [ ] `/cve governance status` menampilkan quota healthy=true.
- [ ] `/cve governance who <player>` menampilkan window, used price, used stock, cooldown, dan last mutation.
- [ ] Corrupt `governance-usage.yml` membuat direct role-only price/stock fail-closed.
- [ ] BUY/SELL core tetap aktif bila quota ledger rusak.
- [ ] Admin/Treasurer/operator permission tetap dapat recovery/operasi.

## ECONOMY_STAFF Price Rolling Quota

Default:

```yaml
window-minutes: 60
max-price-percent-sum: 40.0
cooldown-seconds: 15
```

Untuk testing cepat, cooldown boleh sementara diturunkan di staging lalu `/cve reload`.

- [ ] Harga 100 -> 120 memakai sekitar 20 quota points.
- [ ] Setelah cooldown, 120 -> 144 memakai sekitar 20 points lagi.
- [ ] Usage menjadi sekitar 40/40.
- [ ] Direct price change berikutnya yang masih <=20% per operasi diblokir rolling quota.
- [ ] Perubahan >20% tetap masuk approval RC2 dan tidak dianggap direct quota reservation.
- [ ] Perubahan harga no-op tidak mengonsumsi quota.
- [ ] Price quota dihitung absolute; naik/turun tetap menambah usage, sehingga oscillation tidak mengurangi quota.

## ECONOMY_MANAGER Price Rolling Quota

Default:

```yaml
window-minutes: 60
max-price-percent-sum: 100.0
cooldown-seconds: 5
```

- [ ] Beberapa direct change <=50% dapat berjalan sampai cumulative usage mendekati 100.
- [ ] Direct mutation yang membuat cumulative usage >100 diblokir.
- [ ] >50% per operasi tetap masuk approval tanpa direct quota reservation.

## Runtime Stock Rolling Quota

Default Staff: 256 per 60 menit. Default Manager: 4096 per 60 menit.

- [ ] Staff ADD 128 direct sukses dan reserve 128.
- [ ] Setelah cooldown, REMOVE 64 direct sukses dan cumulative usage 192.
- [ ] Setelah cooldown, ADD 64 direct sukses dan cumulative usage 256.
- [ ] Direct ADD/REMOVE berikutnya diblokir walaupun nilai per operasi <=128.
- [ ] Staff ADD/REMOVE >128 tetap masuk approval RC2, bukan rolling direct quota.
- [ ] Staff SET tetap masuk approval RC2.
- [ ] Manager menggunakan limit rolling 4096 dengan pola yang sama.

## Cooldown Anti-Burst

- [ ] Setelah satu direct mutation Staff, direct price/stock mutation berikutnya dalam <15 detik diblokir.
- [ ] Setelah cooldown lewat, mutation dapat lanjut jika rolling quota masih tersedia.
- [ ] Manager default cooldown 5 detik.
- [ ] Cooldown berlaku lintas price dan stock, bukan hanya command yang sama.
- [ ] Per-operation sensitive change yang diarahkan ke approval tidak membuat direct quota reservation.

## Permission / Role Bypass

- [ ] `cdrvephilimeconomy.admin` bypass rolling quota.
- [ ] Royal Treasurer bypass rolling quota.
- [ ] `cdrvephilimeconomy.shop.price` eksplisit bypass price quota.
- [ ] `cdrvephilimeconomy.shop.stock` eksplisit bypass stock quota.
- [ ] Bypass tetap tercakup administrative audit dari mutation shop normal.

## Persistence / Restart

- [ ] Setelah direct mutation, restart server.
- [ ] Rolling usage tetap terbaca dari `governance-usage.yml`.
- [ ] Restart tidak mereset cooldown/window abuse protection secara diam-diam.
- [ ] Event yang sudah keluar retention tidak memengaruhi quota baru.

## Conservative Reservation

Reservation dibuat sebelum mutation shop dieksekusi.

- [ ] Reservation muncul di ledger sebelum mutation direct diproses.
- [ ] Event `GOVERNANCE_QUOTA_RESERVED` muncul di `logs/admin-audit.log` bila writer tersedia.
- [ ] Jika downstream mutation sengaja dibuat gagal di staging, quota reservation tetap ada sampai window expiry.
- [ ] Kegagalan persistence quota membuat direct role mutation fail-closed, bukan dijalankan tanpa evidence.

## Core Regression

- [ ] BUY 1 sukses.
- [ ] BUY bulk sukses.
- [ ] SELL 1 sukses.
- [ ] SELL bulk sukses.
- [ ] Stock restart persistence tetap benar.
- [ ] `/cve shop schema`, `/cve shop validate`, `/cve doctor`, `/cve safety status` tetap bekerja.
- [ ] Pending transaction/admin mutation recovery beta.1/beta.2 tidak berubah.

## RC3 Exit Criteria

RC3 lulus bila rolling quota bertahan lintas restart, repeated small mutations tidak dapat melewati cumulative limit, cooldown mencegah command burst, sensitive per-operation changes tetap menggunakan approval RC2, permission/operator bypass tetap backward compatible, corrupt quota ledger fail-closed hanya pada direct role mutation, dan core BUY/SELL tidak regression.
