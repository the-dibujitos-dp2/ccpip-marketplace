"""CCPIP-22: pruebas de evaluar_checks.py, la pieza que decide si un PR cumplia los checks obligatorios.

    python3 -m unittest discover -s .github/guardian -v

Ademas de los casos sueltos, recorre TODAS las combinaciones de estado y conclusion que GitHub puede
informar para un check: solo "completado con success" cumple; cualquier otra cosa es un incumplimiento.
"""
import io
import json
import os
import subprocess
import sys
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout

AQUI = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, AQUI)
import evaluar_checks as ev  # noqa: E402

SCRIPT = os.path.join(AQUI, "evaluar_checks.py")
RULESET_REAL = os.path.join(AQUI, "..", "..", "docs", "ruleset-main.json")


def run(id_, nombre, status="completed", conclusion="success"):
    return {"id": id_, "name": nombre, "status": status, "conclusion": conclusion}


class Temporales(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.dir.cleanup)

    def archivo(self, nombre, contenido):
        ruta = os.path.join(self.dir.name, nombre)
        with open(ruta, "w", encoding="utf-8", newline="\n") as f:
            f.write(contenido)
        return ruta

    def ruleset(self, nombres):
        reglas = [{"type": "deletion"}, {
            "type": "required_status_checks",
            "parameters": {"required_status_checks": [{"context": n, "integration_id": 1} for n in nombres]},
        }]
        return self.archivo("ruleset.json", json.dumps({"rules": reglas}))

    def ejecuciones(self, runs):
        return self.archivo("runs.jsonl", "\n".join(json.dumps(r) for r in runs) + "\n")

    def evaluar(self, ruleset, runs):
        """Corre main() como lo hace el guardian y devuelve (codigo, {check: motivo}, stderr)."""
        salida, error = io.StringIO(), io.StringIO()
        with redirect_stdout(salida), redirect_stderr(error):
            codigo = ev.main(["x", ruleset, runs])
        incumplidos = dict(linea.split("\t") for linea in salida.getvalue().splitlines())
        return codigo, incumplidos, error.getvalue()


class IncumplimientoPorEstado(unittest.TestCase):
    """Todas las combinaciones que GitHub puede informar para un check."""

    CONCLUSIONES = ["success", "failure", "neutral", "cancelled", "skipped", "timed_out", "action_required", "stale", None, "algo_nuevo"]
    ESTADOS = ["queued", "in_progress", "pending", "waiting", "requested", "completed"]

    def test_solo_completado_con_success_cumple(self):
        for status in self.ESTADOS:
            for conclusion in self.CONCLUSIONES:
                with self.subTest(status=status, conclusion=conclusion):
                    motivo = ev.incumplimiento("A", [run(1, "A", status, conclusion)])
                    if status == "completed" and conclusion == "success":
                        self.assertIsNone(motivo)
                    else:
                        self.assertIsNotNone(motivo)

    def test_los_motivos_son_los_esperados(self):
        self.assertEqual(ev.incumplimiento("A", []), "no existe")
        self.assertEqual(ev.incumplimiento("A", [run(1, "A", "in_progress", None)]), "pendiente")
        self.assertEqual(ev.incumplimiento("A", [run(1, "A", "queued", None)]), "pendiente")
        self.assertEqual(ev.incumplimiento("A", [run(1, "A", conclusion="failure")]), "fallo (failure)")
        self.assertEqual(ev.incumplimiento("A", [run(1, "A", conclusion="skipped")]), "fallo (skipped)")
        self.assertEqual(ev.incumplimiento("A", [run(1, "A", conclusion=None)]), "fallo (None)")

    def test_un_check_que_se_salto_no_cuenta_como_verificado(self):
        # Sonar queda "skipped" si falla el backend: el PR NO esta verificado
        self.assertIsNotNone(ev.incumplimiento("A", [run(1, "A", conclusion="skipped")]))


