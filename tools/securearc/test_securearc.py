import os, tempfile, unittest
from pathlib import Path
import securearc as s

PW = b"correct horse battery staple"


class T(unittest.TestCase):
    def setUp(self):
        self.d = Path(tempfile.mkdtemp())
        src = self.d / "data"; src.mkdir()
        (src / "a.bin").write_bytes(os.urandom(3 * s.CHUNK + 123))  # несколько чанков
        (src / "b.txt").write_text("hi")
        self.arc = s.create(src, self.d, PW)

    def test_roundtrip(self):
        s.extract(self.arc, self.d / "out", PW)
        self.assertEqual((self.d / "out/data/a.bin").read_bytes(), (self.d / "data/a.bin").read_bytes())

    def test_wrong_password(self):
        with self.assertRaises(Exception): s.extract(self.arc, self.d / "o", b"x" * 20)

    def test_tamper_time(self):
        b = bytearray(self.arc.read_bytes()); b[s.HDR.size - 1] ^= 1
        self.arc.write_bytes(b)
        with self.assertRaises(Exception): s.extract(self.arc, self.d / "o", PW)

    def test_truncate(self):
        b = self.arc.read_bytes()
        for cut in (len(b) - 1, len(b) - (s.CHUNK + s.TAG)):
            self.arc.write_bytes(b[:cut])
            with self.assertRaises(Exception): s.extract(self.arc, self.d / "o", PW)

    def test_two_archives_differ(self):
        a2 = s.create(self.d / "data", self.d / "x" if (self.d / "x").mkdir() is None else self.d, PW)
        self.assertNotEqual(self.arc.read_bytes()[:60], a2.read_bytes()[:60])


if __name__ == "__main__":
    unittest.main()
