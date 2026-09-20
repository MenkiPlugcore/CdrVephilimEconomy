# CdrVephilimEconomy

`CdrVephilimEconomy` adalah plugin economy khusus untuk **Vephilim Roleplay**.

Fokus utamanya adalah membuat perdagangan terasa sebagai bagian dari dunia roleplay kerajaan: player bertransaksi melalui NPC, bukan melalui command shop biasa.

## Prinsip Utama

- Player **tidak memiliki command shop**.
- Semua transaksi dilakukan melalui NPC Citizens.
- NPC hanya menjadi front-end/interaksi dunia; logika transaksi ditangani plugin.
- Sistem mendukung BUY, SELL, atau BUY + SELL per item.
- Shop memiliki stok nyata yang persisten.
- Harga masih bersifat statis agar mudah dibalance dan diaudit.
- Semua transaksi penting memiliki audit trail.
- Fokus utama development adalah keamanan transaksi, anti-dupe, fail-closed recovery, dan operasional admin yang terkontrol.

## Status

**Current development candidate: `0.1.0-beta.2-RC2`**  
**Frozen core baseline: `0.1.0-beta.1`**

RC1 membuka command-driven shop management. RC2 menambahkan formal `shops.yml` schema v2 + migration, Discord administrative audit terpisah, dan QoL command untuk layout/diagnostic shop tanpa mengubah transaction safety foundation beta.1.

## beta.2 Shop Management

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

Setiap mutation config melewati candidate validation dan runtime apply tanpa restart. Snapshot pre-change disimpan ke `shops.yml.admin.bak`, sedangkan jejak perubahan administratif disimpan di `logs/admin-audit.log`.

## shops.yml Schema v2

RC2 memakai:

```yaml
meta:
  schema: 2
```

File beta.1/RC1 tanpa schema dianggap legacy v1 dan dimigrasikan otomatis ketika shop management diinisialisasi. Sebelum migration dibuat backup `shops.yml.schema-v1.bak`. Schema yang lebih baru dari kemampuan plugin ditolak fail-closed supaya plugin lama tidak menulis format yang tidak dipahami.

## Discord Administrative Audit

Admin audit Discord terpisah dari transaction audit:

```yaml
admin-audit:
  discord:
    enabled: false
    webhook-url: ""
    include-requests: false
```

`SUCCESS`, `FAILED`, dan `REJECTED` dapat dikirim async ke Discord. Local `admin-audit.log` tetap source of truth dan wajib berhasil ditulis sebelum mutation administratif dimulai.

## Granular Permissions

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

Full `cdrvephilimeconomy.admin` mewarisi seluruh permission shop. Staff dapat diberi permission granular melalui LuckPerms tanpa full admin access.

## Core Admin Commands

```text
/cve reload
/cve status
/cve doctor
/cve safety status
/cve safety unlock CONFIRM
```

Tidak ada command shop untuk player.

## Integrasi

- Paper 1.21.11 / Java 21.
- Citizens.
- Vault + economy provider.
- Discord webhook opsional untuk transaction audit dan administrative audit.
- LuckPerms dapat digunakan untuk permission admin/staff.

## Storage

- `shops.yml` — definisi shop schema v2.
- `shops.yml.schema-v1.bak` — backup migration legacy.
- `shops.yml.admin.bak` — snapshot sebelum admin mutation.
- `stock.yml` — mutable runtime stock.
- `logs/audit.log` — transaction audit.
- `logs/admin-audit.log` — administrative audit.
- `safety.lock` — persistent economy circuit breaker.
- `pending-transactions/` — crash-window recovery evidence.

## Dokumentasi

- [`ROADMAP.md`](ROADMAP.md) — tahapan development.
- [`CHANGELOG.md`](CHANGELOG.md) — riwayat perubahan.
- [`docs/BETA1_FINAL.md`](docs/BETA1_FINAL.md) — frozen core baseline beta.1.
- [`docs/BETA2_ADMIN_COMMANDS.md`](docs/BETA2_ADMIN_COMMANDS.md) — command dan permission beta.2.
- [`docs/BETA2_TEST_PLAN.md`](docs/BETA2_TEST_PLAN.md) — QA beta.2 RC2.
- [`docs/ECONOMY_DESIGN.md`](docs/ECONOMY_DESIGN.md) — konsep ekonomi.
- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) — arsitektur core.

## Next beta.2 hardening

Setelah RC2 runtime QA, fase berikutnya fokus pada final management regression, migration/recovery edge-case, dan operasional admin sebelum `0.1.0-beta.2` final.

Plugin ini dikembangkan oleh **MenkiPlugcore** untuk project **Vephilim Roleplay**.
