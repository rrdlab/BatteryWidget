#!/usr/bin/env python3
"""securearc — потоковый шифрующий архиватор (tar.gz -> AES-256-GCM, STREAM).

Схема:
  master = Argon2id(пароль, salt[16 случайных байт])
  key    = HKDF-SHA256(master, info = "SARC1|" + UTC-время создания)
  данные = tar.gz, шифруются чанками по 1 MiB AES-256-GCM,
           nonce = prefix(7) || счётчик(4) || флаг_последнего(1),
           AAD = весь заголовок (соль, время, параметры KDF).
Время создания пишется в заголовок и в имя файла; подмена времени/заголовка,
перестановка, обрезка или повреждение чанков ломают проверку тега.
Время НЕ секрет и энтропии не добавляет: стойкость = пароль + Argon2id.
Уникальность ключа каждого архива обеспечивает случайная соль.
"""
import argparse, getpass, io, os, struct, sys, tarfile
from datetime import datetime, timezone
from pathlib import Path

from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.argon2 import Argon2id
from cryptography.hazmat.primitives.kdf.hkdf import HKDF

MAGIC, VERSION = b"SARC", 1
CHUNK = 1 << 20
TAG = 16
TS_FMT = "%Y%m%dT%H%M%SZ"
TS_LEN = 16
# magic | ver | mem_kib | iters | lanes | salt | nonce_prefix | timestamp
HDR = struct.Struct(">4sBIIB16s7s16s")
# Argon2id: 64 MiB, t=3, p=4 (>= RFC 9106 "second recommended" по времени/памяти для бэкапов)
DEF_MEM_KIB, DEF_ITERS, DEF_LANES = 64 * 1024, 3, 4
MAX_MEM_KIB, MAX_ITERS, MAX_LANES = 4 * 1024 * 1024, 20, 16  # защита от DoS через заголовок
MIN_PW_LEN = 12


def derive_key(password: bytes, salt: bytes, ts: bytes, mem: int, it: int, lanes: int) -> bytes:
    master = Argon2id(salt=salt, length=32, iterations=it, lanes=lanes, memory_cost=mem).derive(password)
    return HKDF(hashes.SHA256(), 32, None, b"SARC1|" + ts).derive(master)


def nonce(prefix: bytes, counter: int, last: bool) -> bytes:
    return prefix + struct.pack(">IB", counter, 1 if last else 0)


class EncWriter(io.RawIOBase):
    """Принимает открытый текст, пишет шифрованные чанки. Последний чанк помечается при close()."""

    def __init__(self, out, aead, prefix, header):
        self.out, self.aead, self.prefix, self.hdr = out, aead, prefix, header
        self.buf, self.ctr = bytearray(), 0

    def writable(self): return True

    def _emit(self, data, last):
        if self.ctr >= 1 << 32 or (self.ctr == (1 << 32) - 1 and not last):
            raise OverflowError("архив слишком велик (счётчик чанков)")
        self.out.write(self.aead.encrypt(nonce(self.prefix, self.ctr, last), bytes(data), self.hdr))
        self.ctr += 1

    def write(self, b):
        self.buf += b
        while len(self.buf) > CHUNK:  # строго >, чтобы последний чанк был непустым
            self._emit(self.buf[:CHUNK], False)
            del self.buf[:CHUNK]
        return len(b)

    def close(self):
        if not self.closed:
            self._emit(self.buf, True)
            self.buf.clear()
        super().close()


class DecReader(io.RawIOBase):
    """Читает шифрованные чанки, отдаёт открытый текст; проверяет теги и факт завершения."""

    def __init__(self, inp, aead, prefix, header):
        self.inp, self.aead, self.prefix, self.hdr = inp, aead, prefix, header
        self.cur = inp.read(CHUNK + TAG)
        self.ctr, self.buf, self.done = 0, b"", False

    def readable(self): return True

    def _next(self):
        nxt = self.inp.read(CHUNK + TAG)
        last = not nxt
        if len(self.cur) < TAG or (not last and len(self.cur) != CHUNK + TAG):
            raise ValueError("повреждённый или обрезанный архив")
        self.buf = self.aead.decrypt(nonce(self.prefix, self.ctr, last), self.cur, self.hdr)
        self.ctr += 1
        self.cur, self.done = nxt, last

    def readinto(self, b):
        while not self.buf and not self.done:
            self._next()
        n = min(len(b), len(self.buf))
        b[:n], self.buf = self.buf[:n], self.buf[n:]
        return n


