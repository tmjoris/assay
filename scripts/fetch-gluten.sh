#!/usr/bin/env bash
# Downloads the Gluten + Velox bundle for Spark 4.0 and checks it against the release checksum.
set -euo pipefail

version=1.7.0
sha512=81e692a7d2a136cdf7ade33a6c54385239eb45c8c4fe75b3667bca223cb075bfad0aec98a345509ec5fb3846e012dc974316bbd1c931c033605606ff4cf3c563
name="apache-gluten-${version}-bin-spark-4.0"
dest="$(cd "$(dirname "$0")/.." && pwd)/.engines/gluten-${version}"

if compgen -G "${dest}/gluten-velox-bundle-*.jar" >/dev/null; then
  exit 0
fi

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
curl -fsSL -o "$tmp/gluten.tgz" "https://downloads.apache.org/gluten/${version}/${name}.tar.gz"
echo "${sha512}  $tmp/gluten.tgz" | sha512sum -c --quiet
mkdir -p "$dest"
tar xzf "$tmp/gluten.tgz" -C "$dest" --strip-components=1 --wildcards '*.jar'
