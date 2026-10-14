#!/usr/bin/env bash
# CCPIP-22 (RF-101, RNF-26): guardian de main.
#
# El plan gratuito de GitHub no permite proteger ramas de un repo privado, asi que nada impide
# fusionar un PR con checks en rojo. Este script corre justo despues de cada merge y aplica lo que
# la proteccion aplicaria: si el PR no cumplia los checks obligatorios de docs/ruleset-main.json,
# avisa y, solo si se pidio, revierte el merge y pide redesplegar main. Ver docs/devsecops.md ("Guardian de main").
#
# Entorno (lo pone .github/workflows/guardian-main.yml):
#   GITHUB_REPOSITORY, GH_TOKEN  repo y token con contents/pull-requests/issues/actions: write y checks: read
#   RAMA                         rama que se vigila: main (o prueba/* en un simulacro)
#   GUARDIAN_MODO                avisar (por defecto) | revertir | apagado
#   PR_NUMBER, PR_COMMITS, HEAD_SHA, MERGE_SHA, ETIQUETAS   datos del PR fusionado (ETIQUETAS separadas por coma)
#   SIMULACRO=1, CHECKS_FALLIDOS   simulacro manual: finge esos checks incumplidos sobre RAMA
#   GUARDIAN_ESPERA=3            segundos base entre reintentos al leer los checks (3, 6, 9...); 0 en las pruebas
#   GUARDIAN_SECO=1              ensayo en seco: lee de GitHub y prueba el revert en local, pero NO escribe nada
#                                (ni comentarios, issues, etiquetas, pushes ni despliegues)
#
# Termina con 0 si todo cumplia, con 3 si hubo incumplimientos (el job queda en rojo y GitHub avisa
# a quien lo disparo) y con 1 si algo no cuadra y no se hizo nada.
set -euo pipefail

REPO="${GITHUB_REPOSITORY:?falta GITHUB_REPOSITORY}"
RAMA="${RAMA:?falta RAMA}"
MODO="${GUARDIAN_MODO:-avisar}"
SIMULACRO="${SIMULACRO:-}"
SECO="${GUARDIAN_SECO:-}"
ESPERA="${GUARDIAN_ESPERA:-3}"
PR="${PR_NUMBER:-}"
PR_COMMITS="${PR_COMMITS:-}"
HEAD_SHA="${HEAD_SHA:-}"
ETIQUETAS="${ETIQUETAS:-}"
RULESET="${RULESET:-docs/ruleset-main.json}"
AQUI="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# Un interprete que de verdad ejecute (en Windows, "python3" puede ser un acceso directo a la tienda)
PY=""
for candidato in python3 python; do
  if command -v "$candidato" >/dev/null 2>&1 && "$candidato" -c 'import sys' >/dev/null 2>&1; then
    PY="$candidato"
    break
  fi
done

log() { printf '[guardian] %s\n' "$*"; }
fallar() { printf '::error::%s\n' "$*"; exit 1; }

# La API de GitHub falla de vez en cuando (5xx, limites): se reintenta antes de rendirse, porque rendirse
# significa dejar un merge sin vigilar. Si tras todos los intentos no hay datos, no se actua.
[[ "$ESPERA" =~ ^[0-9]+$ ]] || ESPERA=3
leer_checks() { # $1 = SHA del PR, $2 = archivo de salida
  local intento
  for intento in 1 2 3 4; do
    if gh api --paginate "repos/$REPO/commits/$1/check-runs?per_page=100" \
      --jq '.check_runs[] | {id, name, status, conclusion}' > "$2" 2> "$TMP/gh-error.txt"; then
      return 0
    fi
    log "No se pudieron leer los checks (intento $intento de 4): $(head -c 200 "$TMP/gh-error.txt" | tr '\n' ' ')"
    (( intento < 4 )) && sleep $((intento * ESPERA))
  done
  return 1
}

# ---------- Condiciones para actuar: ante la duda, no se hace nada ----------
[[ -n "$PY" ]] || fallar "Falta Python en el runner."
# Nada de lo que llega por el entorno se usa sin validar: nombres y SHAs terminan como argumentos de git y gh
if [[ ! "$RAMA" =~ ^[A-Za-z0-9._/-]+$ || "$RAMA" == -* ]] || ! git check-ref-format "refs/heads/$RAMA"; then
  fallar "Nombre de rama no valido."
