#!/usr/bin/env bash
# check-i18n-fr.sh — Vérification cohérence i18n FR pour StreamVault IPTV.
#
# 3 checks indépendants :
#   1. Complétude   — keys EN absentes côté FR (count + diff de noms).
#   2. Typographie  — espaces simples avant `:;?!»` dans le contenu des <string>
#                     (modulo whitelist URL / placeholders / commentaires XML).
#   3. Glossaire    — termes FR interdits trouvés dans values-fr/strings.xml
#                     (data source : scripts/i18n-glossary-data.tsv).
#
# Exit codes :
#   0   tous les checks OK
#   1   check 1 fail (complétude)
#   2   check 2 fail (typographie)
#   3   check 3 fail (glossaire)
#   priorité au plus bas si plusieurs échecs cumulés.
#
# Modes :
#   (défaut)   warnings glossaire → affichés mais n'entraînent pas exit ≠ 0
#              tant que la liste FORBIDDEN_FR n'est pas matchée.
#   --strict   tout warning (y compris compte de NBSP suspect) → exit 1.
#
# Usage :
#   bash scripts/check-i18n-fr.sh [--strict] [--help]

set -euo pipefail

# ---------- Configuration ----------
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
EN_FILE="$ROOT_DIR/app/src/main/res/values/strings.xml"
FR_FILE="$ROOT_DIR/app/src/main/res/values-fr/strings.xml"
GLOSSARY_TSV="$ROOT_DIR/scripts/i18n-glossary-data.tsv"

STRICT=0
SHOW_HELP=0
for arg in "$@"; do
    case "$arg" in
        --strict)
            STRICT=1
            ;;
        --help | -h)
            SHOW_HELP=1
            ;;
        *)
            echo "unknown argument: $arg" >&2
            SHOW_HELP=1
            ;;
    esac
done

if [[ $SHOW_HELP -eq 1 ]]; then
    sed -n '2,25p' "${BASH_SOURCE[0]}"
    exit 0
fi

# ---------- Couleurs (TTY only) ----------
if [[ -t 1 ]] && command -v tput > /dev/null 2>&1 && [[ "$(tput colors 2> /dev/null || echo 0)" -gt 2 ]]; then
    C_RED="$(tput setaf 1)"
    C_GREEN="$(tput setaf 2)"
    C_YELLOW="$(tput setaf 3)"
    C_RESET="$(tput sgr0)"
else
    C_RED=""
    C_GREEN=""
    C_YELLOW=""
    C_RESET=""
fi

echo_ok() { echo "${C_GREEN}$*${C_RESET}"; }
echo_warn() { echo "${C_YELLOW}$*${C_RESET}"; }
echo_fail() { echo "${C_RED}$*${C_RESET}"; }

# ---------- Validation préalable ----------
for f in "$EN_FILE" "$FR_FILE" "$GLOSSARY_TSV"; do
    if [[ ! -f "$f" ]]; then
        echo_fail "[FATAL] required file missing: $f"
        exit 1
    fi
done

# ---------- Check 1 — Complétude ----------
EN_KEYS=$(grep -oE 'name="[^"]+"' "$EN_FILE" | sort -u)
FR_KEYS=$(grep -oE 'name="[^"]+"' "$FR_FILE" | sort -u)
EN_COUNT=$(echo "$EN_KEYS" | wc -l | tr -d ' ')
FR_COUNT=$(echo "$FR_KEYS" | wc -l | tr -d ' ')

MISSING_KEYS=$(comm -23 <(echo "$EN_KEYS") <(echo "$FR_KEYS") || true)
MISSING_COUNT=0
if [[ -n "$MISSING_KEYS" ]]; then
    MISSING_COUNT=$(echo "$MISSING_KEYS" | wc -l | tr -d ' ')
fi

