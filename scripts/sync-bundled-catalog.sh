#!/usr/bin/env bash
# Refresh the bundled fallback catalog (app/src/main/assets/movies.json) from the live iOS repo copy.
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
url="https://raw.githubusercontent.com/movieroulettetuesday/movie-roulette-tuesday/main/MovieRouletteTuesday/Resources/movies.json"
tmp="$(mktemp)"
curl -fsSL "$url" -o "$tmp"
python3 -c "import json,sys; d=json.load(open(sys.argv[1])); assert d['categories'] and d['movies']" "$tmp"
mv "$tmp" app/src/main/assets/movies.json
echo "Bundled catalog updated; run ./gradlew testDebugUnitTest before committing."