fi
SHA40='^[0-9a-f]{40}$'
[[ -z "${MERGE_SHA:-}" || "${MERGE_SHA:-}" =~ $SHA40 ]] || fallar "MERGE_SHA no es un SHA completo."
[[ -z "$HEAD_SHA" || "$HEAD_SHA" =~ $SHA40 ]] || fallar "HEAD_SHA no es un SHA completo."
[[ "$PR" =~ ^[0-9]*$ ]] || fallar "PR_NUMBER no es un numero."
[[ "$PR_COMMITS" =~ ^[0-9]*$ ]] || fallar "PR_COMMITS no es un numero."
if [[ -n "$SIMULACRO" ]]; then
  [[ "$RAMA" == prueba/?* ]] || fallar "El simulacro solo opera sobre ramas prueba/*, no sobre '$RAMA'."
else
  [[ "$RAMA" == "main" ]] || fallar "El guardian solo vigila main, no '$RAMA'."
fi
case "$MODO" in
  apagado) log "Guardian apagado (GUARDIAN_MODO=apagado): no se hace nada."; exit 0 ;;
  avisar | revertir) ;;
  *) log "GUARDIAN_MODO no es valido: se usa 'avisar'."; MODO=avisar ;;
esac

TMP="$(mktemp -d)"
trap 'git worktree remove --force "$TMP/trabajo" >/dev/null 2>&1 || true; rm -rf "$TMP"' EXIT

git fetch --quiet origin "$RAMA"
if [[ -n "$SIMULACRO" ]]; then
  MERGE_SHA="$(git rev-parse "origin/$RAMA")"
else
  MERGE_SHA="${MERGE_SHA:?falta MERGE_SHA}"
fi

