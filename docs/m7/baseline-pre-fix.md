# M7 — Baseline pre-fix (15 zaps, 2026-05-17)

Mesure de référence avant intervention des phases P1/P2/P3. Capture device `192.168.1.100` (Smart TV Android 11, MStar SoC, OMX.MS.AVC.Decoder), provider best-8k.org, locale FR, 15 zaps consécutifs sur Live HLS.

## Résultats agrégés

### Gap intent → first_frame (perçu utilisateur)

| Métrique | Valeur | Cible M7 |
|---|---|---|
| P50 | **3265 ms** | ≤ 1500 ms ❌ |
| P95 | **11423 ms** | ≤ 2500 ms ❌ |
| Min | 167 ms | — |
| Max | **15816 ms** | < 4000 ms ❌ |
| Moyenne | 4691 ms | — |

### Gap release → first_frame (gap noir technique)

| Métrique | Valeur |
|---|---|
| P50 | 1307 ms |
| P95 | 4969 ms |
| Min | 725 ms |
| Max | 4969 ms |
| Moyenne | 2336 ms |

### Burst EPG

| Métrique | Valeur | Note |
|---|---|---|
| `epg_in_flight_t500` (tous zaps) | 0 | Burst arrive **après** 500 ms — cohérent PROMPT (~700 ms post-switch) |
| `epg_requests_count` par zap | 0 à **78** | Burst massif sur certains channels (probablement nouvelle category) |
| Total `epg_request` events capture | **262** | Sur 15 intents = ~17 req/zap moyenné |

## Lecture par zap

Sur 15 zaps capturés :
- **14 valides** (intent + first_frame mesurés)
- **1 abandonné** (channel 329, intent sans first_frame — user a zap encore avant rendu)
- **3 anomalies** `first_frame_ts < release_ts` (zaps "instant", possible codec reuse implicite par Media3 même si `canReuseCodec = REUSE_RESULT_NO` hardcoded — à vérifier post P1)

Worst cases :
- **15.8s** sur channel 1167 (1er zap dessus, probablement 4K/HD demandant init complet)
- **11.4s** sur channel 1167 (2e zap, surprise — pas de codec reuse manifeste)
- **5.1s** sur channel 1166 (2e zap dessus)

Best cases :
- **167 ms** sur channel 314 (zap "instant", anomalie codec reuse implicite)
- **2.3s** sur channel 324

## Validation instrumentation

- 348 events `ZapMetrics` capturés sur 351 lignes logcat (filtré `ZapMetrics:I *:S`)
- Distribution events : 15 intent / 21 first_frame / 17 surface_gen / 17 codec_start / 16 release / 262 epg_request
- Plus de `first_frame` que d'`intent` → quelques recovery rendus (pas anormal)
- Pas d'event manquant ou de format JSON cassé

## Conclusion

**Le problème est confirmé et plus sévère que documenté dans le PROMPT** :
- PROMPT annonçait P50=3.1s / P95=5.4s sur 5 zaps
- Baseline réelle 15 zaps : P50=3.3s / **P95=11.4s** / **Max=15.8s**

Hypothèse : le PROMPT mesurait des zaps consécutifs sur des chaînes proches (même category), donc EPG cache moins déclenché et codec init plus rapide. La baseline 15 zaps inclut des switches vers de nouvelles categories (1166-1168) qui exposent le worst case.

**Phases P1/P2/P3 devraient cibler en priorité** :
1. Le worst case 15s (codec init lent sur grosses chaînes) → **P1 decoder reuse strict**
2. Le burst EPG de 78 requests (provoque la contention HTTP pool) → **P2 EPG throttle/cap**
3. Le P95 4969 ms du gap release→first_frame → **P3 snapshot off-main** + **P1 decoder reuse**

## Référence

- Capture brute logcat : `$CLAUDE_JOB_DIR/m7-test-pr64/baseline-20zaps-20260517-093251.log` (351 lignes, 38KB)
- Capture parsée : `docs/m7/baseline-pre-fix.json` (15 zaps détaillés)
- Device : Smart TV Android 11, MStar SoC `OMX.MS.AVC.Decoder`, surface `SURFACE_VIEW`
- Build : `feat/m7-fast-channel-zap` commit `9d1299e` (T1+T2+T3 instrumentation)
- Provider : best-8k.org (Xtream Codes HLS)
- Method : 15 zaps consécutifs manuels, mix chaînes proches (314-329) et chaînes éloignées (1166-1168)
