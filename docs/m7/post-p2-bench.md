# M7 — Bench post-P2/P3 (24 zaps, 2026-05-17)

Capture après commits T5 (snapshot off-main, P3) et T9-T15 (EPG gate + cap, P2). Mêmes conditions que la baseline : device `192.168.1.100`, provider best-8k.org, locale FR, mix near/far channel switches.

## Comparaison

| Métrique | Baseline (15 zaps) | Post-P2/P3 (24 zaps) | Delta |
|---|---|---|---|
| **gap intent→first_frame P50** | 3265 ms | **3248 ms** | ≈ 0% |
| **gap P95** | **11423 ms** | **4226 ms** | **-63%** |
| **gap max** | **15816 ms** | **4591 ms** | **-71%** |
| **gap moyenne** | 4691 ms | 3006 ms | -36% |
| **release→first_frame P50** (gap noir tech) | 1307 ms | **286 ms** | **-78%** |
| **release→first_frame P95** | 4969 ms | **374 ms** | **-92%** |
| **EPG events total** | 262 | 48 | -82% |
| **EPG par zap moyenne** | 17.5 | **2** | **-89%** |

## Conclusion par phase

### P3 — snapshot off-main → **effet collatéral énorme sur gap noir technique**

`PlaybackSupportSnapshotStore.write` était sur main thread. En déplaçant vers `Dispatchers.IO`, le main thread est libéré au moment du `release` → `first_frame` quasi-instantané.

- P50 release→first_frame : **1307ms → 286ms (-78%)**
- P95 : **4969ms → 374ms (-92%)**

Gain inattendu : on ne pensait pas que P3 ferait cette différence-là. La cause vraisemblable : sans `Dispatchers.IO`, le `write` synchronisait sur le main thread pile au moment où Media3 essaie de render le premier frame, créant un stall. Off-main → no stall.

### P2 — EPG gate (1500ms) + cap (4 voisins) → **burst quasi-disparu**

L'origine du burst 78 (fanout multiplicateur per-providerId × 2 callers × 8 providers × 10 voisins) est éliminée par le gate au repo level :

- EPG par zap : **17.5 → 2 (-89%)**
- Burst en T+0 à T+1500ms : **0** systématique

### Le P50 inchangé — où se cache la latence restante ?

P50 gap reste à **~3,2 secondes** parce que :
- `release → first_frame` est désormais ~286ms (P3 ✅)
- Donc `intent → release` est ~2900ms en moyenne
- Cohérent avec observation visuelle utilisateur : *"image new chaine instant → buffer + écran noir → image revient"*

Hypothèse forte : la latence restante = **manifest M3U8 fetch + first HLS segment fetch + live edge sync**. C'est du réseau / HLS, pas du codec ni de l'EPG.

P1 (decoder reuse strict, à venir) devrait :
- Gagner sur les zaps **codec-identical** (H.264 1080p → H.264 1080p) : skip le release+init = gain ~500-1500ms potentiel
- Pas aider sur les zaps **codec-different** ou réseau bottleneck

P50 final estimé après P1 : ~2000-2500ms. Pour atteindre la cible PROMPT ≤ 1500ms, il faudrait P4 (prefetch HLS first segment overlap), out-of-scope M7.

## Critères PROMPT — Reality check

| Critère | Cible | Atteint | Δ restante avant cible |
|---|---|---|---|
| gap P50 | ≤ 1500 ms | 3248 ms | -54% à gagner |
| gap P95 | ≤ 2500 ms | 4226 ms | -41% à gagner |
| gap max | < 4000 ms | 4591 ms | -13% à gagner |
| EPG burst T+0→T+500ms | 0 | 0 | ✅ |
| StrictMode disk-write pendant zap | 0 | non mesuré | (P3 garantit par construction) |

Conclusion : **P2 + P3 ont éliminé les contributeurs identifiés A (burst EPG) et C (snapshot disk write)**. Le contributeur B (codec release+init complet) reste à traiter par P1 mais ne sera pas suffisant pour atteindre P50 1500ms — un quatrième contributeur (manifest M3U8 fetch sequentiel) émerge des données et serait l'objet d'un P4 ou d'un M9 futur.

## Anomalies observées

Plusieurs zaps avec `release_to_first_frame_ms = null` car `first_frame_ts < release_ts` — Media3 render le first frame **avant** de release l'ancien codec. C'est l'image instantanée que l'utilisateur voit avant le buffering. Pattern intéressant : Media3 a un short-circuit "rendered first frame from new manifest" qui précède le release physique du codec. Ne casse pas notre mesure mais explique l'expérience utilisateur.

## Référence

- Capture : `$CLAUDE_JOB_DIR/m7-test-pr64/post-p2-20zaps-20260517-103107.log` (144 lignes)
- JSON : `docs/m7/post-p2-bench.json` (24 zaps détaillés)
- Build : `feat/m7-fast-channel-zap` commit `5a534ee` (T15 = dernier P2)
- Device : Smart TV Android 11 (MStar SoC, OMX.MS.AVC.Decoder)
- Provider : best-8k.org Xtream HLS, locale FR
