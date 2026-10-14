#!/usr/bin/env bash
# CCPIP-22: prueba el guardian de main SIN tocar GitHub ni este repo.
#
# Crea repos de juguete en una carpeta temporal (un "origin" y una copia de trabajo), pone un `gh`
# falso en el PATH y corre guardian-main.sh en cada escenario. Hace falta bash, git y python.
#   bash .github/guardian/probar.sh
set -uo pipefail

RAIZ="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BASE="$(mktemp -d)"
trap 'rm -rf "$BASE"' EXIT
export GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_SYSTEM=/dev/null GIT_TERMINAL_PROMPT=0
export GIT_AUTHOR_NAME=prueba GIT_AUTHOR_EMAIL=prueba@example.com GIT_COMMITTER_NAME=prueba GIT_COMMITTER_EMAIL=prueba@example.com

OK=0
FALLO=0
verificar() { # descripcion, comando...
  local descripcion="$1"
  shift
  if "$@" >/dev/null 2>&1; then OK=$((OK + 1)); printf '  ok     %s\n' "$descripcion"
  else FALLO=$((FALLO + 1)); printf '  FALLO  %s\n' "$descripcion"; fi
}
contiene() { grep -qF -- "$2" "$1"; }
no_contiene() { ! grep -qF -- "$2" "$1"; }
no_contiene_regex() { ! grep -qE -- "$2" "$1"; }

# Un Python que de verdad ejecute (en Windows, "python3" puede ser un acceso directo a la tienda)
PY_TEST=""
for candidato in python3 python; do
  if command -v "$candidato" >/dev/null 2>&1 && "$candidato" -c 'import sys' >/dev/null 2>&1; then PY_TEST="$candidato"; break; fi
done
python3_o_python() { "$PY_TEST" "$@"; }
REAL_GIT="$(command -v git)"
export REAL_GIT
HEX40="$(printf 'a%.0s' $(seq 1 40))"
VACIO="$BASE/vacio.jsonl"
: > "$VACIO"

