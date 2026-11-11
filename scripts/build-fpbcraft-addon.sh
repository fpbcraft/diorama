#!/usr/bin/env bash
# Build Diorama first, then its separately packaged FPBCraft client add-on.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

echo 'Building Diorama for NeoForge 1.21.1...'
./gradlew :1.21.1-neoforge:build

echo 'Building FPBCraft Diorama add-on against the newly built core JAR...'
(
    cd addons/fpbcraft-map-addon
    ../../gradlew -p . build
)

echo
echo 'Finished. Artifacts:'
echo "  $ROOT/versions/1.21.1-neoforge/build/libs/"
echo "  $ROOT/addons/fpbcraft-map-addon/build/libs/"
