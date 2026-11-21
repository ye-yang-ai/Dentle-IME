"""Build the offline candidate index from jieba and the reviewed dental lexicon.

Install pypinyin==0.55.0, then pass a downloaded jieba dict.txt as the argument.
The generated database is checked in so Android builds need neither Python nor network.
"""

from pathlib import Path
import re
import sqlite3
import sys

from pypinyin import Style, pinyin


OUTPUT = Path(__file__).resolve().parents[1] / "app/src/main/assets/pinyin.db"
SPECIALIZED = Path(__file__).resolve().parents[1] / "lexicon/dental_terms.txt"
HANZI = re.compile(r"^[\u3400-\u9fff]{1,6}$")
DENTAL_HANZI = re.compile(r"^[\u3400-\u9fff]{2,12}$")
MAX_WORDS = 120_000


def readings(word: str) -> set[str]:
    if len(word) == 1:
        choices = pinyin(word, style=Style.NORMAL, heteronym=True)[0]
        return {reading.replace("ü", "v") for reading in choices}
    parts = pinyin(word, style=Style.NORMAL)
    return {"".join(part[0] for part in parts).replace("ü", "v")}


def dental_terms() -> list[str]:
    terms = []
    seen = set()
    for line_number, line in enumerate(SPECIALIZED.read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip() or line.startswith("#"):
            continue
        for word in line.split():
            if not DENTAL_HANZI.fullmatch(word):
                raise ValueError(f"Invalid dental term at line {line_number}: {word}")
            if word in seen:
                raise ValueError(f"Duplicate dental term at line {line_number}: {word}")
            seen.add(word)
            terms.append(word)
    if not 500 <= len(terms) <= 1000:
        raise ValueError(f"Expected 500-1000 dental terms, got {len(terms)}")
    return terms


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: generate_pinyin_db.py path/to/jieba/dict.txt")
    source_file = Path(sys.argv[1])
    words = []
    with source_file.open(encoding="utf-8") as source:
        for line in source:
            parts = line.rstrip().split(" ")
            if len(parts) >= 2 and HANZI.fullmatch(parts[0]):
                words.append((parts[0], int(parts[1])))
    words.sort(key=lambda item: item[1], reverse=True)

    specialized = dental_terms()
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.unlink(missing_ok=True)
    connection = sqlite3.connect(OUTPUT)
    try:
        connection.execute("CREATE TABLE entries (pinyin TEXT NOT NULL, word TEXT NOT NULL, freq INTEGER NOT NULL, domain INTEGER NOT NULL, rank INTEGER NOT NULL)")
        rows = []
        for word, freq in words[:MAX_WORDS]:
            for key in readings(word):
                if key.isascii() and key.isalpha():
                    rows.append((key, word, freq, 0, freq))
        connection.executemany("INSERT INTO entries VALUES (?, ?, ?, ?, ?)", rows)
        connection.execute("CREATE UNIQUE INDEX entries_key ON entries (pinyin, word)")
        for word in specialized:
            for key in readings(word):
                if not key.isascii() or not key.isalpha():
                    raise ValueError(f"Invalid pinyin for dental term {word}: {key}")
                boost = 200 if len(word) == 2 else 10_000
                connection.execute(
                    "INSERT INTO entries VALUES (?, ?, 1, 1, ?) "
                    "ON CONFLICT(pinyin, word) DO UPDATE SET domain = 1, rank = max(freq, ?)",
                    (key, word, boost, boost),
                )
        connection.execute("DROP INDEX entries_key")
        connection.execute("CREATE INDEX entries_exact ON entries (pinyin, rank DESC, freq DESC)")
        connection.commit()
        connection.execute("VACUUM")
        print(f"Wrote {len(rows)} general readings and {len(specialized)} dental terms to {OUTPUT}")
    finally:
        connection.close()


if __name__ == "__main__":
    main()
