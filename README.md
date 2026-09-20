# CdrVephilimEconomy

`CdrVephilimEconomy` adalah plugin economy khusus **Vephilim Roleplay** dengan pendekatan NPC-first: player berdagang melalui Citizens NPC, sedangkan plugin menangani stock, harga, transaksi, recovery, audit, shop management, dan governance staff.

## Status

- **Current stable development baseline:** `0.1.0-beta.3`
- **Frozen Governance baseline:** `0.1.0-beta.3`
- **Frozen Shop Management baseline:** `0.1.0-beta.2`
- **Frozen Core Economy baseline:** `0.1.0-beta.1`
- Branch development: `dev/beta.3`

beta.3 sudah ditutup sebagai baseline governance setelah RC1 role/scope, RC2 sensitive-change approval, RC3 rolling quota + cooldown, dan RC4 two-person approval + final security hardening.

## Core Economy

- Citizens NPC sebagai front-end transaksi.
- BUY / SELL / BUY_SELL per listing.
- Persistent real stock dan static pricing.
- Vault economy bridge.
- Transaction journal + persistent safety lock.
- Fail-closed recovery untuk stock/pending transaction.
- Local/Discord transaction audit.
- `/cve doctor`, `/cve safety status`, explicit recovery.

## Shop Management beta.2

```text
/cve shop list
/cve shop info <shop>
/cve shop schema
/cve shop validate
/cve shop create <id> [size] [display name]
/cve shop delete <id> CONFIRM
/cve shop name <shop> <display name>
/cve shop size <shop> <size>
/cve shop bind <shop> <npc-id|-1>
/cve shop enable <shop>
/cve shop disable <shop>
/cve shop manager <shop> <name|none>
/cve shop additem <shop> <listing> <material|hand> <slot> <mode> <buy> <sell> <initial> <max>
/cve shop removeitem <shop> <listing> CONFIRM
/cve shop mode <shop> <listing> <BUY|SELL|BUY_SELL>
/cve shop slot <shop> <listing> <slot>
/cve shop price <shop> <listing> <buy|sell> <value>
/cve shop initialstock <shop> <listing> <value>
/cve shop maxstock <shop> <listing> <value>
/cve shop stock <shop> <listing> <set|add|remove> <amount>
```

Shop management memakai `shops.yml` schema v2, candidate validation, backup, admin mutation journal, local administrative audit, dan optional Discord administrative audit.

## beta.3 — Economy Staff & Governance

Role internal:

```text
ECONOMY_STAFF
ECONOMY_MANAGER
ROYAL_TREASURER
```

Assignment governance berbasis UUID dan dibatasi per shop scope atau `*`.

```text
/cve governance status
/cve governance list
/cve governance who <player>
/cve governance grant <player> <role> <shop|*>
/cve governance revoke <player> <shop|*|all>
/cve governance reload
```

`ECONOMY_STAFF` dapat view, edit price, dan add/remove runtime stock pada scope. `ECONOMY_MANAGER` menambah capability bind/toggle/layout/listing/stock config/manager metadata. `ROYAL_TREASURER` memiliki seluruh capability governance termasuk create/delete.

Permission beta.2 tetap backward compatible dan dianggap explicit operator override.

## Sensitive-Change Approval

Default role guardrail:

```yaml
governance:
  limits:
    economy-staff:
      max-price-change-percent: 20.0
      max-runtime-stock-delta: 128
    economy-manager:
      max-price-change-percent: 50.0
      max-runtime-stock-delta: 1024

  approval:
    enabled: true
    expiry-minutes: 10
    max-pending-per-requester: 5
```

Perubahan role-only yang melewati limit menjadi durable approval request. Requester tidak dapat approve/reject request sendiri. Approval memakai `EXECUTING` evidence sebelum mutation sehingga crash tidak membuat request direplay otomatis.

```text
/cve governance approval status
/cve governance approval list
/cve governance approval show <id>
/cve governance approval approve <id>
/cve governance approval reject <id> [reason]
/cve governance approval cancel <id>
/cve governance approval reload
/cve governance approval recover <id> <executed|not-executed> CONFIRM
```

