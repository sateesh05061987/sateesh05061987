#!/usr/bin/env bash
# Builds the API package for the VPS. Run from Git Bash on the dev machine:
#
#   ./publish.sh            ->  ../publish-out/callrec-api.tar.gz
#
# Self-contained linux-x64 build, so the VPS needs no .NET runtime. Like KATAVU's package it
# contains no appsettings*.json: the server's config lives in /etc/callrec/api.env.
set -euo pipefail
cd "$(dirname "$0")/.."

OUT=publish-out
rm -rf "$OUT/callrec-api"
dotnet publish RecordingServer.csproj -c Release -r linux-x64 --self-contained true \
  -o "$OUT/callrec-api" /p:UseAppHost=true
rm -f "$OUT"/callrec-api/appsettings*.json
tar -czf "$OUT/callrec-api.tar.gz" -C "$OUT/callrec-api" .
echo "==> $(du -h "$OUT/callrec-api.tar.gz" | cut -f1)  $(pwd)/$OUT/callrec-api.tar.gz"
