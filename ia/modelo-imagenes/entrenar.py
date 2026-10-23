"""Entrena la cabeza propia del modelo de imagenes (CCPIP-144) con fotos reales de productos.

Pon las fotos en una carpeta por linea, con el codigo de la linea de CCPIP:
    datos/CACAO/*.jpg   datos/CAFE/*.jpg   datos/SUPERFRUTOS/*.jpg   datos/ACEITES/*.jpg
    datos/INFUSIONES/*.jpg   datos/ARTESANIA/*.jpg   datos/TEXTIL/*.jpg
Conviene tener al menos 30 fotos por linea, variadas (empaque, producto suelto, distintas luces).

Uso:
    pip install onnx onnxruntime numpy pillow
    python entrenar.py                       # entrena y guarda cabeza.npz, con el 20 % reservado para medir
    python exportar.py --cabeza cabeza.npz   # empaqueta el modelo para el backend

El extractor MobileNetV2 queda fijo; solo se aprende una regresion logistica de 1280 caracteristicas
a las lineas. Asi bastan pocas fotos y el entrenamiento toma segundos en una laptop.
"""
import argparse
import glob
import os

import numpy as np
import onnx
import onnxruntime as ort
from PIL import Image

from exportar import AQUI, descargar

MEDIA = np.array([0.485, 0.456, 0.406], dtype=np.float32)
DESVIACION = np.array([0.229, 0.224, 0.225], dtype=np.float32)


def extractor(ruta_onnx):
    """Sesion de ONNX Runtime que devuelve el vector de 1280 caracteristicas (salida del promedio global)."""
    modelo = onnx.load(ruta_onnx)
    pool = next(n for n in modelo.graph.node if n.op_type == "GlobalAveragePool")
    modelo.graph.output.append(onnx.helper.make_tensor_value_info(pool.output[0], onnx.TensorProto.FLOAT, None))
    sesion = ort.InferenceSession(modelo.SerializeToString())
    return lambda x: sesion.run([pool.output[0]], {"input": x})[0].reshape(len(x), -1)


def preparar(ruta):
    """Igual que el backend: lado corto a 256, recorte central de 224 y normalizacion de ImageNet."""
    img = Image.open(ruta).convert("RGB")
    escala = 256 / min(img.size)
    img = img.resize((max(224, round(img.width * escala)), max(224, round(img.height * escala))), Image.BILINEAR)
    izq, arr = (img.width - 224) // 2, (img.height - 224) // 2
    x = np.asarray(img.crop((izq, arr, izq + 224, arr + 224)), dtype=np.float32) / 255
    return ((x - MEDIA) / DESVIACION).transpose(2, 0, 1)


def entrenar(x, y, clases, epocas=400, tasa=0.5, l2=1e-3):
    """Regresion logistica multiclase por descenso de gradiente, con caracteristicas estandarizadas."""
    media, desv = x.mean(0), x.std(0) + 1e-6
    z = (x - media) / desv
    w = np.zeros((clases, x.shape[1]), dtype=np.float64)
    b = np.zeros(clases)
    uno = np.eye(clases)[y]
    for _ in range(epocas):
        logit = z @ w.T + b
        p = np.exp(logit - logit.max(1, keepdims=True))
        p /= p.sum(1, keepdims=True)
        g = (p - uno) / len(z)
        w -= tasa * (g.T @ z + l2 * w)
        b -= tasa * g.sum(0)
    # Se pliega la estandarizacion en los pesos: el backend usa las caracteristicas tal cual
    return (w / desv).astype(np.float32), (b - (w / desv) @ media).astype(np.float32)


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--datos", default=os.path.join(AQUI, "datos"))
    p.add_argument("--salida", default=os.path.join(AQUI, "cabeza.npz"))
    p.add_argument("--reserva", type=float, default=0.2, help="fraccion de fotos para medir, no para entrenar")
    a = p.parse_args()

    lineas = sorted(d for d in os.listdir(a.datos) if os.path.isdir(os.path.join(a.datos, d)))
    rutas, etiquetas = [], []
    for i, linea in enumerate(lineas):
        fotos = [f for f in glob.glob(os.path.join(a.datos, linea, "*")) if f.lower().endswith((".jpg", ".jpeg", ".png", ".webp"))]
        print(f"{linea}: {len(fotos)} fotos")
        rutas += fotos
        etiquetas += [i] * len(fotos)
    caracteristicas = extractor(descargar(os.path.join(AQUI, "mobilenetv2-12.onnx")))
    x = np.concatenate([caracteristicas(np.stack([preparar(r) for r in rutas[i:i + 32]]))
                        for i in range(0, len(rutas), 32)])
    y = np.array(etiquetas)

    rng = np.random.default_rng(42)
    orden = rng.permutation(len(y))
    corte = int(len(y) * (1 - a.reserva))
    entreno, prueba = orden[:corte], orden[corte:]
    w, b = entrenar(x[entreno], y[entreno], len(lineas))
    if len(prueba):
        acierto = ((x[prueba] @ w.T + b).argmax(1) == y[prueba]).mean()
        print(f"Acierto con fotos reservadas: {acierto:.0%} ({len(prueba)} fotos)")
    # El modelo final usa todas las fotos
    w, b = entrenar(x, y, len(lineas))
    np.savez(a.salida, w=w, b=b, lineas=np.array(lineas))
    print("Cabeza guardada en", a.salida)


if __name__ == "__main__":
    main()
