"""Arma el modelo propio de validacion de imagenes (CCPIP-144) en el formato que lee el backend.

El modelo tiene dos partes:
  1. Extractor de caracteristicas: MobileNetV2 preentrenado (ONNX Model Zoo, licencia Apache 2.0).
     Convierte una foto en 1280 numeros que describen lo que se ve.
  2. Cabeza propia: decide a que linea de CCPIP corresponde la foto.
     - Version 0 (este script, sin --cabeza): reutiliza las 1000 clases de ImageNet y agrupa en cada
       linea las clases que la representan (cafe: espresso, taza de cafe...; textil: poncho, lana...).
       Lo demas queda como "OTRO". El mapeo esta en LINEAS y se puede revisar.
     - Version entrenada (--cabeza cabeza.npz, de entrenar.py): una fila por linea, aprendida con
       fotos reales de productos de la Camara.

Uso:
    pip install onnx numpy
    python exportar.py                         # descarga MobileNetV2 y exporta la version 0
    python exportar.py --cabeza cabeza.npz     # exporta con la cabeza entrenada
Salida: backend/src/main/resources/ia/modelo-imagenes.bin (pesos en float16).

Formato: "CCPIPIA1", longitud (int32 big endian) y JSON con el grafo y la cabeza, y despues los
tensores en float16 big endian, uno tras otro.
"""
import argparse
import hashlib
import json
import os
import struct
import urllib.request

import numpy as np
import onnx
from onnx import numpy_helper

URL = ("https://media.githubusercontent.com/media/onnx/models/main/validated/vision/classification/"
       "mobilenet/model/mobilenetv2-12.onnx")
SHA256 = "c0c3f76d93fa3fd6580652a45618618a220fced18babf65774ed169de0432ad5"
AQUI = os.path.dirname(os.path.abspath(__file__))
SALIDA = os.path.join(AQUI, "..", "..", "backend", "src", "main", "resources", "ia", "modelo-imagenes.bin")

# Version 0: clases de ImageNet que cuentan como evidencia de cada linea (nombres exactos de
# imagenet_clases.txt). Una clase pertenece a una sola linea.
LINEAS = {
    "CAFE": ["espresso", "coffee mug", "coffeepot", "espresso maker"],
    "CACAO": ["chocolate sauce"],
    "SUPERFRUTOS": ["banana", "pineapple", "orange", "lemon", "strawberry", "pomegranate", "fig", "jackfruit",
                    "custard apple", "Granny Smith", "hip", "buckeye", "acorn"],
    "ACEITES": ["wine bottle", "water bottle", "pop bottle", "beer bottle", "pill bottle", "perfume", "lotion",
                "whiskey jug", "water jug"],
    "INFUSIONES": ["teapot", "cup"],
    "ARTESANIA": ["vase", "pot", "mask", "necklace", "hamper", "maraca", "drum", "totem pole", "piggy bank",
                  "pitcher", "pedestal", "candle"],
    "TEXTIL": ["poncho", "stole", "wool", "quilt", "cardigan", "sweatshirt", "jersey", "apron", "mitten", "sock",
               "Christmas stocking", "velvet", "bath towel", "handkerchief", "abaya", "kimono", "sarong", "cloak",
               "pillow", "sleeping bag", "theater curtain", "shower curtain", "bib", "pajama", "prayer rug"],
}


