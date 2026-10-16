# Legacy version (superseded)

Previous version of the replication package (May 2026): security postures as
cumulative Spring profiles and Docker Compose overlays (V1, V2, V2+mTLS,
V2+Vault, V3), primitive costs measured by cumulative ablation, and the
corresponding results. Kept for reference only; the Java services no longer
read these profiles. The current setup is described in the top-level README.

- `testbed/` – compose overlays and nginx configurations of the profile-based setup
- `scripts/` – orchestration scripts 03–08 of that setup
- `results/` – its pre-computed results (isolation, RPT, Phase 2 alerts)
