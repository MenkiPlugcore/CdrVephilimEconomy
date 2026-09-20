# beta.3 RC2 Test Plan — CdrVephilimEconomy

Target build: `0.1.0-beta.3-RC2`.

## Preconditions

- Paper 1.21.11 / Java 21.
- Citizens + Vault + economy provider aktif.
- Baseline beta.2 shop/stock sehat.
- Player test Staff, Manager, dan Treasurer sudah pernah join server.
- Minimal dua shop tersedia, misalnya `blacksmith` dan `farmer`.

## Governance Storage

- [ ] First startup membuat `governance.yml` schema v1.
- [ ] `governance.yml.bak` muncul setelah mutation berikutnya.
- [ ] `/cve governance status` menunjukkan governance healthy=true.
- [ ] YAML governance corrupt membuat role-based governance fail-closed.
- [ ] `cdrvephilimeconomy.admin` tetap dapat recovery walaupun governance storage rusak.
- [ ] `/cve governance reload` memuat ulang file yang sudah diperbaiki.

## Role Assignment & Scope Regression

- [ ] Grant Staff ke `blacksmith` sukses.
- [ ] Grant Manager/Treasurer sesuai scope sukses.
- [ ] Staff `blacksmith` ditolak untuk mutation `farmer`.
- [ ] Scope `*` berlaku global.
- [ ] Permission beta.2 eksplisit tetap menjadi backward-compatible override.
- [ ] Grant/revoke tetap tercatat di `logs/admin-audit.log`.

## Approval Storage

Runtime evidence:

```text
governance-approvals.yml
governance-approvals.yml.bak
governance-approvals.yml.tmp
```

- [ ] Startup pertama membuat `governance-approvals.yml` schema v1.
- [ ] Mutation approval berikutnya membuat `.bak`.
- [ ] `/cve governance approval status` menunjukkan healthy=true, recoveryBlocked=false.
- [ ] `/cve status` dan `/cve doctor` tetap berfungsi; output command menampilkan ringkasan approval.
- [ ] Approval YAML corrupt membuat approval subsystem fail-closed tanpa mematikan BUY/SELL core.
- [ ] `/cve governance approval reload` setelah file diperbaiki mengembalikan health.

## Price Approval — ECONOMY_STAFF

Default Staff limit: 20% per operasi.

- [ ] Price change <=20% langsung sukses tanpa approval.
- [ ] Price change >20% tidak langsung dieksekusi; menghasilkan approval ID.
- [ ] Harga belum berubah sebelum approval disetujui.
- [ ] `/cve governance approval list` menampilkan request.
- [ ] `/cve governance approval show <id>` menampilkan requester, role, shop/listing, before/target, expiry.
- [ ] Requester tidak dapat approve request sendiri.
- [ ] Requester dapat `/cve governance approval cancel <id>`.
- [ ] ECONOMY_MANAGER pada scope yang sama dapat approve request Staff.
- [ ] Manager tanpa scope shop terkait ditolak.
- [ ] Setelah approve, harga berubah ke target dan status request menjadi APPROVED.
- [ ] Jika harga berubah lewat admin sebelum approve, approval dianggap stale dan tidak menimpa harga terbaru.
- [ ] Perubahan harga dari base 0 menghasilkan approval, bukan mutation langsung.

## Price Approval — ECONOMY_MANAGER

Default Manager limit: 50% per operasi.

- [ ] Price change <=50% langsung sukses.
- [ ] Price change >50% membuat approval request.
- [ ] Manager tidak dapat approve request miliknya sendiri.
- [ ] Manager peer dengan role sama tidak dapat approve request tersebut.
- [ ] ROYAL_TREASURER dengan scope sesuai dapat approve.
- [ ] Full admin / `cdrvephilimeconomy.governance.approve` dapat review sebagai operator override.

## Runtime Stock Approval

Default limits: Staff 128, Manager 1024.