# ---------- Que checks no cumplio el PR ----------
INCUMPLIDOS="$TMP/incumplidos.tsv"
if [[ -n "$SIMULACRO" ]]; then
  : > "$INCUMPLIDOS"
  IFS=',' read -ra FINGIDOS <<< "${CHECKS_FALLIDOS:-Backend (tests)}"
  for nombre in "${FINGIDOS[@]}"; do
    nombre="${nombre#"${nombre%%[![:space:]]*}"}"
    printf '%s\tsimulado\n' "${nombre%"${nombre##*[![:space:]]}"}" >> "$INCUMPLIDOS"
  done
else
  [[ -n "$HEAD_SHA" ]] || fallar "Falta HEAD_SHA."
  leer_checks "$HEAD_SHA" "$TMP/ejecuciones.jsonl" || fallar "No se pudieron leer los checks del PR tras 4 intentos: no se actua."
  "$PY" "$AQUI/evaluar_checks.py" "$RULESET" "$TMP/ejecuciones.jsonl" | tr -d '\r' > "$INCUMPLIDOS"
fi

if [[ ! -s "$INCUMPLIDOS" ]]; then
  log "El PR cumplia todos los checks obligatorios. Nada que hacer."
  exit 0
fi

# ---------- Mensajes ----------
comentar() {
  if [[ -n "$SIMULACRO" || -n "$SECO" || -z "$PR" ]]; then
    cat "$1"
  else
    gh pr comment "$PR" --repo "$REPO" --body-file "$1" || log "No se pudo comentar el PR."
  fi
}

abrir_issue() {
  if [[ -n "$SIMULACRO" || -n "$SECO" || -z "$PR" ]]; then
    printf '(issue) %s\n' "$1"
    cat "$2"
  else
    gh label create forzar-merge --repo "$REPO" --color D93F0B \
      --description "El guardian de main no actua sobre este PR" >/dev/null 2>&1 || true
    gh issue create --repo "$REPO" --title "$1" --body-file "$2" || log "No se pudo abrir el issue."
  fi
}

tabla() {
  printf '| Check | Estado |\n|---|---|\n'
  while IFS=$'\t' read -r nombre estado; do
    printf '| %s | %s |\n' "$nombre" "$estado"
  done < "$INCUMPLIDOS"
}

# Etiqueta de salida: un admin puede dejar pasar un PR a proposito
ETIQUETAS_MIN="${ETIQUETAS,,}"
if [[ -z "$SIMULACRO" && ",$ETIQUETAS_MIN," == *",forzar-merge,"* ]]; then
  {
    echo "**Guardian de main (CCPIP-22):** este PR se fusiono sin cumplir los checks obligatorios, pero tiene la etiqueta \`forzar-merge\`: no se hace nada."
    echo
    tabla
  } > "$TMP/forzado.md"
  comentar "$TMP/forzado.md"
  log "PR con forzar-merge: no se actua."
  exit 3
fi

# ---------- Revertir (solo si se pidio, y solo si es seguro) ----------
MOTIVO=""
REVERTIDO=""
revertir() {
  local trabajo="$TMP/trabajo" tag titulo padres
  local args=(--no-edit)

  # Si hay commits despues del merge, revertirlo podria dejar la rama rota sin que nadie lo vea
  # (el push de este workflow no dispara el CI): en ese caso solo se avisa.
  if [[ "$(git rev-parse "origin/$RAMA")" != "$MERGE_SHA" ]]; then
    MOTIVO="Hay commits posteriores en \`$RAMA\`: revertir sin pasar por el CI podria dejarla rota. Revisalo a mano."
    return 1
  fi

  # Un merge commit (dos padres) se revierte respecto a su primer padre; un squash no necesita -m.
  # Sin padres es el commit inicial: revertirlo borraria todo el contenido del repositorio.
  padres=$(( $(git rev-list --parents -n 1 "$MERGE_SHA" | wc -w) - 1 ))
  if (( padres == 0 )); then
    MOTIVO="Es el commit inicial del repositorio: revertirlo lo borraria todo."
    return 1
  fi
  if (( padres > 1 )); then args+=(-m 1); fi

  # Un PR de varios commits fusionado con un solo padre es un squash (un commit "... (#N)") o un "rebase and
  # merge" (los commits originales, uno tras otro). Revertir solo el ultimo de un rebase dejaria el PR a
  # medias: si no es claramente un squash o un PR de un solo commit, no se revierte.
  if (( padres == 1 )) && [[ -z "$SIMULACRO" && "$PR_COMMITS" != 1 && "$(git log -1 --format=%s "$MERGE_SHA")" != *"(#$PR)" ]]; then
    MOTIVO="Parece un \"rebase and merge\" de varios commits: revertir solo el ultimo dejaria el PR a medias. Revisalo a mano."
    return 1
  fi

  # Dentro de una funcion llamada desde un "if", set -e no corta el script: cada paso se comprueba a mano
  if ! git worktree add --quiet --detach "$trabajo" "$MERGE_SHA" \
    || ! git -C "$trabajo" config user.name "guardian-main[bot]" \
    || ! git -C "$trabajo" config user.email "41898282+github-actions[bot]@users.noreply.github.com"; then
    MOTIVO="No se pudo preparar el espacio de trabajo del revert."
    return 1
  fi

  if ! git -C "$trabajo" revert "${args[@]}" "$MERGE_SHA" > "$TMP/revert.log" 2>&1; then
    git -C "$trabajo" revert --abort >/dev/null 2>&1 || true
    MOTIVO="El revert no se pudo aplicar (conflictos o sin cambios). Revisalo a mano."
    return 1
  fi

  titulo="$(git log -1 --format=%s "$MERGE_SHA")"
  {
    printf 'Revert "%s"\n\n' "$titulo"
    printf 'Revertido por el guardian de main (CCPIP-22): el PR #%s se fusiono sin cumplir los checks obligatorios.\n' "${PR:-simulacro}"
    printf 'Para reintegrarlo, corrige el problema y abre un PR nuevo, o revierte este commit.\n'
  } > "$TMP/mensaje.txt"
  if ! git -C "$trabajo" commit --amend --quiet -F "$TMP/mensaje.txt"; then
    MOTIVO="No se pudo preparar el mensaje del revert."
    return 1
  fi

  # Red de seguridad: revertir la punta debe dejar EXACTAMENTE el contenido que habia antes del PR.
  # Si no es asi, algo no cuadra y no se publica nada.
  if [[ "$(git -C "$trabajo" rev-parse 'HEAD^{tree}')" != "$(git rev-parse "$MERGE_SHA^1^{tree}")" ]]; then
    MOTIVO="El revert no deja el mismo contenido que habia antes del PR. Revisalo a mano."
    return 1
  fi

  # Ensayo en seco: el revert aplica limpio y deja el contenido de antes, y hasta aqui no se escribio nada
  if [[ -n "$SECO" ]]; then
    REVERTIDO="$(git -C "$trabajo" rev-parse --short HEAD)"
    return 0
  fi

  # Un nombre facil de encontrar para el estado anterior al revert (el commit sigue en el historial)
  tag="respaldo/${RAMA//\//-}-${MERGE_SHA:0:7}"
  git tag "$tag" "$MERGE_SHA" 2>/dev/null || true
  git push --quiet origin "refs/tags/$tag" 2>/dev/null || log "No se pudo publicar la etiqueta $tag (no es grave)."

  # Ultima comprobacion antes de publicar: nadie se adelanto mientras tanto
  git fetch --quiet origin "$RAMA"
  if [[ "$(git rev-parse "origin/$RAMA")" != "$MERGE_SHA" ]]; then
    MOTIVO="Alguien publico en \`$RAMA\` mientras se preparaba el revert."
    return 1
  fi
  # Nunca --force: si la rama se movio, el push falla y no se pierde nada
  if ! git -C "$trabajo" push --quiet origin "HEAD:refs/heads/$RAMA" 2> "$TMP/push.log"; then
    MOTIVO="No se pudo publicar el revert (por ejemplo, el PR tocaba .github/workflows, que este token no puede escribir). Revisalo a mano."
    return 1
  fi
  REVERTIDO="$(git -C "$trabajo" rev-parse --short HEAD)"
}

# Lo normal es que el commit malo nunca se desplegue (deploy.yml exige el CI), pero uno marcado por el
# guardian cuyo CI en main si pasa si se despliega. Tras un revert se pide un despliegue de la rama ya
# revertida: deja el servidor igual que main y, de paso, corre el CI sobre el revert, que el push de
# este workflow no dispara. Si no se puede pedir, se dice: no es motivo para deshacer el revert.
REDESPLIEGUE=""
redesplegar() {
  if [[ -n "$SECO" ]]; then
    REDESPLIEGUE="(en seco) se pediria un redespliegue de \`$RAMA\`."
  elif [[ -n "$SIMULACRO" ]]; then
    REDESPLIEGUE="(simulacro) se pediria un redespliegue de \`$RAMA\`."
  elif gh workflow run deploy.yml --repo "$REPO" --ref "$RAMA"; then
    REDESPLIEGUE="Se pidio un **redespliegue** de \`$RAMA\`: el CI corre de nuevo sobre el revert y el servidor queda igual que la rama."
  else
    REDESPLIEGUE="**No se pudo pedir el redespliegue**: ejecuta a mano el workflow *Deploy* sobre \`$RAMA\`."
  fi
}

if [[ "$MODO" == revertir ]]; then
  # Se llama a revertir UNA sola vez: es la que escribe
  if revertir; then
    redesplegar
    if [[ -n "$SECO" ]]; then
      RESULTADO="(en seco) se revertiria el merge: el revert aplica limpio, deja el contenido de antes del PR (\`$REVERTIDO\`) y no se publico nada. $REDESPLIEGUE"
    else
      RESULTADO="Se **revirtio** automaticamente en \`$REVERTIDO\`. El estado anterior queda en la etiqueta \`respaldo/${RAMA//\//-}-${MERGE_SHA:0:7}\`. $REDESPLIEGUE"
    fi
  else
    RESULTADO="**No se revirtio.** $MOTIVO"
  fi
else
  RESULTADO="Modo \`avisar\`: no se modifico el codigo."
fi

# ---------- Avisos ----------
{
  echo "**Guardian de main (CCPIP-22):** este PR se fusiono sin cumplir los checks obligatorios de \`docs/ruleset-main.json\`."
  echo
  tabla
  echo
  echo "$RESULTADO"
  echo
  echo "Si fue a proposito, etiqueta el PR con \`forzar-merge\` antes de fusionar."
} > "$TMP/aviso.md"
comentar "$TMP/aviso.md"
abrir_issue "main: el PR #${PR:-simulacro} se fusiono sin cumplir los checks obligatorios" "$TMP/aviso.md"

log "Incumplimientos detectados. $RESULTADO"
exit 3
