# Glossaire i18n FR — StreamVault IPTV

## Contexte

- **Public** : contributeurs traduisant ou corrigeant des chaînes FR dans `app/src/main/res/values-fr/strings.xml`.
- **Objectif** : verrouiller la traduction canonique des termes récurrents et éviter les incohérences (ex. *provider*/*fournisseur*, *channel*/*chaîne*).
- **Source** : conventions actées par la PR #41 (typographie + glossaire, mergée 2026-05-12) et complétées par le milestone M8 (mai 2026).
- **Portée** : exclusivement `values-fr/strings.xml`. Pas de Kotlin/Java, pas d'autres locales.
- **Outillage** : `scripts/check-i18n-fr.sh` valide la cohérence (complétude, typographie NBSP, glossaire). Données sources : `scripts/i18n-glossary-data.tsv`.

## Table canonique

| EN | FR canonique | Notes / formes interdites |
|---|---|---|
| provider | fournisseur | Ne pas utiliser « provider » en FR. |
| channel | chaîne | Pas « canal ». |
| group | groupe | |
| custom group | groupe personnalisé | |
| favorite | favori | |
| EPG | EPG | Inchangé. « guide TV » toléré en prose user-facing (cohérent avec EN `nav_epg = Guide`). |
| catch-up | TV en différé | « catch-up » toléré dans contexte technique (API, params XMLTV). |
| timeshift | différé direct | |
| variant (stream) | variante (de flux) | |
| backup | sauvegarde | |
| playlist | playlist (M3U) | « liste de lecture » toléré en prose user-facing pour les libellés M3U. |
| stream | flux | |
| settings | paramètres | « réglages » toléré ponctuellement. |
| home | accueil | |
| recording | enregistrement | |
| DVR | enregistreur | |
| seek | parcourir / avancer / reculer | Selon contexte UI. |
| picture-in-picture | image dans l'image (PiP) | |
| sleep timer | minuterie de veille | |
| theme | thème (sombre/clair) | |
| diagnostic / diagnostics | diagnostic | |
| passthrough (audio) | passthrough (audio) | Sans équivalent FR adopté. Ne pas traduire en « passage » ou « transfert direct ». |
| pixel ratio / aspect ratio | format / format d'image | |
| frame rate | fréquence d'image | |
| codec | codec | Inchangé. |
| HDR | HDR | Inchangé. |
| plugin | plugin | Pas « extension ». |

## Exemples avant/après

- « Add provider » → « Ajouter un fournisseur » (pas « Ajouter un provider »)
- « Channel list » → « Liste des chaînes » (pas « Liste des canaux »)
- « Catch-up window » → « Fenêtre de TV en différé » (sauf contexte technique : « Fenêtre catch-up » toléré pour params XMLTV)
- « Prefer passthrough » → « Préférer le passthrough » (pas « Préférer le passage »)
- « Set up later » → « Configurer plus tard » (vouvoiement implicite, ton injonctif neutre)

## Exceptions tolérées

- **catch-up** dans les noms techniques (API REST, params XMLTV) → conservé tel quel.
- **playlist** sans traduction → entré dans l'usage Android.
- **passthrough** (audio) → conservé, pas d'équivalent FR consensuel.
- **Acronymes** : EPG, HDR, PiP, DVR, M3U → inchangés.
- **CDATA / extraits de code** dans `strings.xml` → non concernés par le glossaire.

## Outillage

- **Données sources** : [`scripts/i18n-glossary-data.tsv`](../scripts/i18n-glossary-data.tsv) — 3 colonnes `EN<TAB>FR_canonique<TAB>FORBIDDEN_FR`.
- **Script de vérification** : [`scripts/check-i18n-fr.sh`](../scripts/check-i18n-fr.sh) — 3 checks (complétude EN/FR, typographie NBSP, glossaire). Exit codes 0/1/2/3, mode `--strict`.
- **Script de conversion typographique** : [`scripts/i18n-nbsp-fr.py`](../scripts/i18n-nbsp-fr.py) — applique les espaces insécables U+00A0 avant `:;?!»`.

## Conventions de ton

- **Vouvoiement** systématique (jamais « tu »).
- **Concision** : Android TV, largeur d'écran limitée pour les libellés focalisés.
- **Neutralité** : pas de familier, pas d'ironie.
- **Ponctuation FR** : espace insécable U+00A0 avant `:`, `;`, `?`, `!`, `»`. Convertir avec le script dédié.