- [ ] Staff ADD/REMOVE <=128 langsung sukses.
- [ ] Staff ADD/REMOVE >128 menghasilkan approval.
- [ ] Staff `SET` berapa pun menghasilkan approval.
- [ ] Manager ADD/REMOVE <=1024 langsung sukses.
- [ ] Manager ADD/REMOVE >1024 menghasilkan approval.
- [ ] Manager `SET` menghasilkan approval.
- [ ] Approved stock request dieksekusi memakai validation `ShopAdminService`; stock tidak boleh keluar dari 0..max-stock.
- [ ] Bila stock berubah sebelum approval, ADD/REMOVE diterapkan relatif terhadap stock saat approve; bounds tetap divalidasi.
- [ ] Treasurer/admin dapat melakukan SET langsung sesuai capability/permission tanpa approval role guardrail.

## Approval Expiry / Queue Limits

Default config:

```yaml
governance:
  approval:
    enabled: true
    expiry-minutes: 10
    max-pending-per-requester: 5
```

- [ ] Pending request expired otomatis berubah menjadi EXPIRED ketika approval subsystem diakses setelah expiry.
- [ ] EXPIRED request tidak dapat diapprove.
- [ ] Request target yang sama oleh requester yang sama tidak dapat diduplikasi saat masih PENDING.
- [ ] Requester tidak dapat melewati `max-pending-per-requester`.
- [ ] `approval.enabled: false` membuat perubahan sensitif ditolak karena queue dinonaktifkan; tidak ada mutation langsung.

## Reject / Cancel

- [ ] Reviewer yang memiliki hierarchy/scope dapat reject request.
- [ ] Anti-self-approval juga berlaku pada reject; requester memakai cancel untuk membatalkan request sendiri.
- [ ] Cancel oleh requester mengubah status menjadi CANCELLED.
- [ ] Governance admin dapat cancel pending request untuk recovery/operasional.
- [ ] REJECTED/CANCELLED tidak mengeksekusi mutation shop.

## Crash-window Approval Recovery — STAGING ONLY

Approval memakai state `EXECUTING` yang dipersist **sebelum** ShopAdminService dipanggil.

- [ ] Buat pending approval pada staging.
- [ ] Simulasikan/siapkan `EXECUTING` evidence sebelum restart.
- [ ] Startup mendeteksi EXECUTING dan status menjadi `recoveryBlocked=true`.
- [ ] Approval baru/approve/reject/cancel diblokir sampai evidence diselesaikan.
- [ ] Admin memeriksa `shops.yml`, stock, dan `logs/admin-audit.log` untuk menentukan apakah mutation sudah dieksekusi.
- [ ] Jika sudah dieksekusi: `/cve governance approval recover <id> executed CONFIRM`.
- [ ] Jika belum dieksekusi: `/cve governance approval recover <id> not-executed CONFIRM`.
- [ ] Setelah semua EXECUTING evidence selesai, recoveryBlocked kembali false.
- [ ] Recovery tidak otomatis replay mutation.

Jangan melakukan crash/recovery drill destruktif di server production.

## Audit

`logs/admin-audit.log` harus mencatat event relevan:

```text
GOV_APPROVAL_REQUEST
GOV_APPROVAL_APPROVE_REQUEST
GOV_APPROVAL_EXECUTE_SUCCESS
GOV_APPROVAL_EXECUTE_FAILED
GOV_APPROVAL_REJECTED
GOV_APPROVAL_CANCELLED
GOV_APPROVAL_EXPIRED
GOV_APPROVAL_RECOVERY_RESOLVED
```

- [ ] Discord administrative audit aktif menerima event approval secara async.
- [ ] Kegagalan menulis REQUEST audit membuat approval request/review dibatalkan fail-closed.

## Core Regression

- [ ] BUY 1 sukses.
- [ ] BUY bulk sukses.
- [ ] SELL 1 sukses.
- [ ] SELL bulk sukses.
- [ ] Stock restart persistence tetap benar.
- [ ] `/cve shop schema`, `/cve shop validate`, `/cve doctor`, `/cve safety status` tetap bekerja.
- [ ] Pending transaction/admin mutation recovery beta.1/beta.2 tidak berubah.

## RC2 Exit Criteria

RC2 lulus bila sensitive price/stock change membentuk durable approval request, hierarchy + scope reviewer benar, self-approval tidak mungkin, expiry/cancel bekerja, crash-window `EXECUTING` tidak dapat direplay otomatis, recovery manual meninggalkan evidence, permission beta.2 tetap kompatibel, dan core BUY/SELL tidak regression.