class UltimaEjecucion(unittest.TestCase):
    def test_vale_la_de_id_mayor_sin_importar_el_orden_de_llegada(self):
        fallo, ok = run(5, "A", conclusion="failure"), run(9, "A")
        self.assertIsNone(ev.incumplimiento("A", [fallo, ok]))
        self.assertIsNone(ev.incumplimiento("A", [ok, fallo]))

    def test_un_fallo_posterior_a_un_exito_incumple(self):
        self.assertEqual(ev.incumplimiento("A", [run(5, "A"), run(9, "A", conclusion="failure")]), "fallo (failure)")

    def test_una_reejecucion_en_curso_incumple_aunque_haya_un_exito_viejo(self):
        self.assertEqual(ev.incumplimiento("A", [run(5, "A"), run(9, "A", "in_progress", None)]), "pendiente")

    def test_ejecuciones_sin_id_no_rompen(self):
        self.assertIsNone(ev.incumplimiento("A", [{"name": "A", "status": "completed", "conclusion": "success"}]))

    def test_no_se_mezclan_nombres_parecidos(self):
        runs = [run(1, "Backend (tests)"), run(2, "Backend (tests) "), run(3, "backend (tests)"), run(4, "Backend")]
        self.assertIsNone(ev.incumplimiento("Backend (tests)", runs))
        self.assertEqual(ev.incumplimiento("Backend (Tests)", runs), "no existe")
        self.assertEqual(ev.incumplimiento("Backend (tests) ", [run(1, "Backend (tests)")]), "no existe")


class Archivos(Temporales):
    def test_todo_en_verde_no_imprime_nada(self):
        regla = self.ruleset(["A", "B"])
        codigo, incumplidos, _ = self.evaluar(regla, self.ejecuciones([run(1, "A"), run(2, "B")]))
        self.assertEqual((codigo, incumplidos), (0, {}))

    def test_lista_solo_los_incumplidos_en_el_orden_del_ruleset(self):
        regla = self.ruleset(["C", "A", "B", "D"])
        runs = self.ejecuciones([run(1, "A"), run(2, "B", conclusion="failure"), run(3, "C", "in_progress", None)])
        codigo, incumplidos, _ = self.evaluar(regla, runs)
        self.assertEqual(codigo, 0)
        self.assertEqual(list(incumplidos), ["C", "B", "D"])
        self.assertEqual(incumplidos, {"C": "pendiente", "B": "fallo (failure)", "D": "no existe"})

    def test_checks_ajenos_al_ruleset_se_ignoran(self):
        regla = self.ruleset(["A"])
        runs = self.ejecuciones([run(1, "A"), run(2, "SonarCloud Code Analysis", conclusion="failure"), run(3, "Otro", "queued", None)])
        self.assertEqual(self.evaluar(regla, runs)[1], {})

    def test_nombres_con_tildes_comas_y_parentesis(self):
        nombres = ["Dependencias, Dockerfiles y Terraform (Trivy)", "Punta a punta (Playwright)", "Revisión ñandú ✓"]
        regla = self.ruleset(nombres)
        self.assertEqual(self.evaluar(regla, self.ejecuciones([run(i, n) for i, n in enumerate(nombres)]))[1], {})
        self.assertEqual(set(self.evaluar(regla, self.ejecuciones([]))[1]), set(nombres))

    def test_lineas_en_blanco_y_extremos_no_molestan(self):
        regla = self.ruleset(["A"])
        runs = self.archivo("r.jsonl", "\n\n" + json.dumps(run(1, "A")) + "\n\n\n")
        self.assertEqual(self.evaluar(regla, runs)[1], {})

    def test_sin_ejecuciones_todo_incumple(self):
        self.assertEqual(self.evaluar(self.ruleset(["A", "B"]), self.archivo("v.jsonl", ""))[1], {"A": "no existe", "B": "no existe"})

    def test_duplicados_en_el_ruleset_se_listan_tal_cual(self):
        self.assertEqual(self.evaluar(self.ruleset(["A", "A"]), self.ejecuciones([run(1, "A", conclusion="failure")]))[0], 0)