def get_password(confirm: bool) -> bytes:
    pw = os.environ.get("SECUREARC_PASSWORD")
    if pw is None:
        pw = getpass.getpass("Пароль: ")
        if confirm and getpass.getpass("Повторите пароль: ") != pw:
            sys.exit("Пароли не совпадают")
    if confirm and len(pw) < MIN_PW_LEN:
        sys.exit(f"Пароль короче {MIN_PW_LEN} символов")
    return pw.encode()


def create(src: Path, dst_dir: Path, password: bytes) -> Path:
    now = datetime.now(timezone.utc)
    ts = now.strftime(TS_FMT).encode()
    salt, prefix = os.urandom(16), os.urandom(7)
    header = HDR.pack(MAGIC, VERSION, DEF_MEM_KIB, DEF_ITERS, DEF_LANES, salt, prefix, ts)
    aead = AESGCM(derive_key(password, salt, ts, DEF_MEM_KIB, DEF_ITERS, DEF_LANES))
    dst = dst_dir / f"{src.name}_{ts.decode()}.sarc"
    tmp = dst.with_suffix(".sarc.part")
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    try:
        with os.fdopen(fd, "wb") as out:
            out.write(header)
            w = EncWriter(out, aead, prefix, header)
            with tarfile.open(fileobj=w, mode="w|gz") as tar:
                tar.add(src, arcname=src.name)
            w.close()
            out.flush(); os.fsync(out.fileno())
        os.replace(tmp, dst)
    except BaseException:
        tmp.unlink(missing_ok=True)
        raise
    return dst


def extract(arc: Path, dst_dir: Path, password: bytes) -> None:
    with open(arc, "rb") as f:
        header = f.read(HDR.size)
        if len(header) != HDR.size:
            raise ValueError("не архив securearc")
        magic, ver, mem, it, lanes, salt, prefix, ts = HDR.unpack(header)
        if magic != MAGIC or ver != VERSION:
            raise ValueError("неизвестный формат/версия")
        if not (8 * lanes <= mem <= MAX_MEM_KIB and 1 <= it <= MAX_ITERS and 1 <= lanes <= MAX_LANES):
            raise ValueError("недопустимые параметры KDF")
        datetime.strptime(ts.decode(), TS_FMT)  # валидность времени (подлинность — через AAD)
        aead = AESGCM(derive_key(password, salt, ts, mem, it, lanes))
        r = io.BufferedReader(DecReader(f, aead, prefix, header))
        dst_dir.mkdir(parents=True, exist_ok=True)
        with tarfile.open(fileobj=r, mode="r|gz") as tar:
            tar.extractall(dst_dir, filter="data")  # запрет абсолютных путей, '..', спец. файлов
        while r.read(CHUNK):  # дочитать хвост: проверить финальный тег (защита от обрезки)
            pass


def main():
    p = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    sub = p.add_subparsers(dest="cmd", required=True)
    c = sub.add_parser("create", help="зашифрованный архив из файла/каталога")
    c.add_argument("src", type=Path); c.add_argument("dst_dir", type=Path)
    x = sub.add_parser("extract", help="расшифровать и распаковать")
    x.add_argument("archive", type=Path); x.add_argument("dst_dir", type=Path)
    a = p.parse_args()
    try:
        if a.cmd == "create":
            a.dst_dir.mkdir(parents=True, exist_ok=True)
            print(create(a.src, a.dst_dir, get_password(True)))
        else:
            extract(a.archive, a.dst_dir, get_password(False))
    except InvalidTag:
        sys.exit("Неверный пароль или архив изменён/повреждён")
    except (ValueError, tarfile.TarError, OSError) as e:
        sys.exit(f"Ошибка: {e}")


if __name__ == "__main__":
    main()
