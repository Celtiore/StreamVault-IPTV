# M7 — Cross-OEM validation

## Devices benchés

| Device | OS | SoC | Decoder | Surface | Status |
|---|---|---|---|---|---|
| Smart TV (TCL?) `192.168.1.100` | Android 11 | MStar | `OMX.MS.AVC.Decoder` | `SURFACE_VIEW` | ✅ Bench complet (baseline + post-P2 + post-P1) |

## Limitation explicite

**M7 a été benché sur 1 seul device.** Les gains observés (cf `post-p2-bench.md` et `post-p1-bench.md`) sont représentatifs **uniquement** pour les Smart TVs Android 11 sur SoC MStar avec decoder `OMX.MS.AVC.Decoder`.

**Pas validé sur** :
- Nvidia Shield Pro (SoC Tegra X1, decoder Mediatek/NVMC)
- Bravia Android TV (SoC MediaTek MT5891 ou similaire)
- Fire TV (SoC Amlogic)
- Chromecast with Google TV
- Xiaomi Mi Box / OnePlus Smart TV
- ADT-3 (Mediatek MT8167)
- Sigma SoC family (legacy Smart TVs avant 2020)

## Risques device-specific connus

### P1 — Decoder reuse strict (`canReuseCodec`)

Le code actuel **avant M7** retournait `REUSE_RESULT_NO` hardcoded avec raison `DISCARD_REASON_MAX_INPUT_SIZE_EXCEEDED`. **Cette protection était explicite et raisonée** — probablement une mitigation historique contre des SoCs qui ne tolèrent pas le codec reuse cross-format.

**M7 P1 introduit du codec reuse en mode strict** sur :
- `mimeType` identique
- `width` × `height` identiques
- `colorInfo` identique (couvre HDR transitions)
- `pixelWidthHeightRatio` identique

**Tout le reste tombe sur `super.canReuseCodec(...)`** (Media3 default).

→ **Si un SoC historiquement instable** (Sigma, vieux MediaTek, etc.) **a un bug même sur formats strictement identiques**, on régresse.

**Mitigation prévue** :
1. **Pre-flight issue chez David** (`upstream-issue-draft.md` dans le dossier planning) — demander avant push si `REUSE_RESULT_NO` visait un SoC précis.
2. **Si signalé en post-merge** : ajouter un opt-out config `disable_decoder_reuse: Boolean` en Settings (fast-follow, hors scope M7).

### P2 — EPG gate (1500ms) + cap (4 voisins)

- **Aucun risque device-specific** — c'est de la logique réseau / Kotlin pure, indépendante du SoC.
- Risque côté UX : un user qui zap très très vite (sub-1500ms) ne verra plus de pré-fetch EPG voisin. Acceptable : il n'aurait pas eu le temps de voir l'EPG visible de toute façon, et le current channel EPG reste non-gaté.

### P3 — Snapshot off-main

- **Aucun risque device-specific** — `Dispatchers.IO` + `withContext` est standard.
- Le `check(Looper)` runtime est inconditionnel mais inoffensif (1 ref comparison).

## Pour la communauté

Si vous testez M7 sur un device autre que le device de référence, **merci de partager** :
- Modèle device + SoC + decoder name (visible dans `logcat ZapMetrics`)
- Gap zap perçu avant / après (estimation visuelle suffit)
- Tout crash ou green frame sur P1 (decoder reuse)

→ Issue / discussion à ouvrir sur le PR M7 P1 quand il sera ouvert.

## Note pour reviewer

Cette doc liste **explicitement** ce qui n'a **pas** été testé, pour éviter une fausse impression de validation. La PR M7 ne sur-vendra **pas** "marche partout" — uniquement "marche sur ma TCL Android 11 + reste compatible Media3 default pour le reste".
