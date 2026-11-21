"""Build the offline candidate index from jieba's dict.txt using pypinyin.

Install pypinyin==0.55.0, then pass a downloaded jieba dict.txt as the argument.
The generated database is checked in so Android builds need neither Python nor network.
"""

from pathlib import Path
import re
import sqlite3
import sys

from pypinyin import Style, pinyin


OUTPUT = Path(__file__).resolve().parents[1] / "app/src/main/assets/pinyin.db"
HANZI = re.compile(r"^[\u3400-\u9fff]{1,6}$")
MAX_WORDS = 120_000


def readings(word: str) -> set[str]:
    if len(word) == 1:
        choices = pinyin(word, style=Style.NORMAL, heteronym=True)[0]
        return {reading.replace("ü", "v") for reading in choices}
    parts = pinyin(word, style=Style.NORMAL)
    return {"".join(part[0] for part in parts).replace("ü", "v")}


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

    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.unlink(missing_ok=True)
    connection = sqlite3.connect(OUTPUT)
    try:
        connection.execute("CREATE TABLE entries (pinyin TEXT NOT NULL, word TEXT NOT NULL, freq INTEGER NOT NULL)")
        rows = []
        for word, freq in words[:MAX_WORDS]:
            for key in readings(word):
                if key.isascii() and key.isalpha():
                    rows.append((key, word, freq))
        connection.executemany("INSERT INTO entries VALUES (?, ?, ?)", rows)
        connection.execute("CREATE INDEX entries_pinyin ON entries (pinyin)")
        connection.commit()
        connection.execute("VACUUM")
        print(f"Wrote {len(rows)} readings to {OUTPUT}")
    finally:
        connection.close()


if __name__ == "__main__":
    main()
