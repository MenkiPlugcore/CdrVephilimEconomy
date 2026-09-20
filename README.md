# CdrVephilimEconomy

`CdrVephilimEconomy` adalah plugin economy khusus **Vephilim Roleplay** dengan pendekatan NPC-first: player berdagang melalui Citizens NPC, sedangkan plugin menangani stock, harga, transaksi, recovery, audit, dan governance.

## Status

- **Current development:** `0.1.0-beta.3-RC3`
- **Frozen Shop Management baseline:** `0.1.0-beta.2`
- **Frozen Core Economy baseline:** `0.1.0-beta.1`
- Branch development aktif: `dev/beta.3`

beta.3 RC1 membuka role/scope Economy Staff. RC2 menambahkan **sensitive-change approval**. RC3 menambahkan **rolling quota + cooldown anti-abuse** agar limit per operasi tidak dapat dibypass dengan banyak perubahan kecil beruntun.

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

Assignment berbasis UUID dan dibatasi per shop scope atau `*`.

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

## RC2 Sensitive-Change Approval

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

Untuk role-only Staff/Manager:
- perubahan harga di dalam limit dapat direct mutation;
- harga di atas limit menjadi approval request;
- perubahan harga dari base `0` menjadi approval request;
- ADD/REMOVE runtime stock di dalam limit dapat direct mutation;
- delta stock di atas limit menjadi approval request;
- runtime stock `SET` selalu menjadi approval request.

Approval commands:

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

Requester tidak dapat approve/reject request sendiri. Manager dapat review Staff pada scope yang sama; Treasurer dapat review Manager/Staff. Approval memakai durable `EXECUTING` evidence sebelum mutation untuk mencegah replay ambigu setelah crash.

## RC3 Rolling Governance Quota

Direct mutation kecil sekarang dibatasi lagi secara kumulatif.

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

Contoh Staff: harga `100 -> 120` memakai sekitar 20 quota points; setelah cooldown, `120 -> 144` memakai sekitar 20 lagi. Total 40/40, sehingga direct price mutation berikutnya diblokir sampai rolling window berkurang.

Stock menggunakan absolute delta yang sama: ADD/REMOVE berulang tetap dijumlahkan. Quota bersifat global per staff account dan cooldown berlaku lintas direct price/stock mutation.

Per-operation sensitive changes tetap memakai approval RC2. Full admin, Royal Treasurer, dan explicit beta.2 permission dianggap operator override dan tidak dibatasi rolling quota.

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
```

Quota reservation dipersist sebelum direct role mutation dijalankan. Jika downstream mutation gagal, reservation tetap dihitung sampai window expiry; desain ini sengaja konservatif agar retry/crash tidak menjadi bypass quota.

Jika quota ledger corrupt/unwritable, direct role-only price/stock mutation diblokir fail-closed, tetapi BUY/SELL core dan operator recovery tidak otomatis dihentikan.

## Permissions

Existing beta.2:

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
```

beta.3:

```text
cdrvephilimeconomy.governance.view
cdrvephilimeconomy.governance.approve
cdrvephilimeconomy.governance.admin
```

`cdrvephilimeconomy.admin` mewarisi semuanya.

## Audit

Grant/revoke, approval, dan quota reservation memakai `logs/admin-audit.log`. Jika Discord administrative audit aktif, event governance/approval juga dikirim async melalui sink yang sama.

Event quota:

```text
GOVERNANCE_QUOTA_RESERVED
```

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
- [`docs/BETA3_RC1.md`](docs/BETA3_RC1.md)
- [`docs/BETA3_RC2.md`](docs/BETA3_RC2.md)
- [`docs/BETA3_RC3.md`](docs/BETA3_RC3.md)
- [`docs/BETA3_TEST_PLAN.md`](docs/BETA3_TEST_PLAN.md)
- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)
- [`docs/ECONOMY_DESIGN.md`](docs/ECONOMY_DESIGN.md)

## Next beta.3 Update

Setelah RC3 QA aman, beta.3 tinggal hardening governance terakhir: opsi **two-person approval** untuk perubahan paling sensitif, final regression/security audit, lalu finalisasi `0.1.0-beta.3`.

Plugin dikembangkan oleh **MenkiPlugcore** untuk **Vephilim Roleplay**.
