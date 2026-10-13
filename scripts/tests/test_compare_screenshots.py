import importlib.util
import pathlib
import struct
import tempfile
import unittest
import zlib

SPEC = importlib.util.spec_from_file_location(
    "compare_screenshots", pathlib.Path(__file__).resolve().parent.parent / "compare-screenshots.py")
compare = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(compare)


def png(width, height, pixel, *, color=2, depth=8, filter_kind=0, block=None):
    """PNG minimal ; `block` = (x0, y0, x1, y1, pixel) peint un rectangle d'une autre couleur."""
    bpp = 3 if color == 2 else 4
    rows = []
    for y in range(height):
        row = bytearray()
        for x in range(width):
            inside = block and block[0] <= x < block[2] and block[1] <= y < block[3]
            row += bytes(block[4] if inside else pixel)[:bpp].ljust(bpp, b"\xff")
        rows.append(row)
    raw = bytearray()
    previous = bytearray(width * bpp)
    for row in rows:
        raw.append(filter_kind)
        if filter_kind == 1:
            raw += bytes((row[i] - (row[i - bpp] if i >= bpp else 0)) & 255 for i in range(len(row)))
        elif filter_kind == 2:
            raw += bytes((row[i] - previous[i]) & 255 for i in range(len(row)))
        else:
            raw += row
        previous = row

    def chunk(kind, body):
        return struct.pack(">I", len(body)) + kind + body + struct.pack(">I", zlib.crc32(kind + body))

    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, depth, color, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(bytes(raw))) + chunk(b"IEND", b""))


class ChangedShare(unittest.TestCase):
    def test_identical_images_have_no_change(self):
        self.assertEqual(0.0, compare.changed_share(png(20, 10, (9, 9, 9)), png(20, 10, (9, 9, 9))))

    def test_filters_decode_to_the_same_pixels(self):
        flat = png(20, 10, (30, 60, 90))
        for kind in (1, 2):
            self.assertEqual(0.0, compare.changed_share(flat, png(20, 10, (30, 60, 90), filter_kind=kind)))

    def test_rgba_is_read(self):
        self.assertEqual(0.0, compare.changed_share(png(8, 8, (1, 2, 3), color=6), png(8, 8, (1, 2, 3), color=6)))

    def test_a_block_counts_by_area(self):
        changed = png(10, 10, (0, 0, 0), block=(0, 0, 5, 4, (255, 255, 255)))
        self.assertAlmostEqual(0.2, compare.changed_share(png(10, 10, (0, 0, 0)), changed))

    def test_a_gradient_under_the_tolerance_is_not_a_change(self):
        self.assertEqual(0.0, compare.changed_share(png(10, 10, (100, 100, 100)), png(10, 10, (110, 112, 100))))

    def test_a_different_size_is_a_full_change(self):
        self.assertEqual(1.0, compare.changed_share(png(10, 10, (0, 0, 0)), png(11, 10, (0, 0, 0))))

    def test_what_the_reader_cannot_decode_is_refused_not_guessed(self):
        with self.assertRaises(compare.Undecodable):
            compare.decode(png(4, 4, (0, 0, 0), depth=16))
        with self.assertRaises(compare.Undecodable):
            compare.decode(b"pas un png")


class Main(unittest.TestCase):
    def folder(self, **shots):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        for name, data in shots.items():
            (pathlib.Path(directory.name) / f"{name}.png").write_bytes(data)
        return directory.name

    def test_exit_codes_tell_stable_drifted_and_unreadable_apart(self):
        base = png(10, 10, (0, 0, 0))
        moved = png(10, 10, (0, 0, 0), block=(0, 0, 10, 5, (255, 255, 255)))
        same = self.folder(overview=base, integrations=base)
        self.assertEqual(0, compare.main([same, same]))
        self.assertEqual(1, compare.main([self.folder(overview=moved, integrations=base), same]))
        self.assertEqual(2, compare.main([self.folder(overview=base), same]))


if __name__ == "__main__":
    unittest.main()