## Rolling Governance Quota

Direct mutation kecil tetap dibatasi secara kumulatif:

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

Quota reservation dipersist sebelum direct role mutation. Jika ledger corrupt/unwritable, direct role-only price/stock mutation diblokir fail-closed. `GovernanceQuotaCommandListener` dipasang langsung pada runtime command path.

## Two-Person Extreme Approval

Perubahan sangat besar membutuhkan dua reviewer berbeda:

```yaml
governance:
  approval:
    two-person:
      enabled: true
      price-change-percent-threshold: 100.0
      price-from-zero-requires-two: true
      stock-delta-threshold: 4096
      stock-set-requires-two: true
      require-at-least-one-senior-reviewer: true
```

Reviewer pertama hanya membuat durable first-review evidence. Reviewer kedua harus berbeda. Dengan policy default, minimal satu reviewer harus Royal Treasurer dengan scope sesuai, governance admin, atau full admin. First-review evidence memakai fingerprint SHA-256 request sehingga evidence lama tidak dapat dipakai untuk target mutation yang berubah.

## Governance Persistence

```text
governance.yml
governance.yml.bak
governance.yml.tmp

governance-approvals.yml
governance-approvals.yml.bak
governance-approvals.yml.tmp

governance-usage.yml
governance-usage.yml.bak
governance-usage.yml.tmp

governance-dual-approval.yml
governance-dual-approval.yml.bak
governance-dual-approval.yml.tmp
logs/governance-dual-approval-history.log
```

## Permissions

```text
cdrvephilimeconomy.admin
cdrvephilimeconomy.shop.view
cdrvephilimeconomy.shop.create
cdrvephilimeconomy.shop.delete
cdrvephilimeconomy.shop.bind
cdrvephilimeconomy.shop.toggle
cdrvephilimeconomy.shop.edit
cdrvephilimeconomy.shop.item
cdrvephilimeconomy.shop.price
cdrvephilimeconomy.shop.stock
cdrvephilimeconomy.shop.manager
cdrvephilimeconomy.governance.view
cdrvephilimeconomy.governance.approve
cdrvephilimeconomy.governance.admin
```

`cdrvephilimeconomy.admin` mewarisi seluruh permission plugin.

## Audit

Grant/revoke, approval, quota reservation, dan dual-review menggunakan `logs/admin-audit.log`. Jika Discord administrative audit aktif, event governance ikut dikirim async melalui sink yang sama.

## Integrasi

- Paper 1.21.11 / Java 21
- Citizens
- Vault + economy provider
- LuckPerms opsional untuk permission override
- Discord webhook opsional

## Dokumentasi

- [`ROADMAP.md`](ROADMAP.md)
- [`docs/BETA1_FINAL.md`](docs/BETA1_FINAL.md)
- [`docs/BETA2_FINAL.md`](docs/BETA2_FINAL.md)
- [`docs/BETA3_FINAL.md`](docs/BETA3_FINAL.md)
- [`docs/BETA3_RC1.md`](docs/BETA3_RC1.md)
- [`docs/BETA3_RC2.md`](docs/BETA3_RC2.md)
- [`docs/BETA3_RC3.md`](docs/BETA3_RC3.md)
- [`docs/BETA3_RC4.md`](docs/BETA3_RC4.md)
- [`docs/BETA3_TEST_PLAN.md`](docs/BETA3_TEST_PLAN.md)
- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)
- [`docs/ECONOMY_DESIGN.md`](docs/ECONOMY_DESIGN.md)

## Next Development Phase

Setelah `0.1.0-beta.3` FINAL, development berikutnya adalah **beta.4 — Controlled Dynamic Pricing**. Governance beta.3 dianggap frozen baseline; bug fix harus dipisahkan dari fitur beta.4.

Plugin dikembangkan oleh **MenkiPlugcore** untuk **Vephilim Roleplay**.
