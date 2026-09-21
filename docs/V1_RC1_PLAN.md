# v1.0.0 Production Hardening — RC1 Plan

RC1 menutup gap runtime/persistence yang harus aman sebelum release production:

- explicit single market runtime bootstrap;
- fail-closed governance assignment recovery setelah file hilang;
- post-commit governance audit semantics yang tidak melaporkan mutation committed sebagai gagal;
- scope/tab-completion cleanup;
- official Vephilim NPC catalog baseline;
- exact-head CI artifact untuk runtime regression.

Target version: `1.0.0-RC1`.
