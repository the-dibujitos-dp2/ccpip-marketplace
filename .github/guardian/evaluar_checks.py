#!/usr/bin/env python3
"""CCPIP-22: que checks obligatorios no cumplio un PR.

Uso: evaluar_checks.py RULESET CHECK_RUNS

- RULESET es docs/ruleset-main.json, la unica fuente de verdad de los checks obligatorios.
- CHECK_RUNS tiene un check-run de GitHub por linea, en JSON ({id, name, status, conclusion}).

Imprime una linea "nombre<TAB>estado" por cada check incumplido y termina con 0 aunque haya
incumplidos. Un check cumple si su ultima ejecucion (la de id mayor, por si se re-ejecuto)
termino en success; si fallo, sigue corriendo o ni existe, esta incumplido. Termina con 2 y sin
imprimir nada si no puede leer los datos o no hay checks obligatorios: ante la duda, el guardian
no debe actuar.
"""
import json
import sys


def obligatorios(ruta):
    with open(ruta, encoding="utf-8") as f:
        regla = json.load(f)
    return [
        check["context"]
        for r in regla.get("rules", [])
        if r.get("type") == "required_status_checks"
        for check in r.get("parameters", {}).get("required_status_checks", [])
    ]


def leer_ejecuciones(ruta):
    with open(ruta, encoding="utf-8") as f:
        return [json.loads(linea) for linea in f if linea.strip()]


def incumplimiento(nombre, ejecuciones):
    """None si el check cumple; si no, por que no."""
    propias = [e for e in ejecuciones if e.get("name") == nombre]
    if not propias:
        return "no existe"
    ultima = max(propias, key=lambda e: e.get("id", 0))
    if ultima.get("status") != "completed":
        return "pendiente"
    if ultima.get("conclusion") == "success":
        return None
    return f"fallo ({ultima.get('conclusion')})"


def main(argv):
    # Misma salida en Windows y en Linux: UTF-8 y saltos de linea "\n"
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", newline="\n")
    if len(argv) != 3:
        print(__doc__, file=sys.stderr)
        return 2
    try:
        nombres = obligatorios(argv[1])
        ejecuciones = leer_ejecuciones(argv[2])
    except (OSError, ValueError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 2
    if not nombres:
        print(f"error: {argv[1]} no define checks obligatorios", file=sys.stderr)
        return 2
    for nombre in nombres:
        motivo = incumplimiento(nombre, ejecuciones)
        if motivo:
            print(f"{nombre}\t{motivo}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
