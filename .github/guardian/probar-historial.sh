#!/usr/bin/env bash
# CCPIP-22: prueba el guardian de main con PRs REALES de este repositorio, sin escribir en GitHub.
#
#   bash .github/guardian/probar-historial.sh            # todo (la parte 2 necesita gh con sesion iniciada)
#   bash .github/guardian/probar-historial.sh --sin-red  # solo la parte 1 (basta con git)
#   bash .github/guardian/probar-historial.sh --solo-red # solo la parte 2 (rapida: unos segundos)
#
# Parte 1 (solo git): por cada commit del historial de main se arma una copia, se finge que ese commit
#   fue un PR fusionado con checks en rojo y se corre el guardian en modo revertir. Debe dejar main con
#   el contenido EXACTO de antes del commit, sin perder historia. Para los commits que tocaron
#   .github/workflows se repite la prueba con un origin que, como GitHub con el GITHUB_TOKEN, rechaza
#   esos cambios: ahi el guardian no debe hacer nada mas que avisar.
# Parte 2 (API de GitHub, solo lectura): corre el guardian REAL, en modo ensayo en seco, sobre los PRs
#   fusionados de verdad y sobre commits que fallaron de verdad (PR #37 y #39), y comprueba que
#   detecta los incumplimientos reales y no inventa ninguno. Un gh envoltorio BLOQUEA cualquier llamada
#   que no sea leer los check-runs: aunque hubiera un error, no se puede escribir en GitHub.
set -uo pipefail

SIN_RED=""
SOLO_RED=""
[[ "${1:-}" == "--sin-red" ]] && SIN_RED=1
[[ "${1:-}" == "--solo-red" ]] && SOLO_RED=1

RAIZ="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
GUARDIAN="$RAIZ/.github/guardian/guardian-main.sh"
BASE="$(mktemp -d)"
trap 'rm -rf "$BASE"' EXIT
export GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_SYSTEM=/dev/null GIT_TERMINAL_PROMPT=0
export GIT_CONFIG_COUNT=1 GIT_CONFIG_KEY_0=safe.directory GIT_CONFIG_VALUE_0='*'
export GIT_AUTHOR_NAME=prueba GIT_AUTHOR_EMAIL=prueba@example.com GIT_COMMITTER_NAME=prueba GIT_COMMITTER_EMAIL=prueba@example.com
HEX40="$(printf 'a%.0s' $(seq 1 40))"

OK=0
FALLO=0
no_contiene() { ! grep -qF -- "$2" "$1"; }
verificar() { # descripcion, comando...
  local descripcion="$1"
  shift
  if "$@" >/dev/null 2>&1; then OK=$((OK + 1)); else FALLO=$((FALLO + 1)); printf '  FALLO  %s\n' "$descripcion"; fi
}

# ===================================================================================
# Parte 1: reproducir cada merge real del historial y revertirlo
# ===================================================================================
echo "Parte 1: revertir cada commit real de main con el guardian (solo git)"

FUENTE="$BASE/fuente"
ORIGIN="$BASE/origin.git"
WORK="$BASE/trabajo"
git clone --quiet --no-hardlinks "$RAIZ" "$FUENTE" 2>/dev/null || { echo "No se pudo copiar el repositorio."; exit 1; }
git init --quiet --bare --initial-branch=main "$ORIGIN"
cp "$RAIZ/docs/ruleset-main.json" "$BASE/ruleset-main.json"

# Un origin que rechaza, solo si existe el marcador, los pushes a main que cambian .github/workflows
# (es lo que hace GitHub con el GITHUB_TOKEN, que no tiene el permiso "workflows")
cat > "$ORIGIN/hooks/pre-receive" <<'HOOK'
#!/usr/bin/env bash
[[ -e "$GIT_DIR/restringir-workflows" ]] || exit 0
while read -r viejo nuevo ref; do
  [[ "$ref" == refs/heads/main && "$viejo" != 0000000000000000000000000000000000000000 ]] || continue
  if git diff --name-only "$viejo" "$nuevo" | grep -q '^\.github/workflows/'; then
    echo "refusing to allow a GitHub App to create or update workflow without workflows permission" >&2
    exit 1
  fi
done
HOOK
chmod +x "$ORIGIN/hooks/pre-receive"

