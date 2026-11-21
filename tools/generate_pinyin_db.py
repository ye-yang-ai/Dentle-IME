"""Build the offline candidate index from jieba, conversation phrases, and dental terms.

Install pypinyin==0.55.0, then pass a downloaded jieba dict.txt as the argument.
The generated database is checked in so Android builds need neither Python nor network.
"""

from pathlib import Path
import re
import sqlite3
import sys

from pypinyin import Style, pinyin

from build_initials_index import add_initials_index


OUTPUT = Path(__file__).resolve().parents[1] / "app/src/main/assets/pinyin.db"
SPECIALIZED = Path(__file__).resolve().parents[1] / "lexicon/dental_terms.txt"
CONVERSATION = Path(__file__).resolve().parents[1] / "lexicon/conversation_frequency_sc.txt"
TRANSITIONS = Path(__file__).resolve().parents[1] / "lexicon/conversation_transitions.tsv"
HANZI = re.compile(r"^[\u3400-\u9fff]{1,6}$")
DENTAL_HANZI = re.compile(r"^[\u3400-\u9fff]{2,12}$")
PHRASE_HANZI = re.compile(r"^[\u3400-\u9fff]{2,6}$")
MAX_WORDS = 120_000
MIN_CONVERSATION_SCORE = 200


def readings(word: str) -> set[str]:
    if len(word) == 1:
        choices = pinyin(word, style=Style.NORMAL, heteronym=True)[0]
        return {reading.replace("ü", "v") for reading in choices}
    parts = [part[0] for part in pinyin(word, style=Style.NORMAL)]
    keys = {"".join(parts).replace("ü", "v")}
    for index in range(len(word) - 1):
        if word[index:index + 2] == "出血":
            alternate = parts.copy()
            alternate[index + 1] = "xue"
            keys.add("".join(alternate).replace("ü", "v"))
    return keys


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


def conversation_phrases() -> list[tuple[str, int]]:
    phrases = []
    for line in CONVERSATION.read_text(encoding="utf-8").splitlines():
        if not line or line.startswith("#"):
            continue
        word, score_text = line.split("\t")
        score = int(score_text)
        if score >= MIN_CONVERSATION_SCORE and PHRASE_HANZI.fullmatch(word):
            # Conversation scores are logarithmic; keep new phrases below high-frequency base words.
            frequency = (score - 150) ** 2 // 10
            phrases.append((word, frequency))
    return phrases


def conversation_transitions() -> list[tuple[str, str, str, int]]:
    rows = []
    for line in TRANSITIONS.read_text(encoding="utf-8").splitlines():
        if line and not line.startswith("#"):
            key, left, right, score = line.split("\t")
            rows.append((key, left, right, int(score)))
    return rows


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
    conversation = conversation_phrases()
    transitions = conversation_transitions()
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
        phrase_rows = []
        for word, frequency in conversation:
            for key in readings(word):
                if key.isascii() and key.isalpha():
                    phrase_rows.append((key, word, frequency, 2, frequency))
        connection.executemany(
            "INSERT INTO entries VALUES (?, ?, ?, ?, ?) "
            "ON CONFLICT(pinyin, word) DO UPDATE SET domain = 2, "
            "rank = max(entries.rank, excluded.rank)",
            phrase_rows,
        )
        for word in specialized:
            for key in readings(word):
                if not key.isascii() or not key.isalpha():
                    raise ValueError(f"Invalid pinyin for dental term {word}: {key}")
                boost = 200 if len(word) == 2 else 10_000
                connection.execute(
                    "INSERT INTO entries VALUES (?, ?, 1, 1, ?) "
                    "ON CONFLICT(pinyin, word) DO UPDATE SET domain = 1, "
                    "rank = max(entries.rank, ?)",
                    (key, word, boost, boost),
                )
        connection.execute("DROP INDEX entries_key")
        connection.execute("CREATE INDEX entries_exact ON entries (pinyin, rank DESC, freq DESC)")
        connection.execute(
            "CREATE TABLE transitions (pinyin TEXT NOT NULL, left_word TEXT NOT NULL, "
            "right_word TEXT NOT NULL, score INTEGER NOT NULL)"
        )
        connection.executemany("INSERT INTO transitions VALUES (?, ?, ?, ?)", transitions)
        connection.execute("CREATE INDEX transitions_pinyin ON transitions (pinyin)")
        add_initials_index(connection)
        connection.commit()
        connection.execute("VACUUM")
        print(
            f"Wrote {len(rows)} base readings, {len(phrase_rows)} conversation phrases, "
            f"{len(specialized)} dental terms, and {len(transitions)} transitions to {OUTPUT}"
        )
    finally:
        connection.close()


if __name__ == "__main__":
    main()
