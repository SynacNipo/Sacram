#!/usr/bin/env python3
"""Generate release notes from git commit messages via OpenRouter (free tier).

Reads a list of commit messages from stdin and asks a free OpenRouter model
to categorize them into Added / Changed / Removed / Deprecated sections.
Prints the notes to stdout. On any failure (no key, network, bad response)
it prints nothing, so the caller can fall back to plain notes.
"""
import json
import os
import sys
import urllib.request

OPENROUTER_URL = "https://openrouter.ai/api/v1/chat/completions"
# Free models, tried in order. OpenRouter rotates its free lineup, so having
# several means a removal of one doesn't break release notes (we just try the next).
MODELS = [
    "google/gemma-4-31b-it:free",
    "openai/gpt-oss-20b:free",
    "z-ai/glm-5.2:free",
    "nvidia/nemotron-nano-9b-v2:free",
]


def main() -> None:
    key = os.environ.get("OPENROUTER_API_KEY")
    if not key:
        sys.stderr.write("OPENROUTER_API_KEY not set - skipping AI notes\n")
        return

    commits = sys.stdin.read().strip()
    if not commits:
        sys.stderr.write("no commit log provided - skipping AI notes\n")
        return

    prompt = (
        "You write release notes for 'Sacram', an Android app that turns a phone "
        "into a WiFi-Direct proxy so a PC can reach the internet through it. "
        "Below are the git commit messages for this release (newest last). "
        "Write concise release notes with these sections ONLY when they apply: "
        "## Added, ## Changed, ## Removed, ## Deprecated. Use short bullet points, "
        "be factual, never invent features, and keep the whole thing under 250 words. "
        "If nothing is notable, output a single short sentence.\n\n"
        f"COMMITS:\n{commits}"
    )

    last_err = None
    for model in MODELS:
        payload = {
            "model": model,
            "messages": [{"role": "user", "content": prompt}],
            "max_tokens": 700,
            "temperature": 0.2,
        }
        req = urllib.request.Request(
            OPENROUTER_URL,
            data=json.dumps(payload).encode("utf-8"),
            headers={
                "Authorization": f"Bearer {key}",
                "Content-Type": "application/json",
                "HTTP-Referer": "https://github.com/SynacNipo/Sacram",
                "X-Title": "Sacram",
            },
            method="POST",
        )
        try:
            with urllib.request.urlopen(req, timeout=60) as resp:
                data = json.load(resp)
            text = data["choices"][0]["message"]["content"].strip()
            if text:
                print(text)
                return
        except Exception as e:  # noqa: BLE001 - try next model
            last_err = e
            sys.stderr.write(f"model {model} failed: {e}\n")

    if last_err is not None:
        sys.stderr.write(f"AI release notes failed: {last_err}\n")


if __name__ == "__main__":
    main()