# --- Un gh falso: registra lo que se le pide y responde los check-runs que diga STUB_RUNS ---
BIN="$BASE/bin"
mkdir -p "$BIN"
cat > "$BIN/gh" <<'FALSO'
#!/usr/bin/env bash
echo "gh $*" >> "$STUB_LOG"
case "$1 $2" in
  "api --paginate")
    # STUB_FALLAS=N: las primeras N llamadas fallan (como una falla de red) y luego responde
    if [[ -n "${STUB_FALLAS:-}" ]]; then
      hechas="$(cat "$STUB_CUENTA" 2>/dev/null || echo 0)"
      echo $((hechas + 1)) > "$STUB_CUENTA"
      [[ "$hechas" -lt "$STUB_FALLAS" ]] && { echo "HTTP 502: Bad Gateway" >&2; exit 1; }
    fi
    cat "$STUB_RUNS" ;;
  "pr comment") shift 2; while [[ $# -gt 0 ]]; do [[ "$1" == --body-file ]] && { echo "--- comentario" >> "$STUB_LOG"; cat "$2" >> "$STUB_LOG"; }; shift; done ;;
  "workflow run") [[ -z "${STUB_FALLAR_DEPLOY:-}" ]] || exit 1 ;;
  "issue create") shift 2; while [[ $# -gt 0 ]]; do [[ "$1" == --body-file ]] && { echo "--- issue" >> "$STUB_LOG"; cat "$2" >> "$STUB_LOG"; }; shift; done ;;
esac
exit 0
FALSO
chmod +x "$BIN/gh"

# --- Escenario: un origin con main = v1, y un PR "fusionado" que pasa app.txt a v2 ---
nuevo() {
  ORIGIN="$BASE/origin.git"
  WORK="$BASE/trabajo"
  rm -rf "$ORIGIN" "$WORK"
  git init --quiet --bare --initial-branch=main "$ORIGIN"
  git clone --quiet "$ORIGIN" "$WORK" 2>/dev/null
  git -C "$WORK" checkout --quiet -b main
  mkdir -p "$WORK/docs" "$WORK/.github"
  cp "$RAIZ/docs/ruleset-main.json" "$WORK/docs/"
  cp -r "$RAIZ/.github/guardian" "$WORK/.github/"
  echo v1 > "$WORK/app.txt"
  git -C "$WORK" add -A && git -C "$WORK" commit --quiet -m "inicial"
  git -C "$WORK" push --quiet origin main
  echo v2 > "$WORK/app.txt"
  git -C "$WORK" commit --quiet -am "CCPIP-1: cambio del PR"
  git -C "$WORK" push --quiet origin main
  MERGE="$(git -C "$WORK" rev-parse HEAD)"
  PADRE="$(git -C "$WORK" rev-parse HEAD~1)"
  export STUB_LOG="$BASE/gh.log" STUB_RUNS="$BASE/ejecuciones.jsonl"
  : > "$STUB_LOG"
  ejecuciones success success success success success success
}

# Un check-run por cada uno de los 6 obligatorios, en el orden de docs/ruleset-main.json
NOMBRES=("Backend (tests)" "SonarQube (Quality Gate)" "Frontend (tests + build + audit)" "Punta a punta (Playwright)" "Secretos filtrados (TruffleHog)" "Dependencias, Dockerfiles y Terraform (Trivy)")
ejecuciones() { # conclusion por check; "pendiente" = sigue corriendo; "-" = no existe
  : > "$STUB_RUNS"
  local i=0 c
  for c in "$@"; do
    if [[ "$c" == pendiente ]]; then
      printf '{"id":%d,"name":"%s","status":"in_progress","conclusion":null}\n' $((100 + i)) "${NOMBRES[$i]}" >> "$STUB_RUNS"
    elif [[ "$c" != "-" ]]; then
      printf '{"id":%d,"name":"%s","status":"completed","conclusion":"%s"}\n' $((100 + i)) "${NOMBRES[$i]}" "$c" >> "$STUB_RUNS"
    fi
    i=$((i + 1))
  done
}

correr() { # VAR=valor...  -> deja la salida en $SALIDA y el codigo en $CODIGO
  SALIDA="$BASE/salida.txt"
  ( cd "$WORK" && env PATH="$BIN:$PATH" GITHUB_REPOSITORY=org/repo GH_TOKEN=falso RAMA=main PR_NUMBER=7 PR_COMMITS=1 GUARDIAN_ESPERA=0 \
      HEAD_SHA="$HEX40" MERGE_SHA="$MERGE" "$@" bash .github/guardian/guardian-main.sh ) > "$SALIDA" 2>&1
  CODIGO=$?
}
origin_main() { git -C "$ORIGIN" rev-parse main; }

echo "1. Todos los checks cumplidos: no hace nada"
nuevo
correr GUARDIAN_MODO=revertir
verificar "termina en 0" test "$CODIGO" -eq 0
verificar "main no cambia" test "$(origin_main)" = "$MERGE"
verificar "no comenta ni abre issues" no_contiene "$STUB_LOG" "comentario"

echo "2. Un check fallo, modo avisar: avisa y NO toca el codigo"
nuevo
ejecuciones failure success success success success success
correr GUARDIAN_MODO=avisar
verificar "termina en 3 (job en rojo)" test "$CODIGO" -eq 3
verificar "main no cambia" test "$(origin_main)" = "$MERGE"
verificar "comenta en el PR 7 con el check incumplido" contiene "$STUB_LOG" "gh pr comment 7"
verificar "la tabla nombra Backend (tests) y su fallo" contiene "$STUB_LOG" "| Backend (tests) | fallo (failure) |"
verificar "abre un issue" contiene "$STUB_LOG" "gh issue create"
verificar "dice que no se modifico el codigo" contiene "$STUB_LOG" "no se modifico el codigo"
verificar "no pide ningun despliegue" no_contiene "$STUB_LOG" "gh workflow run"

echo "3. Pendientes, inexistentes y cancelados cuentan como incumplidos"
nuevo
ejecuciones success pendiente success - cancelled success
correr GUARDIAN_MODO=avisar
verificar "pendiente" contiene "$STUB_LOG" "| SonarQube (Quality Gate) | pendiente |"
verificar "no existe" contiene "$STUB_LOG" "| Punta a punta (Playwright) | no existe |"
verificar "cancelado" contiene "$STUB_LOG" "| Secretos filtrados (TruffleHog) | fallo (cancelled) |"
verificar "lo que cumple no se lista" no_contiene "$STUB_LOG" "| Backend (tests) |"

echo "4. Un check re-ejecutado: vale la ultima ejecucion"
nuevo
ejecuciones success success success success success success
printf '{"id":999,"name":"Backend (tests)","status":"completed","conclusion":"failure"}\n' >> "$STUB_RUNS"
correr GUARDIAN_MODO=avisar
verificar "la ultima (fallida) gana" test "$CODIGO" -eq 3
nuevo
printf '{"id":1,"name":"Backend (tests)","status":"completed","conclusion":"failure"}\n' > "$BASE/primero.jsonl"
cat "$BASE/primero.jsonl" "$STUB_RUNS" > "$BASE/mezcla.jsonl" && cp "$BASE/mezcla.jsonl" "$STUB_RUNS"
correr GUARDIAN_MODO=avisar
verificar "un fallo viejo ya superado no cuenta" test "$CODIGO" -eq 0

echo "5. Modo revertir, el merge es la punta de main: revierte sin perder historia"
nuevo
ejecuciones failure success success success success success
correr GUARDIAN_MODO=revertir
verificar "termina en 3" test "$CODIGO" -eq 3
verificar "main avanzo un commit" test "$(git -C "$ORIGIN" rev-list --count "$MERGE..main")" -eq 1
verificar "app.txt vuelve a v1" test "$(git -C "$ORIGIN" show main:app.txt)" = "v1"
verificar "el commit malo sigue en el historial" git -C "$ORIGIN" merge-base --is-ancestor "$MERGE" main
verificar "el mensaje explica el revert" contiene <(git -C "$ORIGIN" log -1 --format=%B main) "Revertido por el guardian de main"
verificar "el mensaje es 'Revert ...'" test "$(git -C "$ORIGIN" log -1 --format=%s main)" = 'Revert "CCPIP-1: cambio del PR"'
verificar "queda una etiqueta de respaldo en el commit anterior al revert" test "$(git -C "$ORIGIN" rev-parse "respaldo/main-${MERGE:0:7}^{commit}")" = "$MERGE"
verificar "avisa que se revirtio" contiene "$STUB_LOG" "Se **revirtio** automaticamente"
verificar "pide redesplegar main con el workflow Deploy" contiene "$STUB_LOG" "gh workflow run deploy.yml --repo org/repo --ref main"
verificar "lo dice en el aviso" contiene "$STUB_LOG" "Se pidio un **redespliegue**"

echo "6. Modo revertir pero hay commits despues del merge: NO revierte"
nuevo
echo v3 > "$WORK/otro.txt"
git -C "$WORK" add -A && git -C "$WORK" commit --quiet -m "otro PR encima" && git -C "$WORK" push --quiet origin main
PUNTA="$(origin_main)"
ejecuciones failure success success success success success
correr GUARDIAN_MODO=revertir
verificar "main queda como estaba" test "$(origin_main)" = "$PUNTA"
verificar "avisa el motivo" contiene "$STUB_LOG" "Hay commits posteriores"
verificar "no crea etiqueta de respaldo" test -z "$(git -C "$ORIGIN" tag -l 'respaldo/*')"
verificar "tampoco pide un despliegue" no_contiene "$STUB_LOG" "gh workflow run"

echo "7. El push es rechazado: no pasa nada y se avisa"
nuevo
printf '#!/bin/sh\necho "rechazado por la prueba" >&2\nexit 1\n' > "$ORIGIN/hooks/pre-receive"
chmod +x "$ORIGIN/hooks/pre-receive"
ejecuciones failure success success success success success
correr GUARDIAN_MODO=revertir
verificar "main no cambia" test "$(origin_main)" = "$MERGE"
verificar "avisa que no se pudo publicar" contiene "$STUB_LOG" "No se pudo publicar el revert"
verificar "sin revert no hay despliegue" no_contiene "$STUB_LOG" "gh workflow run"
verificar "termina en 3, no en 0" test "$CODIGO" -eq 3

echo "8. Merge commit (dos padres): se revierte contra el primer padre"
nuevo
git -C "$WORK" checkout --quiet -b rama-pr "$PADRE"
echo ajeno > "$WORK/pr.txt" && git -C "$WORK" add -A && git -C "$WORK" commit --quiet -m "trabajo del PR"
git -C "$WORK" checkout --quiet main
git -C "$WORK" reset --quiet --hard "$PADRE"
git -C "$WORK" merge --quiet --no-ff rama-pr -m "Merge pull request #7" && git -C "$WORK" push --quiet --force origin main
MERGE="$(git -C "$WORK" rev-parse HEAD)"
ejecuciones failure success success success success success
correr GUARDIAN_MODO=revertir
verificar "revierte el merge" test "$(git -C "$ORIGIN" rev-list --count "$MERGE..main")" -eq 1
verificar "pr.txt desaparece de main" test -z "$(git -C "$ORIGIN" ls-tree --name-only main pr.txt)"
verificar "app.txt sigue en v1" test "$(git -C "$ORIGIN" show main:app.txt)" = "v1"

echo "9. Etiqueta forzar-merge: el guardian no actua, ni en modo revertir"
nuevo
ejecuciones failure success success success success success
correr GUARDIAN_MODO=revertir ETIQUETAS="bug,forzar-merge"
verificar "main no cambia" test "$(origin_main)" = "$MERGE"
verificar "lo deja dicho en el PR" contiene "$STUB_LOG" "forzar-merge"
verificar "no abre issue" no_contiene "$STUB_LOG" "gh issue create"

echo "10. Apagado y modos desconocidos"
nuevo
ejecuciones failure success success success success success
correr GUARDIAN_MODO=apagado
verificar "apagado: termina en 0 sin hacer nada" test "$CODIGO" -eq 0
verificar "apagado: main no cambia" test "$(origin_main)" = "$MERGE"
correr GUARDIAN_MODO=cualquiera
verificar "modo desconocido: se comporta como avisar (no revierte)" test "$(origin_main)" = "$MERGE"
correr GUARDIAN_MODO=
verificar "modo vacio: avisar por defecto" test "$(origin_main)" = "$MERGE"

echo "11. Solo vigila main (y prueba/* en simulacro)"
nuevo
ejecuciones failure success success success success success
correr RAMA=develop GUARDIAN_MODO=revertir
verificar "otra rama: se niega (1)" test "$CODIGO" -eq 1
correr RAMA=main SIMULACRO=1 GUARDIAN_MODO=revertir
verificar "simulacro sobre main: se niega (1)" test "$CODIGO" -eq 1
verificar "y main no cambia" test "$(origin_main)" = "$MERGE"
correr RAMA='main;rm -rf /' GUARDIAN_MODO=revertir
verificar "nombre de rama raro: se niega (1)" test "$CODIGO" -eq 1

echo "12. Simulacro sobre una rama prueba/: revierte esa rama y deja main intacta"
nuevo
git -C "$WORK" checkout --quiet -b prueba/guardian
echo roto > "$WORK/app.txt" && git -C "$WORK" commit --quiet -am "commit de prueba"
git -C "$WORK" push --quiet origin prueba/guardian
PRUEBA="$(git -C "$ORIGIN" rev-parse prueba/guardian)"
correr RAMA=prueba/guardian SIMULACRO=1 GUARDIAN_MODO=revertir CHECKS_FALLIDOS="Backend (tests), Punta a punta (Playwright)" PR_NUMBER= HEAD_SHA= MERGE_SHA=
verificar "la rama de prueba avanzo un revert" test "$(git -C "$ORIGIN" rev-list --count "$PRUEBA..prueba/guardian")" -eq 1
verificar "main no se toco" test "$(origin_main)" = "$MERGE"
verificar "lista los dos checks simulados" contiene "$SALIDA" "| Punta a punta (Playwright) | simulado |"
verificar "no llama a gh para comentar" no_contiene "$STUB_LOG" "gh pr comment"
verificar "el simulacro no pide despliegues reales" no_contiene "$STUB_LOG" "gh workflow run"
verificar "pero dice que lo pediria" contiene "$SALIDA" "(simulacro) se pediria un redespliegue"

echo "13. Si no se pueden leer los checks obligatorios, no actua"
nuevo
echo '{"rules":[]}' > "$WORK/docs/ruleset-main.json"
ejecuciones failure success success success success success
correr GUARDIAN_MODO=revertir
verificar "falla sin hacer nada" test "$CODIGO" -ne 0 -a "$CODIGO" -ne 3
verificar "main no cambia" test "$(origin_main)" = "$MERGE"

echo "14. Si la API de GitHub falla, no actua"
nuevo
mkdir -p "$BASE/bin-falla"
# Las comillas simples son a proposito: el $* se expande cuando corre el gh falso, no ahora
# shellcheck disable=SC2016
printf '#!/usr/bin/env bash\necho "gh $*" >> "$STUB_LOG"\nexit 1\n' > "$BASE/bin-falla/gh"
chmod +x "$BASE/bin-falla/gh"
( cd "$WORK" && env PATH="$BASE/bin-falla:$PATH" GITHUB_REPOSITORY=org/repo GH_TOKEN=falso RAMA=main PR_NUMBER=7 PR_COMMITS=1 GUARDIAN_ESPERA=0 HEAD_SHA="$HEX40" MERGE_SHA="$MERGE" GUARDIAN_MODO=revertir bash .github/guardian/guardian-main.sh ) > "$BASE/salida.txt" 2>&1
CODIGO=$?
verificar "termina con error" test "$CODIGO" -ne 0 -a "$CODIGO" -ne 3
verificar "main no cambia" test "$(origin_main)" = "$MERGE"
verificar "reintento 4 veces antes de rendirse" test "$(grep -c 'check-runs' "$STUB_LOG")" -eq 4
verificar "lo dice" contiene "$BASE/salida.txt" "No se pudieron leer los checks del PR tras 4 intentos"

echo "15. El redespliegue no se puede pedir: el revert se mantiene y se avisa"
nuevo
ejecuciones failure success success success success success
correr GUARDIAN_MODO=revertir STUB_FALLAR_DEPLOY=1
verificar "el revert sigue en main" test "$(git -C "$ORIGIN" rev-list --count "$MERGE..main")" -eq 1
verificar "app.txt vuelve a v1" test "$(git -C "$ORIGIN" show main:app.txt)" = "v1"
verificar "pide al equipo que despliegue a mano" contiene "$STUB_LOG" "No se pudo pedir el redespliegue"
verificar "termina en 3" test "$CODIGO" -eq 3


# =====================================================================================
# Escenarios 16 en adelante: ataques, casos limite y pruebas del propio repo
# =====================================================================================

# Ayudas para armar situaciones mas raras que "un PR cambio app.txt"
sin_tocar() { # el origin sigue exactamente como estaba (rama, etiquetas y archivos de la copia)
  test "$(origin_main)" = "$MERGE" && test -z "$(git -C "$ORIGIN" tag -l 'respaldo/*')" && test ! -e "$BASE/pwn" && test ! -e "$WORK/PWNED"
}
revertido() { test "$(git -C "$ORIGIN" rev-list --count "$MERGE..main")" -eq 1; }
mismo_arbol_que_antes() { test "$(git -C "$ORIGIN" rev-parse 'main^{tree}')" = "$(git -C "$ORIGIN" rev-parse "$MERGE^1^{tree}")"; }

echo "16. Nombres de rama hostiles: se rechazan sin tocar nada"
nuevo
ejecuciones failure success success success success success
# Las comillas simples son a proposito: son cargas hostiles que deben llegar al guardian como texto literal
# shellcheck disable=SC2016
for r in "prueba/" "prueba/../main" "prueba//x" "prueba/x." 'prueba/a b' 'prueba/$(touch PWNED)' 'prueba/x;y' 'prueba/x@{1}' 'prueba/x.lock'; do
  correr RAMA="$r" SIMULACRO=1 GUARDIAN_MODO=revertir PR_NUMBER= HEAD_SHA= MERGE_SHA=
  verificar "simulacro rechaza '$r'" test "$CODIGO" -eq 1
done
for r in "refs/heads/main" "./main" "main/.." "MAIN" "main " "-main" "main;ls" "main/x"; do
  correr RAMA="$r" GUARDIAN_MODO=revertir
  verificar "main real rechaza '$r'" test "$CODIGO" -eq 1
done
verificar "main intacta y sin archivos de inyeccion" sin_tocar

echo "17. SHAs y numeros de PR hostiles: se rechazan antes de hacer nada"
nuevo
ejecuciones failure success success success success success
for v in "--output=$BASE/pwn" "HEAD" "main" "abc123" "${HEX40^^}" "${HEX40}a" "${HEX40:1}" "$HEX40 --all"; do
  correr MERGE_SHA="$v" GUARDIAN_MODO=revertir
  verificar "MERGE_SHA '${v:0:30}' rechazado" test "$CODIGO" -eq 1
done
for v in "--paginate" "../x" "abc;ls" "${HEX40^^}" "abc" ""; do
  correr HEAD_SHA="$v" GUARDIAN_MODO=revertir
  verificar "HEAD_SHA '$v' rechazado" test "$CODIGO" -eq 1
done
# shellcheck disable=SC2016
for v in "7;ls" "abc" "-1" "7 8" '$(id)'; do
  correr PR_NUMBER="$v" GUARDIAN_MODO=revertir
  verificar "PR_NUMBER '$v' rechazado" test "$CODIGO" -eq 1
done
verificar "main intacta y sin archivos de inyeccion" sin_tocar

echo "18. Titulos de commit hostiles: el revert los copia como texto, nunca los ejecuta"
ASUNTOS=(
  'CCPIP-9: comillas "dobles" y '"'"'simples'"'"
  "\$(touch $BASE/pwn)"
  "\`touch $BASE/pwn\`"
  '"; touch '"$BASE"'/pwn; echo "'
  'barras \ y formatos %s %d %n %%'
  'acentos: ñandú, camión, ✓ 🚀 日本語'
  '-n --version'
  "$(printf 'x%.0s' $(seq 1 2000))"
)
for asunto in "${ASUNTOS[@]}"; do
  nuevo
  git -C "$WORK" commit --quiet --amend -m "$asunto" && git -C "$WORK" push --quiet --force origin main
  MERGE="$(git -C "$WORK" rev-parse HEAD)"
  ejecuciones failure success success success success success
  correr GUARDIAN_MODO=revertir
  verificar "revierte '${asunto:0:28}'" revertido
  verificar "  el asunto del revert es 'Revert \"<asunto>\"' exacto" test "$(git -C "$ORIGIN" log -1 --format=%s main)" = "Revert \"$asunto\""
  verificar "  no se ejecuto nada" test ! -e "$BASE/pwn"
done

echo "19. Todo tipo de cambios en el PR: el revert deja el arbol EXACTAMENTE como estaba"
nuevo
git -C "$WORK" reset --quiet --hard "$PADRE"
printf 'linea1\r\nlinea2\r\n' > "$WORK/windows.txt"
printf 'a\nb\n' > "$WORK/viejo.txt"
mkdir -p "$WORK/carpeta con espacios"
echo hola > "$WORK/carpeta con espacios/ñandú.txt"
echo '#!/bin/sh' > "$WORK/script.sh"
head -c 200000 /dev/urandom > "$WORK/binario.bin"
git -C "$WORK" add -A && git -C "$WORK" commit --quiet -m "preparacion" && git -C "$WORK" push --quiet --force origin main
PADRE="$(git -C "$WORK" rev-parse HEAD)"
# El PR: modifica, borra, renombra, cambia permisos, agrega vacios y un binario nuevo
printf 'linea1\r\nlinea2 cambiada\r\n' > "$WORK/windows.txt"
git -C "$WORK" rm --quiet viejo.txt
git -C "$WORK" mv "carpeta con espacios/ñandú.txt" "carpeta con espacios/renombrado.txt"
git -C "$WORK" update-index --chmod=+x script.sh 2>/dev/null || true
: > "$WORK/vacio.txt"
head -c 300000 /dev/urandom > "$WORK/otro.bin"
git -C "$WORK" add -A && git -C "$WORK" commit --quiet -m "CCPIP-2: PR con de todo" && git -C "$WORK" push --quiet origin main
MERGE="$(git -C "$WORK" rev-parse HEAD)"
ejecuciones failure success success success success success
correr GUARDIAN_MODO=revertir
verificar "revierte" revertido
verificar "el arbol es identico al de antes del PR" mismo_arbol_que_antes
verificar "el binario original vuelve igual" test "$(git -C "$ORIGIN" rev-parse main:binario.bin)" = "$(git -C "$ORIGIN" rev-parse "$PADRE:binario.bin")"
verificar "el CRLF original se conserva" test "$(git -C "$ORIGIN" show main:windows.txt | tr -cd '\r' | wc -c)" -eq 2

echo "20. Un PR vacio como punta: no se puede revertir y no se rompe nada"
nuevo
git -C "$WORK" commit --quiet --allow-empty -m "PR sin cambios" && git -C "$WORK" push --quiet origin main
MERGE="$(git -C "$WORK" rev-parse HEAD)"
ejecuciones failure success success success success success
correr GUARDIAN_MODO=revertir
verificar "main no cambia" test "$(origin_main)" = "$MERGE"
verificar "explica que no se revirtio" contiene "$STUB_LOG" "No se revirtio"
verificar "termina en 3" test "$CODIGO" -eq 3

echo "21. El commit inicial NUNCA se revierte (borraria todo el repositorio)"
ORIGIN="$BASE/origin.git"; WORK="$BASE/trabajo"; rm -rf "$ORIGIN" "$WORK"
git init --quiet --bare --initial-branch=main "$ORIGIN"
git clone --quiet "$ORIGIN" "$WORK" 2>/dev/null && git -C "$WORK" checkout --quiet -b main
mkdir -p "$WORK/docs" "$WORK/.github" && cp "$RAIZ/docs/ruleset-main.json" "$WORK/docs/" && cp -r "$RAIZ/.github/guardian" "$WORK/.github/"
echo v1 > "$WORK/app.txt" && git -C "$WORK" add -A && git -C "$WORK" commit --quiet -m "inicial" && git -C "$WORK" push --quiet origin main
MERGE="$(git -C "$WORK" rev-parse HEAD)"
export STUB_LOG="$BASE/gh.log" STUB_RUNS="$BASE/ejecuciones.jsonl"; : > "$STUB_LOG"
ejecuciones failure success success success success success
correr GUARDIAN_MODO=revertir
verificar "main no cambia" test "$(origin_main)" = "$MERGE"
verificar "app.txt sigue ahi" test "$(git -C "$ORIGIN" show main:app.txt)" = "v1"
verificar "explica que es el commit inicial" contiene "$STUB_LOG" "commit inicial"

echo "22. Dos guardianes a la vez sobre el mismo merge: un solo revert (5 repeticiones)"
for i in 1 2 3 4 5; do
  nuevo
  ejecuciones failure success success success success success
  rm -rf "$BASE/trabajo2" && git clone --quiet "$ORIGIN" "$BASE/trabajo2" 2>/dev/null
  ( cd "$WORK" && env PATH="$BIN:$PATH" GITHUB_REPOSITORY=org/repo GH_TOKEN=falso RAMA=main PR_NUMBER=7 PR_COMMITS=1 GUARDIAN_ESPERA=0 HEAD_SHA="$HEX40" MERGE_SHA="$MERGE" GUARDIAN_MODO=revertir bash .github/guardian/guardian-main.sh ) > "$BASE/a.txt" 2>&1 &
  P1=$!
  ( cd "$BASE/trabajo2" && env PATH="$BIN:$PATH" GITHUB_REPOSITORY=org/repo GH_TOKEN=falso RAMA=main PR_NUMBER=7 PR_COMMITS=1 GUARDIAN_ESPERA=0 HEAD_SHA="$HEX40" MERGE_SHA="$MERGE" GUARDIAN_MODO=revertir bash .github/guardian/guardian-main.sh ) > "$BASE/b.txt" 2>&1 &
  P2=$!
  wait "$P1"; C1=$?; wait "$P2"; C2=$?
  verificar "repeticion $i: un solo revert en main" revertido
  verificar "repeticion $i: el arbol queda como antes del PR" mismo_arbol_que_antes
  verificar "repeticion $i: ambos terminan en 3 (sin errores raros)" test "$C1" -eq 3 -a "$C2" -eq 3
done

echo "23. Otro PR se fusiona JUSTO antes del push del guardian: no se pisa, no se pierde nada"
nuevo
ejecuciones failure success success success success success
rm -rf "$BASE/otro" "$BASE/bin-carrera" && git clone --quiet "$ORIGIN" "$BASE/otro" 2>/dev/null
mkdir -p "$BASE/bin-carrera" && cp "$BIN/gh" "$BASE/bin-carrera/gh"
cat > "$BASE/bin-carrera/git" <<'ENVOLTORIO'
#!/usr/bin/env bash
# Un git que, justo antes del push de una rama, hace que "otro PR" se fusione en el origin
sub="${1:-}"; [[ "$sub" == -C ]] && sub="${3:-}"
if [[ "$sub" == push && "$*" == *refs/heads/* && "$*" != *refs/tags/* && -n "${CARRERA_DIR:-}" && ! -e "$CARRERA_DIR/.ya" ]]; then
  : > "$CARRERA_DIR/.ya"
  "$REAL_GIT" -C "$CARRERA_DIR" pull --quiet origin main
  echo carrera >> "$CARRERA_DIR/carrera.txt"
  "$REAL_GIT" -C "$CARRERA_DIR" add carrera.txt
  "$REAL_GIT" -C "$CARRERA_DIR" commit --quiet -m "otro PR (carrera)"
  "$REAL_GIT" -C "$CARRERA_DIR" push --quiet origin main
fi
exec "$REAL_GIT" "$@"
ENVOLTORIO
chmod +x "$BASE/bin-carrera/git"
correr PATH="$BASE/bin-carrera:$PATH" CARRERA_DIR="$BASE/otro" GUARDIAN_MODO=revertir
verificar "la rama quedo con el commit del otro PR (no se piso)" test "$(git -C "$ORIGIN" log -1 --format=%s main)" = "otro PR (carrera)"
verificar "y con nada mas: ni revert ni commits perdidos" revertido
verificar "avisa que no se pudo publicar" contiene "$STUB_LOG" "No se pudo publicar el revert"
verificar "termina en 3" test "$CODIGO" -eq 3
verificar "no pide despliegue" no_contiene "$STUB_LOG" "gh workflow run"

echo "24. La etiqueta de respaldo ya existe en el origin apuntando a otro commit: no se pisa"
nuevo
git -C "$ORIGIN" tag "respaldo/main-${MERGE:0:7}" "$PADRE"
ejecuciones failure success success success success success
correr GUARDIAN_MODO=revertir
verificar "el revert se hace igual" revertido
verificar "la etiqueta existente no se movio" test "$(git -C "$ORIGIN" rev-parse "respaldo/main-${MERGE:0:7}^{commit}")" = "$PADRE"
verificar "termina en 3" test "$CODIGO" -eq 3

echo "25. Volver a correr el job sobre un merge ya revertido: no revierte dos veces"
nuevo
ejecuciones failure success success success success success
correr GUARDIAN_MODO=revertir
correr GUARDIAN_MODO=revertir
verificar "sigue habiendo un solo revert" revertido
verificar "la segunda corrida lo explica" contiene "$STUB_LOG" "Hay commits posteriores"

echo "26. Etiquetas: solo forzar-merge exacta (sin distinguir mayusculas) deja pasar"
for et in "no-forzar-merge" "xforzar-merge" "forzar-merge-ya" "forzar merge" "bug,otra" ""; do
  nuevo
  ejecuciones failure success success success success success
  correr GUARDIAN_MODO=revertir ETIQUETAS="$et"
  verificar "etiquetas '$et': el guardian actua" revertido
done
for et in "forzar-merge" "Forzar-Merge" "bug,FORZAR-MERGE,otra"; do
  nuevo
  ejecuciones failure success success success success success
  correr GUARDIAN_MODO=revertir ETIQUETAS="$et"
  verificar "etiquetas '$et': el guardian se aparta" test "$(origin_main)" = "$MERGE"
done

echo "27. Muchos check-runs (paginas, re-ejecuciones y ruido)"
nuevo
: > "$STUB_RUNS"
for i in $(seq 1 120); do printf '{"id":%d,"name":"Otro job %d","status":"completed","conclusion":"success"}\n' "$i" "$i" >> "$STUB_RUNS"; done
for rep in 1 2 3; do for i in 0 1 2 3 4 5; do
  c=failure; [[ "$rep" -eq 3 ]] && c=success
  printf '{"id":%d,"name":"%s","status":"completed","conclusion":"%s"}\n' $((1000 + rep * 10 + i)) "${NOMBRES[$i]}" "$c" >> "$STUB_RUNS"
done; done
correr GUARDIAN_MODO=revertir
verificar "con 138 check-runs y la ultima ejecucion en verde: no actua" test "$CODIGO" -eq 0
printf '{"id":9999,"name":"%s","status":"completed","conclusion":"failure"}\n' "${NOMBRES[3]}" >> "$STUB_RUNS"
correr GUARDIAN_MODO=revertir
verificar "si la ultima de un obligatorio falla: actua" revertido

echo "28. Idioma del sistema: el resultado es el mismo con LC_ALL=C y con UTF-8"
for loc in C C.UTF-8 POSIX; do
  nuevo
  git -C "$WORK" commit --quiet --amend -m "CCPIP-5: ñandú ✓ camión" && git -C "$WORK" push --quiet --force origin main
  MERGE="$(git -C "$WORK" rev-parse HEAD)"
  ejecuciones failure success success success success success
  correr GUARDIAN_MODO=revertir LC_ALL="$loc"
  verificar "LC_ALL=$loc: revierte conservando los acentos" test "$(git -C "$ORIGIN" log -1 --format=%s main)" = 'Revert "CCPIP-5: ñandú ✓ camión"'
done

echo "29. Ensayo en seco (GUARDIAN_SECO=1): lee, calcula y NO escribe nada"
nuevo
ejecuciones failure success success success success success
correr GUARDIAN_MODO=revertir GUARDIAN_SECO=1
verificar "termina en 3" test "$CODIGO" -eq 3
verificar "main no cambia" test "$(origin_main)" = "$MERGE"
verificar "no crea etiquetas" test -z "$(git -C "$ORIGIN" tag -l)"
verificar "no comenta" no_contiene "$STUB_LOG" "gh pr comment"
verificar "no abre issues" no_contiene "$STUB_LOG" "gh issue create"
verificar "no crea la etiqueta forzar-merge" no_contiene "$STUB_LOG" "gh label create"
verificar "no pide despliegues" no_contiene "$STUB_LOG" "gh workflow run"
verificar "describe lo que haria" contiene "$SALIDA" "(en seco)"
verificar "el revert se probo en local y aplica limpio" contiene "$SALIDA" "se revertiria"

# ---------------------------------------------------------------------------------------
echo "30. El workflow: seguridad estatica (sobre el archivo real del repo)"
WF="$RAIZ/.github/workflows/guardian-main.yml"
verificar "permisos globales vacios" grep -qx 'permissions: {}' "$WF"
verificar "ninguna accion sin fijar por SHA de 40 caracteres" python3_o_python - "$WF" <<'PY'
import re, sys
usos = re.findall(r'^\s*-?\s*uses:\s*(\S+)', open(sys.argv[1], encoding='utf-8').read(), re.M)
assert usos, "no hay ninguna accion"
assert all(re.fullmatch(r'[\w.-]+/[\w./-]+@[0-9a-f]{40}', u) for u in usos), usos
PY
verificar "ningun 'run' interpola expresiones de GitHub (inyeccion de scripts)" python3_o_python - "$WF" <<'PY'
import re, sys
lineas = open(sys.argv[1], encoding='utf-8').read().split('\n')
for i, linea in enumerate(lineas):
    m = re.match(r'^(\s*)(?:-\s+)?run:\s*(.*)$', linea)
    if not m:
        continue
    sangria, resto = len(m.group(1)), m.group(2)
    bloque = [resto]
    if resto in ('|', '>', '|-', '>-'):
        for sig in lineas[i + 1:]:
            if sig.strip() and len(sig) - len(sig.lstrip()) <= sangria:
                break
            bloque.append(sig)
    assert '${{' not in '\n'.join(bloque), f"linea {i + 1}: {bloque}"
PY
verificar "no usa titulo, cuerpo ni rama del PR" no_contiene_regex "$WF" 'pull_request\.(title|body|head\.ref|head\.label|head\.repo)'
verificar "el checkout no pide la rama del PR (sin 'ref:' ni 'repository:')" no_contiene_regex "$WF" '^\s+(ref|repository):'
verificar "se dispara solo al cerrar PRs hacia main" python3_o_python - "$WF" <<'PY'
import re, sys
t = open(sys.argv[1], encoding='utf-8').read()
assert re.search(r'pull_request_target:\s*\n\s+types:\s*\[closed\]\s*\n\s+branches:\s*\[main\]', t)
assert 'pull_request:' not in re.sub(r'pull_request_target:', '', t)
PY
verificar "solo actua si el PR se fusiono" contiene "$WF" "github.event.pull_request.merged == true"
verificar "el simulacro exige rama prueba/*" contiene "$WF" "startsWith(inputs.rama, 'prueba/')"
verificar "una ejecucion a la vez y sin cancelar a medias" python3_o_python - "$WF" <<'PY'
import re, sys
t = open(sys.argv[1], encoding='utf-8').read()
assert re.search(r'concurrency:\s*\n\s+group:\s*guardian-main\s*\n\s+cancel-in-progress:\s*false', t)
PY
verificar "los permisos del job son exactamente los necesarios" python3_o_python - "$WF" <<'PY'
import re, sys
t = open(sys.argv[1], encoding='utf-8').read()
bloque = re.search(r'^    permissions:\n((?:      \S.*\n)+)', t, re.M).group(1)
permisos = dict(re.findall(r'^      ([\w-]+): (\w+)', bloque, re.M))
assert permisos == {'contents': 'write', 'pull-requests': 'write', 'issues': 'write', 'checks': 'read', 'actions': 'write'}, permisos
PY
verificar "el simulacro propone una rama prueba/* por defecto" contiene "$WF" "default: prueba/guardian"
verificar "el workflow le pasa al script todo lo que este necesita" python3_o_python - "$WF" "$RAIZ/.github/guardian/guardian-main.sh" <<'PY'
import re, sys
wf = open(sys.argv[1], encoding='utf-8').read()
sh = open(sys.argv[2], encoding='utf-8').read()
env = set(re.findall(r'^          ([A-Z_]+): \$\{\{', wf, re.M))
usadas = {'GH_TOKEN', 'GUARDIAN_MODO', 'RAMA', 'SIMULACRO', 'CHECKS_FALLIDOS', 'PR_NUMBER', 'PR_COMMITS', 'HEAD_SHA', 'MERGE_SHA', 'ETIQUETAS'}
assert usadas <= env, usadas - env
assert all(v in sh for v in usadas), [v for v in usadas if v not in sh]
PY

echo "31. Coherencia con el resto del repo"
verificar "el ruleset exige exactamente los 6 checks esperados" test "$("$PY_TEST" "$RAIZ/.github/guardian/evaluar_checks.py" "$RAIZ/docs/ruleset-main.json" "$VACIO" | wc -l)" -eq 6
while IFS= read -r nombre; do
  verificar "ci.yml define el job '$nombre' que exige el ruleset" contiene "$RAIZ/.github/workflows/ci.yml" "name: $nombre"
done < <("$PY_TEST" "$RAIZ/.github/guardian/evaluar_checks.py" "$RAIZ/docs/ruleset-main.json" "$VACIO" | cut -f1 | tr -d '\r')
verificar "deploy.yml admite workflow_dispatch (el guardian lo usa para redesplegar)" contiene "$RAIZ/.github/workflows/deploy.yml" "workflow_dispatch:"

echo "32. Windows: con core.autocrlf=true los scripts conservan LF gracias a .gitattributes"
for caso in con sin; do
  rm -rf "$BASE/crlf-origen" "$BASE/crlf-clon"
  mkdir -p "$BASE/crlf-origen/.github/guardian"
  cp "$RAIZ/.github/guardian/guardian-main.sh" "$BASE/crlf-origen/.github/guardian/"
  [[ "$caso" == con ]] && cp "$RAIZ/.gitattributes" "$BASE/crlf-origen/"
  git -C "$BASE/crlf-origen" init --quiet --initial-branch=main
  git -C "$BASE/crlf-origen" add -A && git -C "$BASE/crlf-origen" commit --quiet -m "x"
  git -c core.autocrlf=true clone --quiet "$BASE/crlf-origen" "$BASE/crlf-clon" 2>/dev/null
  CR="$(tr -cd '\r' < "$BASE/crlf-clon/.github/guardian/guardian-main.sh" | wc -c)"
  if [[ "$caso" == con ]]; then verificar "con .gitattributes: 0 CR en el clon" test "$CR" -eq 0
  else verificar "control, sin .gitattributes: el clon SI trae CR (la prueba detecta el problema)" test "$CR" -gt 0; fi
done

echo "33. Fallas transitorias de la API de GitHub: se reintenta y, si se recupera, el guardian actua normal"
for fallas in 1 2 3; do
  nuevo
  export STUB_CUENTA="$BASE/cuenta.txt"; rm -f "$STUB_CUENTA"
  ejecuciones failure success success success success success
  correr GUARDIAN_MODO=revertir STUB_FALLAS="$fallas"
  verificar "$fallas fallas seguidas y luego responde: revierte igual" revertido
  verificar "  hizo exactamente $((fallas + 1)) consultas" test "$(grep -c 'check-runs' "$STUB_LOG")" -eq $((fallas + 1))
  verificar "  deja constancia de los reintentos" contiene "$SALIDA" "intento $fallas de 4"
done
nuevo
export STUB_CUENTA="$BASE/cuenta.txt"; rm -f "$STUB_CUENTA"
correr GUARDIAN_MODO=revertir STUB_FALLAS=4
verificar "4 fallas seguidas: se rinde sin actuar" test "$(origin_main)" = "$MERGE"
verificar "  y termina con error (el job queda en rojo)" test "$CODIGO" -eq 1
unset STUB_CUENTA
correr GUARDIAN_MODO=revertir GUARDIAN_ESPERA=abc
verificar "una espera invalida no rompe nada (usa el valor por defecto)" test "$CODIGO" -eq 0

echo "34. PR de varios commits con un solo padre: squash si, rebase no (revertir el ultimo lo dejaria a medias)"
rebase_de_3() { # $1 = asunto del ultimo commit; deja main con 3 commits seguidos encima del inicial
  nuevo
  git -C "$WORK" reset --quiet --hard "$PADRE"
  for i in 1 2 3; do
    echo "cambio $i" > "$WORK/archivo$i.txt"
    git -C "$WORK" add -A
    git -C "$WORK" commit --quiet -m "commit $i del PR"
  done
  [[ -n "$1" ]] && git -C "$WORK" commit --quiet --amend -m "$1"
  git -C "$WORK" push --quiet --force origin main
  MERGE="$(git -C "$WORK" rev-parse HEAD)"
  ejecuciones failure success success success success success
}
rebase_de_3 ""
correr GUARDIAN_MODO=revertir PR_COMMITS=3
verificar "rebase de 3 commits: NO revierte (quedaria a medias)" test "$(origin_main)" = "$MERGE"
verificar "  y lo explica" contiene "$STUB_LOG" "rebase and merge"
verificar "  termina en 3" test "$CODIGO" -eq 3
rebase_de_3 ""
correr GUARDIAN_MODO=revertir PR_COMMITS=
verificar "numero de commits desconocido y sin (#N): NO revierte" test "$(origin_main)" = "$MERGE"
rebase_de_3 "Titulo de un squash real (#7)"
correr GUARDIAN_MODO=revertir PR_COMMITS=3
verificar "varios commits pero asunto '... (#7)' (squash): revierte" revertido
verificar "  y el arbol queda como antes" mismo_arbol_que_antes
rebase_de_3 "Titulo de un squash de otro PR (#8)"
correr GUARDIAN_MODO=revertir PR_COMMITS=3
verificar "el '(#N)' debe ser del MISMO PR (#8 no vale para el PR 7): NO revierte" test "$(origin_main)" = "$MERGE"
nuevo
ejecuciones failure success success success success success
correr GUARDIAN_MODO=revertir PR_COMMITS=1
verificar "PR de un solo commit (aunque no diga '(#N)'): revierte" revertido
nuevo
ejecuciones failure success success success success success
correr GUARDIAN_MODO=revertir PR_COMMITS=abc
verificar "PR_COMMITS no numerico: se rechaza" test "$CODIGO" -eq 1
nuevo
git -C "$WORK" checkout --quiet -b rama-pr "$PADRE"
for i in 1 2 3 4 5; do echo "x$i" > "$WORK/m$i.txt"; git -C "$WORK" add -A; git -C "$WORK" commit --quiet -m "trabajo $i"; done
git -C "$WORK" checkout --quiet main && git -C "$WORK" reset --quiet --hard "$PADRE"
git -C "$WORK" merge --quiet --no-ff rama-pr -m "Merge pull request #7 from org/rama" && git -C "$WORK" push --quiet --force origin main
MERGE="$(git -C "$WORK" rev-parse HEAD)"
ejecuciones failure success success success success success
correr GUARDIAN_MODO=revertir PR_COMMITS=5
verificar "merge commit de un PR de 5 commits: revierte (dos padres)" revertido
verificar "  y deja el contenido de antes" mismo_arbol_que_antes

echo
echo "Resultado: $OK ok, $FALLO con fallo"
[[ "$FALLO" -eq 0 ]]
