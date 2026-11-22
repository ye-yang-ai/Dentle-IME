"""Add indexed pinyin initials to the checked-in candidate database."""

from pathlib import Path
import re
import sqlite3

from pypinyin import Style, pinyin


DATABASE = Path(__file__).resolve().parents[1] / "app/src/main/assets/pinyin.db"
CONVERSATION = Path(__file__).resolve().parents[1] / "lexicon/conversation_frequency_sc.txt"


def initials(word: str, full_pinyin: str = "") -> str:
    if len(word) == 1 and full_pinyin:
        primary = pinyin(word, style=Style.NORMAL)[0][0].replace("ü", "v")
        return full_pinyin[0] if full_pinyin == primary else ""
    return "".join(part[0] for part in pinyin(word, style=Style.FIRST_LETTER))


def add_initials_index(connection: sqlite3.Connection) -> None:
    connection.execute("CREATE INDEX IF NOT EXISTS entries_word ON entries (word, rank DESC)")
    entry_columns = {row[1] for row in connection.execute("PRAGMA table_info(entries)")}
    if "initials" not in entry_columns:
        connection.execute("ALTER TABLE entries ADD COLUMN initials TEXT")
    connection.execute("DROP INDEX IF EXISTS entries_initials")
    entries = connection.execute("SELECT rowid, word, pinyin FROM entries")
    connection.executemany(
        "UPDATE entries SET initials = ? WHERE rowid = ?",
        ((initials(word, key), rowid) for rowid, word, key in entries),
    )
    connection.execute(
        "CREATE INDEX entries_initials ON entries (initials, rank DESC, freq DESC)"
    )

    transition_columns = {row[1] for row in connection.execute("PRAGMA table_info(transitions)")}
    if "initials" not in transition_columns:
        connection.execute("ALTER TABLE transitions ADD COLUMN initials TEXT")
    connection.execute("DROP INDEX IF EXISTS transitions_initials")
    transitions = connection.execute("SELECT rowid, left_word, right_word FROM transitions")
    connection.executemany(
        "UPDATE transitions SET initials = ? WHERE rowid = ?",
        ((initials(left + right), rowid) for rowid, left, right in transitions),
    )
    connection.execute("CREATE INDEX transitions_initials ON transitions (initials)")


def promote_conversation_phrases(connection: sqlite3.Connection) -> None:
    connection.execute("CREATE TEMP TABLE conversation_rank (word TEXT PRIMARY KEY, rank INTEGER)")
    ranks = []
    for line in CONVERSATION.read_text(encoding="utf-8").splitlines():
        if not line or line.startswith("#"):
            continue
        word, score_text = line.split("\t")
        score = int(score_text)
        if score >= 200 and re.fullmatch(r"[\u3400-\u9fff]{2,6}", word):
            ranks.append((word, (score - 150) ** 2 // 10))
    connection.executemany("INSERT OR REPLACE INTO conversation_rank VALUES (?, ?)", ranks)
    connection.execute(
        "UPDATE entries SET domain = CASE WHEN domain = 1 THEN 1 ELSE 2 END, "
        "rank = max(rank, (SELECT rank FROM conversation_rank WHERE word = entries.word)) "
        "WHERE word IN (SELECT word FROM conversation_rank)"
    )
    connection.execute("DROP TABLE conversation_rank")


def main() -> None:
    with sqlite3.connect(DATABASE) as connection:
        promote_conversation_phrases(connection)
        add_initials_index(connection)
        connection.commit()
        connection.execute("VACUUM")


if __name__ == "__main__":
    main()
