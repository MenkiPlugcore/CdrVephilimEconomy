# CdrVephilimEconomy 0.1.0-beta.2-RC2

RC2 melanjutkan Shop Management RC1 dengan fokus pada migration safety, audit administratif eksternal, dan QoL operasional.

## Added

- Formal `shops.yml` schema v2 melalui `meta.schema: 2`.
- Migration legacy schema v1 (beta.1/RC1 tanpa `meta.schema`) ke v2.
- Backup migration `shops.yml.schema-v1.bak` sebelum file legacy diganti.
- Metadata migration: `meta.migrated-from`, `meta.migrated-at`, `meta.updated-at`.
- Future-schema guard: schema lebih baru dari plugin ditolak fail-closed.
- Discord administrative audit terpisah dari transaction audit.
- Config `admin-audit.discord.enabled`, `webhook-url`, dan `include-requests`.
- `/cve shop schema` untuk melihat versi schema.
- `/cve shop validate` untuk strict validation management config.
- `/cve shop name <shop> <display name>`.
- `/cve shop size <shop> <size>`.
- `/cve shop slot <shop> <listing> <slot>`.
- Permission `cdrvephilimeconomy.shop.edit` untuk display name dan GUI size.

## Safety

- Local `admin-audit.log` tetap mandatory source of truth. Jika REQUEST audit lokal gagal, mutation administratif dibatalkan.
- Discord admin audit asynchronous dan tidak boleh menjadi dependency commit mutation.
- Config mutation tetap menggunakan candidate validation, pre-change `shops.yml.admin.bak`, atomic replace bila tersedia, runtime reload, dan rollback file bila reload gagal.
- Size/slot mutation melewati validation yang sama dengan startup/reload sehingga duplicate slot dan out-of-range layout tidak dapat di-commit.
- Runtime stock safety beta.1 tidak diubah.

## Upgrade Notes

Saat server menggunakan file beta.1/RC1 tanpa schema, RC2 tetap dapat memuat konfigurasi legacy. Setelah ShopAdminService diinisialisasi, file dimigrasikan ke v2. Definisi shop/listing dan runtime stock tidak di-reset; stock tetap berada di `stock.yml`.

Setelah upgrade, cek:

```text
/cve shop schema
/cve shop validate
/cve doctor
```

Expected management schema:

```text
shops.yml schema=v2/v2 CURRENT
```

## QA Focus

Lihat `docs/BETA2_TEST_PLAN.md`, terutama:

- v1 -> v2 migration dan backup;
- future schema rejection;
- Discord admin audit delivery/failure isolation;
- display name/size/slot QoL;
- RC1 management regression;
- BUY/SELL core regression.
