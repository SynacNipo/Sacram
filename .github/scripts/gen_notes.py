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
import time
import urllib.error
import urllib.request

OPENROUTER_URL = "https://openrouter.ai/api/v1/chat/completions"
GROQ_URL = "https://api.groq.com/openai/v1/chat/completions"
GITHUB_MODELS_URL = "https://models.github.ai/inference/chat/completions"

# Providers tried in order. GitHub Models is keyless (uses the built-in
# GITHUB_TOKEN, needs `models: read` permission) so it works with zero signup -
# tried first. Groq/OpenRouter are fallbacks if a key is configured.
# Each provider lists free models to try (in case one is pulled).
PROVIDERS = [
    ("githubmodels", GITHUB_MODELS_URL, "GITHUB_TOKEN", [
        "openai/gpt-4o-mini",
        "openai/gpt-4o",
    ]),
    ("groq", GROQ_URL, "GROQ_API_KEY", [
        "llama-3.3-70b-versatile",
        "llama-3.1-8b-instant",
        "gemma2-9b-it",
    ]),
    ("openrouter", OPENROUTER_URL, "OPENROUTER_API_KEY", [
        "google/gemma-4-31b-it:free",
        "openai/gpt-oss-20b:free",
        "z-ai/glm-5.2:free",
        "nvidia/nemotron-nano-9b-v2:free",
    ]),
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
    for provider, url, key_env, models in PROVIDERS:
        key = os.environ.get(key_env)
        if not key:
            sys.stderr.write(f"{provider}: no {key_env} set - skipping\n")
            continue
        for model in models:
            # Free-tier rate limits are tight; retry with backoff on 429/5xx
            # instead of immediately burning the next model (which just 429s too
            # and wastes what little quota we have). Stop at first model that answers.
            done = False
            for attempt in range(4):
                payload = {
                    "model": model,
                    "messages": [{"role": "user", "content": prompt}],
                    "max_tokens": 700,
                    "temperature": 0.2,
                }
                req = urllib.request.Request(
                    url,
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
                    done = True
                    break
                except urllib.error.HTTPError as e:
                    if e.code == 429 or e.code >= 500:
                        wait = (attempt + 1) * 15
                        sys.stderr.write(f"{provider}/{model} {e.code}, retry in {wait}s\n")
                        time.sleep(wait)
                        continue
                    last_err = e
                    sys.stderr.write(f"{provider}/{model} failed: {e}\n")
                    break
                except Exception as e:  # noqa: BLE001 - non-HTTP error, try next model
                    last_err = e
                    sys.stderr.write(f"{provider}/{model} failed: {e}\n")
                    break
            if done:
                return

    if last_err is not None:
        sys.stderr.write(f"AI release notes failed: {last_err}\n")


if __name__ == "__main__":
    main()
