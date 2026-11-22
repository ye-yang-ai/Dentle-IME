"""Keep common two-word transitions from Cassotis' offline ranking data."""

from pathlib import Path
import re
import sys


ROOT = Path(__file__).resolve().parents[1]
CONVERSATION = ROOT / "lexicon/conversation_frequency_sc.txt"
OUTPUT = ROOT / "lexicon/conversation_transitions.tsv"
HANZI = re.compile(r"^[\u3400-\u9fff]{2,6}$")
PINYIN = re.compile(r"^[a-z]{4,24}$")


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: filter_conversation_transitions.py path/to/dict_lm_transition_sc.txt")

    common = {}
    for line in CONVERSATION.read_text(encoding="utf-8").splitlines():
        if line and not line.startswith("#"):
            word, score = line.split("\t")
            if HANZI.fullmatch(word) and int(score) >= 400:
                common[word] = int(score)

    selected = []
    with Path(sys.argv[1]).open(encoding="utf-8") as source:
        for line in source:
            pinyin, words, score = line.rstrip("\n").split("\t")
            parts = words.split("|")
            if (len(parts) == 2 and PINYIN.fullmatch(pinyin)
                    and all(word in common for word in parts)):
                selected.append((pinyin, *parts, int(score)))

    with OUTPUT.open("w", encoding="utf-8", newline="\n") as destination:
        destination.write("# pinyin\tleft_word\tright_word\tscore\n")
        for pinyin, left, right, score in selected:
            destination.write(f"{pinyin}\t{left}\t{right}\t{score}\n")
    print(f"Wrote {len(selected)} transitions to {OUTPUT}")


if __name__ == "__main__":
    main()
