#!/usr/bin/env python3
"""Les captures du site ont-elles assez bougé pour mériter une pull request ?

La CI reprend les captures de la Control Center à chaque merge (`site/capture.mjs`). Les recopier
dans `site/img/` à chaque fois noierait la revue : une horloge, un compteur, un anti-crénelage
suffisent à changer des pixels. Ce script ne dit « différent » qu'au-delà d'une part de pixels
modifiés, et jamais sur la foi d'un octet.

    python3 scripts/compare-screenshots.py fresh site/img            # seuil par défaut : 3 %
    python3 scripts/compare-screenshots.py fresh site/img --threshold 0.05

Code de sortie : 0 = rien à rafraîchir, 1 = au moins une capture a dérivé (ou changé de taille),
2 = comparaison impossible (fichier absent, PNG que ce lecteur ne décode pas). Un « on n'a pas pu
comparer » n'est pas un « identique » : le workflow s'arrête alors au lieu d'ouvrir ou d'omettre
une pull request sur une mesure qui n'a pas eu lieu.

Le lecteur PNG ne couvre que ce que Chromium produit : 8 bits, RGB ou RGBA, non entrelacé.
"""

from __future__ import annotations

import argparse
import pathlib
import struct
import sys
import zlib

SHOTS = ("overview.png", "integrations.png")
CHANNEL_TOLERANCE = 24  # sur 255 : sous ce seuil, un dégradé ou un lissage n'est pas un changement


class Undecodable(Exception):
    pass


def decode(data: bytes) -> tuple[int, int, int, list[bytes]]:
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise Undecodable("ce n'est pas un PNG")
    pos, header, idat = 8, None, []
    while pos < len(data):
        length, kind = struct.unpack(">I4s", data[pos:pos + 8])
        body = data[pos + 8:pos + 8 + length]
        pos += 12 + length
        if kind == b"IHDR":
            header = struct.unpack(">IIBBBBB", body)
        elif kind == b"IDAT":
            idat.append(body)
        elif kind == b"IEND":
            break
    if header is None:
        raise Undecodable("IHDR absent")
    width, height, depth, color, _, _, interlace = header
    if depth != 8 or color not in (2, 6) or interlace != 0:
        raise Undecodable(f"profondeur {depth}, type {color}, entrelacement {interlace} : non pris en charge")
    bpp = 3 if color == 2 else 4
    raw = zlib.decompress(b"".join(idat))
    stride = width * bpp
    if len(raw) != height * (stride + 1):
        raise Undecodable("données image tronquées")
    rows, previous = [], bytearray(stride)
    for y in range(height):
        base = y * (stride + 1)
        kind, line = raw[base], bytearray(raw[base + 1:base + 1 + stride])
        if kind == 1:
            for i in range(bpp, stride):
                line[i] = (line[i] + line[i - bpp]) & 255
        elif kind == 2:
            for i in range(stride):
                line[i] = (line[i] + previous[i]) & 255
        elif kind == 3:
            for i in range(stride):
                left = line[i - bpp] if i >= bpp else 0
                line[i] = (line[i] + ((left + previous[i]) >> 1)) & 255
        elif kind == 4:
            for i in range(stride):
                a = line[i - bpp] if i >= bpp else 0
                b = previous[i]
                c = previous[i - bpp] if i >= bpp else 0
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                line[i] = (line[i] + (a if pa <= pb and pa <= pc else b if pb <= pc else c)) & 255
        elif kind != 0:
            raise Undecodable(f"filtre {kind} inconnu")
        rows.append(bytes(line))
        previous = line
    return width, height, bpp, rows


def changed_share(fresh: bytes, current: bytes) -> float:
    """Part des pixels dont un canal s'écarte de plus de CHANNEL_TOLERANCE ; 1.0 si la taille diffère."""
    w1, h1, b1, rows1 = decode(fresh)
    w2, h2, b2, rows2 = decode(current)
    if (w1, h1) != (w2, h2):
        return 1.0
    changed = 0
    for r1, r2 in zip(rows1, rows2):
        if r1 == r2:
            continue
        for x in range(w1):
            p1, p2 = x * b1, x * b2
            if any(abs(r1[p1 + c] - r2[p2 + c]) > CHANNEL_TOLERANCE for c in range(3)):
                changed += 1
    return changed / (w1 * h1)


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("fresh", type=pathlib.Path)
    parser.add_argument("current", type=pathlib.Path)
    parser.add_argument("--threshold", type=float, default=0.03)
    args = parser.parse_args(argv)
    drifted = False
    for name in SHOTS:
        try:
            share = changed_share((args.fresh / name).read_bytes(), (args.current / name).read_bytes())
        except (OSError, Undecodable, zlib.error) as error:
            print(f"::error::{name} : comparaison impossible ({error})")
            return 2
        verdict = "dérive" if share > args.threshold else "stable"
        print(f"{name} : {share:.2%} des pixels ont changé (seuil {args.threshold:.0%}) — {verdict}")
        drifted |= share > args.threshold
    return 1 if drifted else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