# Un gh falso: los checks del "PR" siempre fallan; registra lo que se le pide y el texto de los comentarios
BIN="$BASE/bin"
mkdir -p "$BIN"
cat > "$BIN/gh" <<'FALSO'
#!/usr/bin/env bash
echo "gh $*" >> "$STUB_LOG"
case "$1 $2" in
  "api --paginate") printf '{"id":1,"name":"Backend (tests)","status":"completed","conclusion":"failure"}\n' ;;
  "pr comment") shift 2; while [[ $# -gt 0 ]]; do [[ "$1" == --body-file ]] && { echo "--- comentario" >> "$STUB_LOG"; cat "$2" >> "$STUB_LOG"; }; shift; done ;;
esac
exit 0
FALSO
chmod +x "$BIN/gh"
export STUB_LOG="$BASE/gh.log"

reiniciar_origin() { # $1 = commit que queda como punta de main
  git -C "$ORIGIN" tag -l | xargs -r git -C "$ORIGIN" tag -d >/dev/null 2>&1
  git -C "$FUENTE" push --quiet --force "$ORIGIN" "$1:refs/heads/main" 2>/dev/null
  if [[ -d "$WORK/.git" ]]; then
    git -C "$WORK" fetch --quiet origin main && git -C "$WORK" checkout --quiet -f -B main origin/main && git -C "$WORK" clean -fdxq
  else
    git clone --quiet "$ORIGIN" "$WORK" 2>/dev/null
  fi
  : > "$STUB_LOG"
}

correr_guardian() { # $1 = commit fusionado, $2 = numero de PR
  ( cd "$WORK" && env PATH="$BIN:$PATH" GITHUB_REPOSITORY=org/repo GH_TOKEN=falso RAMA=main PR_NUMBER="$2" PR_COMMITS=1 \
      HEAD_SHA="$HEX40" MERGE_SHA="$1" GUARDIAN_MODO=revertir RULESET="$BASE/ruleset-main.json" \
      bash "$GUARDIAN" ) > "$BASE/salida.txt" 2>&1
  CODIGO=$?
}

MERGES=0
CON_WORKFLOWS=0
# Con --solo-red no se recorre el historial: la lista queda vacia
HISTORIAL=(origin/main)
[[ -n "$SOLO_RED" ]] && HISTORIAL+=(--max-count=0)
while IFS=$'\t' read -r sha asunto; do
  [[ -n "$sha" ]] || continue
  MERGES=$((MERGES + 1))
  corto="${sha:0:7}"
  padres=$(( $(git -C "$FUENTE" rev-list --parents -n 1 "$sha" | wc -w) - 1 ))
  archivos="$(git -C "$FUENTE" diff-tree --no-commit-id --name-only -r -m --first-parent "$sha" | wc -l | tr -d ' ')"
  pr="$(grep -oE '\(#[0-9]+\)$|pull request #[0-9]+' <<< "$asunto" | grep -oE '[0-9]+' | head -1)"
  reiniciar_origin "$sha"

  if (( padres == 0 )); then
    # El commit inicial: revertirlo borraria todo el repositorio, el guardian se niega
    correr_guardian "$sha" "${pr:-1}"
    verificar "$corto: el commit inicial NO se revierte" test "$(git -C "$ORIGIN" rev-parse main)" = "$sha"
    verificar "$corto: lo explica" grep -qF "commit inicial" "$STUB_LOG"
    printf '  ok     %s  commit inicial: el guardian se niega  (%s)\n' "$corto" "${asunto:0:60}"
    continue
  fi

  correr_guardian "$sha" "${pr:-1}"
  verificar "$corto: termina en 3" test "$CODIGO" -eq 3
  verificar "$corto: main avanza exactamente un commit" test "$(git -C "$ORIGIN" rev-list --count "$sha..main")" -eq 1
  verificar "$corto: el contenido queda IDENTICO al de antes del commit" test "$(git -C "$ORIGIN" rev-parse 'main^{tree}')" = "$(git -C "$ORIGIN" rev-parse "$sha^1^{tree}")"
  verificar "$corto: el commit original sigue en el historial" git -C "$ORIGIN" merge-base --is-ancestor "$sha" main
  verificar "$corto: el revert lo dice en su asunto" test "$(git -C "$ORIGIN" log -1 --format=%s main | cut -c1-8)" = 'Revert "'
  verificar "$corto: queda la etiqueta de respaldo" git -C "$ORIGIN" rev-parse --verify --quiet "respaldo/main-$corto^{commit}"
  verificar "$corto: se pide redesplegar" grep -qF "gh workflow run deploy.yml" "$STUB_LOG"
  tipo="squash"; (( padres > 1 )) && tipo="merge commit"

  # Si toco .github/workflows, GitHub no dejaria que el guardian publique el revert: debe quedarse quieto
  if git -C "$FUENTE" diff-tree --no-commit-id --name-only -r -m --first-parent "$sha" | grep -q '^\.github/workflows/'; then
    CON_WORKFLOWS=$((CON_WORKFLOWS + 1))
    reiniciar_origin "$sha"
    : > "$ORIGIN/restringir-workflows"
    correr_guardian "$sha" "${pr:-1}"
    rm -f "$ORIGIN/restringir-workflows"
    verificar "$corto (toca workflows): main no cambia si GitHub rechaza el push" test "$(git -C "$ORIGIN" rev-parse main)" = "$sha"
    verificar "$corto (toca workflows): avisa que no se pudo publicar" grep -qF "No se pudo publicar el revert" "$STUB_LOG"
    verificar "$corto (toca workflows): no pide despliegue" no_contiene "$STUB_LOG" "gh workflow run"
    tipo="$tipo, toca workflows"
  fi
  printf '  ok     %s  %-22s %3s archivos  %s\n' "$corto" "$tipo" "$archivos" "${asunto:0:56}"
done < <(git -C "$FUENTE" log --first-parent --format='%H%x09%s' "${HISTORIAL[@]}")

echo "  -> $MERGES commits reales de main revertidos; $CON_WORKFLOWS de ellos tocaban .github/workflows"
# Una prueba que no ejecuta nada no prueba nada
[[ -n "$SOLO_RED" ]] || verificar "se recorrio el historial real (al menos 10 commits)" test "$MERGES" -ge 10

# ===================================================================================
# Parte 2: el guardian real, en seco, sobre los checks reales de GitHub
# ===================================================================================
if [[ -z "$SIN_RED" ]]; then
  echo
  echo "Parte 2: el guardian real sobre los checks reales de GitHub (solo lectura)"
  if ! command -v gh >/dev/null 2>&1 || ! gh auth status >/dev/null 2>&1; then
    echo "  (se omite: gh no esta instalado o no tiene sesion iniciada; usa --sin-red para no ver este aviso)"
  else
    REAL_GH="$(command -v gh)"
    export REAL_GH
    REPO="$(cd "$RAIZ" && gh repo view --json nameWithOwner --jq .nameWithOwner | tr -d '\r')"
    BLOQUEOS="$BASE/gh-bloqueado.log"
    : > "$BLOQUEOS"
    export BLOQUEOS

    # Un gh que SOLO deja leer los check-runs; todo lo demas se bloquea y queda anotado
    BIN2="$BASE/bin-solo-lectura"
    mkdir -p "$BIN2"
    cat > "$BIN2/gh" <<'SOLO_LECTURA'
#!/usr/bin/env bash
if [[ "$1" == api && "$2" == --paginate && "$3" =~ ^repos/[A-Za-z0-9._-]+/[A-Za-z0-9._-]+/commits/[0-9a-f]{40}/check-runs\?per_page=100$ && "$*" != *" -X "* && "$*" != *"--method"* && "$*" != *"--input"* && "$*" != *" -f "* && "$*" != *" -F "* ]]; then
  exec "$REAL_GH" "$@"
fi
echo "BLOQUEADO: gh $*" >> "$BLOQUEOS"
exit 99
SOLO_LECTURA
    chmod +x "$BIN2/gh"

    # origin con la punta real de main
    reiniciar_origin "$(git -C "$FUENTE" rev-parse origin/main)"

    en_seco() { # numero de PR, head, merge, cuantos commits tenia  -> deja el codigo en $CODIGO
      ( cd "$WORK" && env PATH="$BIN2:$PATH" GITHUB_REPOSITORY="$REPO" RAMA=main PR_NUMBER="$1" HEAD_SHA="$2" MERGE_SHA="$3" PR_COMMITS="${4:-1}" \
          GUARDIAN_MODO=revertir GUARDIAN_SECO=1 RULESET="$BASE/ruleset-main.json" bash "$GUARDIAN" ) > "$BASE/salida2.txt" 2>&1
      CODIGO=$?
    }

    CUMPLEN=0
    ANTIGUOS=0
    RECIENTES_FALLAN=""
    PRS="$(cd "$RAIZ" && gh pr list --state merged --limit 200 --json number,headRefOid,mergeCommit \
      --jq 'sort_by(.number) | .[] | "\(.number)\t\(.headRefOid)\t\(.mergeCommit.oid)"' | tr -d '\r')"
    # Una prueba que no recibe datos no prueba nada
    verificar "se obtuvo la lista de PRs fusionados (al menos 10)" test "$(grep -c . <<< "$PRS")" -ge 10
    while IFS=$'\t' read -r numero head merge; do
      [[ -n "$numero" ]] || continue
      commits="$(cd "$RAIZ" && gh api "repos/$REPO/pulls/$numero" --jq .commits | tr -d '\r')"
      en_seco "$numero" "$head" "$merge" "$commits"
      if [[ "$CODIGO" -eq 0 ]]; then
        CUMPLEN=$((CUMPLEN + 1))
      elif [[ "$CODIGO" -eq 3 ]]; then
        if (( numero < 16 )); then ANTIGUOS=$((ANTIGUOS + 1)); else RECIENTES_FALLAN="$RECIENTES_FALLAN #$numero"; fi
      else
        verificar "PR #$numero: el guardian termino con un error inesperado ($CODIGO)" false
      fi
      # Los PRs que sabemos en verde deben cumplir los 6 checks
      case "$numero" in 36 | 44 | 50) verificar "PR #$numero (verde en la realidad): el guardian no lo marca" test "$CODIGO" -eq 0 ;; esac
    done <<< "$PRS"
    verificar "el guardian reviso al menos 10 PRs reales" test $((CUMPLEN + ANTIGUOS)) -ge 10
    printf '  -> PRs fusionados: %s cumplen los 6 checks; %s anteriores al #16 no (el CI de entonces no tenia todos los checks)\n' "$CUMPLEN" "$ANTIGUOS"
    if [[ -n "$RECIENTES_FALLAN" ]]; then
      printf '  !! PRs desde el #16 que el guardian habria marcado:%s  (revisalos: en esa fecha el CI estaba completo)\n' "$RECIENTES_FALLAN"
    else
      echo "  -> cero falsos positivos en los PRs desde el #16, cuando el CI ya tenia los 6 checks"
    fi

    # Fallos reales del historial: el guardian debe detectarlos, y no marcar el commit que los corrigio
    primer_commit() { cd "$RAIZ" && gh api "repos/$REPO/pulls/$1/commits" --jq ".[$2].sha" | tr -d '\r'; }
    for caso in "37 0 Backend (tests)" "39 0 SonarQube (Quality Gate)"; do
      read -r n idx nombre <<< "$caso"
      sha="$(primer_commit "$n" "$idx")"
      en_seco "$n" "$sha" "$sha"
      verificar "PR #$n, commit ${sha:0:7} (fallo real): el guardian termina en 3" test "$CODIGO" -eq 3
      verificar "PR #$n, commit ${sha:0:7}: nombra '$nombre'" grep -qF "| $nombre | fallo" "$BASE/salida2.txt"
      ultimo="$(cd "$RAIZ" && gh api "repos/$REPO/pulls/$n/commits" --jq '.[-1].sha' | tr -d '\r')"
      en_seco "$n" "$ultimo" "$ultimo"
      verificar "PR #$n, commit ${ultimo:0:7} (la correccion): el guardian no lo marca" test "$CODIGO" -eq 0
      printf '  ok     PR #%s: primer commit marcado por %s; el commit que lo corrigio, limpio\n' "$n" "$nombre"
    done
    verificar "el ensayo en seco no intento escribir en GitHub (nada bloqueado)" test ! -s "$BLOQUEOS"
    [[ -s "$BLOQUEOS" ]] && cat "$BLOQUEOS"
  fi
fi

echo
echo "Resultado: $OK comprobaciones ok, $FALLO con fallo"
[[ "$FALLO" -eq 0 ]]