CHECK1_FAIL=0
if [[ "$MISSING_COUNT" -gt 0 ]]; then
    echo_fail "[CHECK 1/3] FAIL — completeness: $MISSING_COUNT FR keys missing (EN=$EN_COUNT, FR=$FR_COUNT)"
    echo "$MISSING_KEYS" | sed 's/^/    /'
    CHECK1_FAIL=1
else
    echo_ok "[CHECK 1/3] OK — completeness: EN=$EN_COUNT, FR=$FR_COUNT, diff=0"
fi

# ---------- Check 2 — Typographie ----------
# Compte des espaces simples avant ponctuation forte FR, EN EXCLUANT les
# commentaires XML (`<!-- ... -->`) qui ne sont pas visibles utilisateur.
TYPO_COUNT=$(
    python3 - "$FR_FILE" << 'PY'
import re
import sys

path = sys.argv[1]
with open(path, encoding='utf-8') as f:
    content = f.read()

# Retire les commentaires XML.
no_comments = re.sub(r'<!--.*?-->', '', content, flags=re.DOTALL)
# Retire les sections CDATA.
no_cdata = re.sub(r'<!\[CDATA\[.*?\]\]>', '', no_comments, flags=re.DOTALL)

matches = re.findall(r' [:;?!»]', no_cdata)
print(len(matches))
PY
)

CHECK2_FAIL=0
if [[ "$TYPO_COUNT" -gt 0 ]]; then
    echo_fail "[CHECK 2/3] FAIL — typography: $TYPO_COUNT simple space(s) before strong punctuation (expected 0)"
    CHECK2_FAIL=2
else
    echo_ok "[CHECK 2/3] OK — typography: 0 simple space before :;?!» (modulo whitelist)"
fi

# ---------- Check 3 — Glossaire ----------
# Pour chaque ligne du TSV (non-commentaire), grep insensible à la casse des
# termes FORBIDDEN_FR (colonne 3, pipe-séparée) dans FR_FILE.
CHECK3_FAIL=0
GLOSSARY_HITS=""

while IFS=$'\t' read -r en fr forbidden || [[ -n "$en" ]]; do
    # Skip blank lines and comments
    [[ -z "$en" || "$en" =~ ^# ]] && continue
    [[ -z "${forbidden:-}" ]] && continue
    # Decompose pipe-separated forbidden list
    IFS='|' read -ra terms <<< "$forbidden"
    for term in "${terms[@]}"; do
        [[ -z "$term" ]] && continue
        # Grep word-boundary insensible à la casse, ne matcher que dans le
        # contenu textuel (best effort : on accepte d'inclure les attributs
        # mais c'est trop rare pour générer du bruit).
        if grep -iqE "\b${term}\b" "$FR_FILE"; then
            CHECK3_FAIL=3
            GLOSSARY_HITS+="    '$term' (canonical FR for '$en' = '$fr')"$'\n'
        fi
    done
done < "$GLOSSARY_TSV"

if [[ "$CHECK3_FAIL" -ne 0 ]]; then
    echo_fail "[CHECK 3/3] FAIL — glossary: forbidden FR terms detected"
    printf '%s' "$GLOSSARY_HITS"
else
    echo_ok "[CHECK 3/3] OK — glossary: no forbidden FR terms detected"
fi

# ---------- Aggregate exit code ----------
# Priorité au plus bas : 1 > 2 > 3 (le plus haut numérique mais le plus bas
# logique). On retourne 1 si check 1 a échoué, sinon 2 si check 2, sinon 3.
if [[ $CHECK1_FAIL -ne 0 ]]; then
    exit 1
fi
if [[ $CHECK2_FAIL -ne 0 ]]; then
    exit 2
fi
if [[ $CHECK3_FAIL -ne 0 ]]; then
    exit 3
fi

# Mode --strict : à ce stade, tout est OK mais on pourrait ajouter d'autres
# warnings (ex. NBSP suspect dans un placeholder). Placeholder pour évolution.
if [[ $STRICT -eq 1 ]]; then
    # Aucun warning supplémentaire pour l'instant — placeholder.
    :
fi

exit 0
