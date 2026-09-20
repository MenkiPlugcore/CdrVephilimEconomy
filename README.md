# CdrVephilimEconomy

`CdrVephilimEconomy` adalah plugin economy khusus **Vephilim Roleplay** dengan pendekatan NPC-first: player berdagang melalui Citizens NPC, sedangkan plugin menangani stock, harga, transaksi, recovery, audit, dan governance.

## Status

- **Current development:** `0.1.0-beta.3-RC1`
- **Frozen Shop Management baseline:** `0.1.0-beta.2`
- **Frozen Core Economy baseline:** `0.1.0-beta.1`
- Branch development aktif: `dev/beta.3`

beta.3 RC1 membuka **Economy Staff & Governance** tanpa mengubah transaction safety foundation beta.1/beta.2.

## Core Economy

- NPC Citizens sebagai front-end transaksi.
- BUY / SELL / BUY_SELL per listing.
- Persistent real stock.
- Static pricing.
- Vault economy bridge.
- Transaction journal + persistent safety lock.
- Fail-closed recovery untuk stock/pending transaction.
- Local/Discord transaction audit.
- `/cve doctor`, `/cve safety status`, dan explicit recovery.

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

## beta.3 RC1 — Economy Staff & Governance

Tiga role internal tersedia:

```text
ECONOMY_STAFF
ECONOMY_MANAGER
ROYAL_TREASURER
```

Assignment disimpan berdasarkan UUID dan dapat dibatasi ke satu atau beberapa shop.

```text
/cve governance status
/cve governance list
/cve governance who <player>
/cve governance grant <player> <role> <shop|*>
/cve governance revoke <player> <shop|*|all>
/cve governance reload
```

Contoh:

```text
/cve governance grant Raka ECONOMY_STAFF blacksmith
/cve governance grant Raka ECONOMY_STAFF farmer
/cve governance grant Menki ROYAL_TREASURER *
```

Role tidak menghapus permission beta.2. Permission lama tetap backward compatible dan dianggap explicit operator override.

## Role Capabilities

`ECONOMY_STAFF`:
- view management diagnostics;
- edit price pada shop scope;
- add/remove runtime stock pada shop scope.

`ECONOMY_MANAGER`:
- seluruh capability Staff;
- bind/unbind NPC;
- enable/disable shop;
- edit display name/size;
- listing CRUD/mode/slot;
- initial/max stock config;
- manager metadata.

`ROYAL_TREASURER`:
- seluruh capability governance;
- create/delete shop;
- price/stock guardrail role tidak membatasi Treasurer.

Scope `*` berarti global.

## RC1 Guardrails

Default `config.yml`:

```yaml
governance:
  limits:
    economy-staff:
      max-price-change-percent: 20.0
      max-runtime-stock-delta: 128
    economy-manager:
      max-price-change-percent: 50.0
      max-runtime-stock-delta: 1024
```

Untuk role-only Staff/Manager:
- perubahan harga dibatasi per operasi;
- perubahan harga dari basis `0` membutuhkan Treasurer/admin;
- runtime stock hanya `add/remove` sesuai delta limit;
- runtime stock `SET` membutuhkan Royal Treasurer/admin atau explicit beta.2 permission.

RC1 belum memiliki rolling quota atau approval queue. Itu menjadi target RC berikutnya.

## Governance Persistence

```text
governance.yml
governance.yml.bak
governance.yml.tmp
```

`governance.yml` schema v1 ditulis dengan temporary validation dan atomic replace bila filesystem mendukung. Governance storage corrupt membuat akses berbasis role fail-closed, tetapi owner dengan `cdrvephilimeconomy.admin` tetap dapat melakukan recovery.

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

New beta.3:

```text
cdrvephilimeconomy.governance.view
cdrvephilimeconomy.governance.admin
```

`cdrvephilimeconomy.admin` mewarisi semuanya.

## Audit

Governance grant/revoke memakai administrative audit beta.2:

```text
GOVERNANCE_GRANT_REQUEST
GOVERNANCE_GRANT_SUCCESS
GOVERNANCE_GRANT_FAILED
GOVERNANCE_REVOKE_REQUEST
GOVERNANCE_REVOKE_SUCCESS
GOVERNANCE_REVOKE_FAILED
```

Jika Discord administrative audit aktif, event governance juga dikirim async melalui sink yang sama.

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
- [`docs/BETA3_TEST_PLAN.md`](docs/BETA3_TEST_PLAN.md)
- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)
- [`docs/ECONOMY_DESIGN.md`](docs/ECONOMY_DESIGN.md)

## Next beta.3 Update

Setelah RC1 role/scope regression aman, update berikutnya adalah **sensitive-change approval workflow**: request/approve/reject, threshold perubahan besar, expiry, anti-self-approval, dan durable approval evidence.

Plugin dikembangkan oleh **MenkiPlugcore** untuk **Vephilim Roleplay**.
