#!/usr/bin/env python3
"""Builds sidekit/src/main/assets/t9/en_words.txt from FrequencyWords' en_50k.txt.

Source: https://github.com/hermitdave/FrequencyWords (content/2018/en/en_50k.txt),
word lists CC BY-SA 4.0, derived from OpenSubtitles 2018. See assets/t9/NOTICE.

Output: one lowercase word per line, most frequent first. Rank is the line number; the app keeps
no counts. Words are limited to a-z plus inner apostrophes (typed on the 1 key).

Usage: build_t9_words.py en_50k.txt > sidekit/src/main/assets/t9/en_words.txt
"""
import re
import sys

LIMIT = 40000
WORD = re.compile(r"^[a-z]+(?:'[a-z]+)?$")
SINGLE_LETTERS = {"a", "i"}
# Not suggested by prediction. Still typeable in multi-tap, and learned once typed.
BLOCK = {
    "fuck", "fucking", "fucked", "fucker", "fuckin", "motherfucker", "shit", "shitty", "bitch",
    "bitches", "cunt", "dick", "cock", "pussy", "whore", "slut", "bastard", "asshole", "nigger",
    "nigga", "fag", "faggot", "retard", "twat", "wanker", "dickhead", "bullshit",
}

seen = set()
out = []
with open(sys.argv[1], encoding="utf-8") as f:
    for line in f:
        word = line.split(" ", 1)[0].strip().lower()
        if not WORD.match(word) or len(word) > 20 or word in seen or word in BLOCK:
            continue
        if len(word) == 1 and word not in SINGLE_LETTERS:
            continue
        seen.add(word)
        out.append(word)
        if len(out) == LIMIT:
            break
sys.stdout.write("\n".join(out) + "\n")