def descargar(destino):
    if not os.path.exists(destino):
        print("Descargando MobileNetV2...")
        urllib.request.urlretrieve(URL, destino)
    with open(destino, "rb") as f:
        if hashlib.sha256(f.read()).hexdigest() != SHA256:
            raise SystemExit("El archivo descargado no coincide con el SHA-256 esperado")
    return destino


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--onnx", default=os.path.join(AQUI, "mobilenetv2-12.onnx"))
    p.add_argument("--cabeza", help="cabeza.npz de entrenar.py; sin ella se exporta la version 0")
    p.add_argument("--salida", default=SALIDA)
    a = p.parse_args()

    modelo = onnx.load(descargar(a.onnx))
    pesos = {t.name: numpy_helper.to_array(t).astype(np.float32) for t in modelo.graph.initializer}
    tensores, datos = [], []

    def tensor(arr):
        arr = np.ascontiguousarray(arr, dtype=np.float32)
        tensores.append({"forma": list(arr.shape)})
        datos.append(arr.astype(">f2").tobytes())
        return len(tensores) - 1

    nodos = []
    gemm = None
    for n in modelo.graph.node:
        at = {x.name: onnx.helper.get_attribute_value(x) for x in n.attribute}
        if n.op_type == "Conv":
            assert at.get("dilations", [1, 1]) == [1, 1]
            k = at["kernel_shape"]
            assert k[0] == k[1] and len(set(at["strides"])) == 1 and len(set(at["pads"])) == 1
            nodos.append({"op": "Conv", "entrada": n.input[0], "salida": n.output[0],
                          "w": tensor(pesos[n.input[1]]), "b": tensor(pesos[n.input[2]]),
                          "paso": at["strides"][0], "relleno": at["pads"][0], "grupos": at.get("group", 1)})
        elif n.op_type == "Clip":
            nodos.append({"op": "Clip", "entrada": n.input[0], "salida": n.output[0],
                          "min": float(pesos[n.input[1]].reshape(-1)[0]), "max": float(pesos[n.input[2]].reshape(-1)[0])})
        elif n.op_type == "Add":
            nodos.append({"op": "Add", "entrada": n.input[0], "entrada2": n.input[1], "salida": n.output[0]})
        elif n.op_type == "GlobalAveragePool":
            nodos.append({"op": "GlobalAveragePool", "entrada": n.input[0], "salida": n.output[0]})
        elif n.op_type == "Gemm":
            gemm = n
        elif n.op_type in ("Shape", "Constant", "Gather", "Unsqueeze", "Concat", "Reshape"):
            continue  # solo aplanan el vector final; el backend ya lo trata como vector
        else:
            raise SystemExit("Operacion no soportada: " + n.op_type)
    if gemm is None:
        raise SystemExit("El modelo no tiene la capa final Gemm")
    caracteristicas = nodos[-1]["salida"]

    with open(os.path.join(AQUI, "imagenet_clases.txt"), encoding="utf-8") as f:
        etiquetas = [l.strip() for l in f]
    if a.cabeza:
        with np.load(a.cabeza) as c:
            w, b, lineas = c["w"], c["b"], [str(x) for x in c["lineas"]]
        cabeza = {"version": "entrenada", "w": tensor(w), "b": tensor(b), "lineas": lineas,
                  "etiquetas": lineas}
    else:
        clase_linea = {}
        for linea, nombres in LINEAS.items():
            for nombre in nombres:
                i = etiquetas.index(nombre)
                assert i not in clase_linea, nombre
                clase_linea[i] = linea
        cabeza = {"version": "0-imagenet", "w": tensor(pesos[gemm.input[1]]), "b": tensor(pesos[gemm.input[2]]),
                  "lineas": [clase_linea.get(i, "OTRO") for i in range(len(etiquetas))],
                  "etiquetas": etiquetas}

    cabecera = {
        "base": "MobileNetV2 (ONNX Model Zoo, Apache 2.0)",
        "entrada": {"tamano": 224, "media": [0.485, 0.456, 0.406], "desviacion": [0.229, 0.224, 0.225]},
        "nodos": nodos, "caracteristicas": caracteristicas, "tensores": tensores, "cabeza": cabeza,
    }
    js = json.dumps(cabecera, ensure_ascii=False).encode("utf-8")
    os.makedirs(os.path.dirname(a.salida), exist_ok=True)
    with open(a.salida, "wb") as f:
        f.write(b"CCPIPIA1")
        f.write(struct.pack(">i", len(js)))
        f.write(js)
        for d in datos:
            f.write(d)
    print(f"{a.salida}: {os.path.getsize(a.salida) / 1e6:.1f} MB, {len(nodos)} capas, cabeza {cabeza['version']}")


if __name__ == "__main__":
    main()