class Fallos(Temporales):
    """Ante cualquier duda debe terminar en 2 y NO imprimir nada: el guardian no actua."""

    def assertAborta(self, ruleset, runs):
        codigo, incumplidos, error = self.evaluar(ruleset, runs)
        self.assertEqual(codigo, 2)
        self.assertEqual(incumplidos, {})
        self.assertTrue(error)

    def test_ruleset_sin_checks_obligatorios(self):
        self.assertAborta(self.archivo("a.json", '{"rules": []}'), self.ejecuciones([]))
        self.assertAborta(self.archivo("b.json", "{}"), self.ejecuciones([]))
        self.assertAborta(self.ruleset([]), self.ejecuciones([]))

    def test_ruleset_roto_o_inexistente(self):
        self.assertAborta(self.archivo("c.json", "{no es json"), self.ejecuciones([]))
        self.assertAborta(self.archivo("d.json", ""), self.ejecuciones([]))
        self.assertAborta(os.path.join(self.dir.name, "no-existe.json"), self.ejecuciones([]))

    def test_ejecuciones_rotas_o_inexistentes(self):
        regla = self.ruleset(["A"])
        self.assertAborta(regla, self.archivo("e.jsonl", "esto no es json\n"))
        self.assertAborta(regla, self.archivo("f.jsonl", '{"id": 1, "name": "A"\n'))
        self.assertAborta(regla, os.path.join(self.dir.name, "no-existe.jsonl"))

    def test_argumentos_incorrectos(self):
        for argv in (["x"], ["x", "a"], ["x", "a", "b", "c"]):
            with redirect_stderr(io.StringIO()):
                self.assertEqual(ev.main(argv), 2)


class ProcesoReal(Temporales):
    """Corre el script como lo hace el guardian: salida estandar limpia y codigo de salida."""

    def correr(self, ruleset, runs):
        return subprocess.run([sys.executable, SCRIPT, ruleset, runs], capture_output=True, check=False)

    def test_salida_con_saltos_de_linea_lf_y_utf8(self):
        regla = self.ruleset(["Revisión ñandú", "B"])
        proceso = self.correr(regla, self.ejecuciones([run(1, "B", conclusion="failure")]))
        self.assertEqual(proceso.returncode, 0)
        self.assertNotIn(b"\r", proceso.stdout)
        self.assertEqual(proceso.stdout.decode("utf-8"), "Revisión ñandú\tno existe\nB\tfallo (failure)\n")

    def test_codigo_2_y_salida_vacia_si_no_puede_leer(self):
        proceso = self.correr(self.archivo("roto.json", "{"), self.ejecuciones([]))
        self.assertEqual((proceso.returncode, proceso.stdout), (2, b""))


class RulesetReal(unittest.TestCase):
    """El ruleset del repo, tal cual: los 6 checks que exige la proteccion de rama."""

    ESPERADOS = [
        "Backend (tests)",
        "SonarQube (Quality Gate)",
        "Frontend (tests + build + audit)",
        "Punta a punta (Playwright)",
        "Secretos filtrados (TruffleHog)",
        "Dependencias, Dockerfiles y Terraform (Trivy)",
    ]

    def test_exige_exactamente_estos_seis(self):
        self.assertEqual(sorted(ev.obligatorios(RULESET_REAL)), sorted(self.ESPERADOS))

    def test_sin_ninguna_ejecucion_incumplen_los_seis(self):
        self.assertEqual(len([n for n in ev.obligatorios(RULESET_REAL) if ev.incumplimiento(n, [])]), 6)

    def test_con_los_seis_en_verde_cumple_el_pr(self):
        runs = [run(i, n) for i, n in enumerate(self.ESPERADOS)]
        self.assertEqual([n for n in ev.obligatorios(RULESET_REAL) if ev.incumplimiento(n, runs)], [])


if __name__ == "__main__":
    unittest.main()
