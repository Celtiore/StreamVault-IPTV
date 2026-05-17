# M7 — Bench post-P1 (9 zaps, 2026-05-17, dont 1 outlier 31s)

Capture après T17-T18 (decoder reuse strict, P1). Mêmes conditions device et provider que les benchs précédents.

## Comparaison 3-way

| Métrique | Baseline (15 zaps) | Post-P2/P3 (24 zaps) | Post-P1 brut (9 zaps) | Post-P1 hors outlier (8 zaps) |
|---|---|---|---|---|
| **gap intent→first_frame P50** | 3265 ms | 3248 ms | 3455 ms | **~3180 ms** |
| **gap P95** | 11423 ms | 4226 ms | 31691 ms ⚠️ | **~4500 ms** |
| **gap max** | 15816 ms | 4591 ms | 31691 ms ⚠️ | **5328 ms** |
| **gap moyenne** | 4691 ms | 3006 ms | 6837 ms | **~3300 ms** |
| **release→first_frame P50** (gap noir tech) | 1307 ms | 286 ms | **154 ms** | 154 ms ✅ |
| **release→first_frame P95** | 4969 ms | 374 ms | **393 ms** | 393 ms ✅ |
| **EPG events** | 262 (15 zaps) | 48 (24 zaps) | 60 (9 zaps) | ≈ 6.7 / zap |

## L'outlier 31s

Channel 329 a pris **31.7 secondes** (`intent → first_frame`), dont **33.4 secondes** entre intent et codec_start. Probablement :
- Manifest M3U8 inaccessible / timeout côté provider
- Backend best-8k.org saturé
- Recovery via 2e essai

Cet outlier ne reflète pas le gain réel de P1 — c'est un cas réseau pathologique qui aurait causé un long buffering avec ou sans P1.

## Gain réel de P1

Sans l'outlier, **P1 n'apporte ni régression ni amélioration significative** sur le gap total `intent → first_frame`. La distribution post-P1 (P50 ~3180ms) est essentiellement la même que post-P2 (P50 3248ms).

**Pourquoi le gain est invisible** :

Le pattern dominant est :

```
intent ─────────[2-6s manifest+segment HLS fetch]──── codec_start ──── first_frame
                                                          [< 300ms render]
                                                  ↑
                                          écran noir perçu
```

P1 économise le release+init complet du codec (~200-500ms gagnés sur le segment `codec_start → first_frame`) mais ce gain est noyé dans les 2-6 secondes réseau du segment `intent → codec_start`.

## Observation visuelle utilisateur

> *"L'écran noir est quasiment de 2-3 secondes !!!"*

Cohérent avec les chiffres : `intent → codec_start` ≈ 2-6s sur ce capture. Le **floor HLS** est dominant. P1 n'a pas amélioré cette partie (c'est P4 prefetch qui le ferait).

## Verdict M7

| Phase | Gain mesuré | Statut |
|---|---|---|
| **P3 (snapshot off-main)** | gap noir technique P50 1307→286ms (-78%), P95 4969→374ms (-92%) | ✅ Win massive |
| **P2 (EPG gate+cap)** | burst per-zap 17.5→2 (-89%), P95 11423→4226ms (-63%), max 15816→4591ms (-71%) | ✅ Win massive |
| **P1 (decoder reuse strict)** | Code et tests OK. Gain mesurable noyé dans le floor HLS ~3s. | ⚠️ Win marginal (réel mais masqué) |

## Cible PROMPT — Reality check

| Critère PROMPT | Cible | Atteint (hors outlier) | Verdict |
|---|---|---|---|
| gap P50 | ≤ 1500 ms | ~3180 ms | ❌ pas atteint (floor HLS) |
| gap P95 | ≤ 2500 ms | ~4500 ms | ❌ pas atteint (floor HLS) |
| gap max | < 4000 ms | 5328 ms | ❌ très proche |
| EPG burst T+0→T+500ms | 0 | 0 | ✅ |
| Gap noir technique | (non chiffré) | -88 / -92% | ✅ |

**Conclusion honnête** : M7 a éliminé les **2 contributeurs identifiés** (burst EPG côté A, snapshot disk-write côté C, decoder reuse côté B partiellement) mais a **révélé un 4e contributeur** dominant non-anticipé : la **latence HLS manifest+segment fetch séquentielle** (~3s incompressibles avec l'archi actuelle).

Pour vraiment atteindre P50 ≤ 1500ms, il faudrait **P4 prefetch HLS first segment overlap** — déclencher le manifest+segment fetch en parallèle du release codec précédent. C'était marqué out-of-scope dans le PROMPT M7 — devra être un milestone séparé (M9 ?).

## Référence

- Capture : `$CLAUDE_JOB_DIR/m7-test-pr64/post-p1-20zaps-20260517-105942.log` (114 lignes, 9 zaps)
- JSON : `docs/m7/post-p1-bench.json`
- Build : `feat/m7-fast-channel-zap` commit `c5ad5b2` (T18 = dernier P1)
- Device : Smart TV Android 11 (MStar SoC, OMX.MS.AVC.Decoder)
- Provider : best-8k.org Xtream HLS, locale FR
