#!/bin/bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
CORE_PATCH="$PROJECT_ROOT/buildScript/lib/core/patch_sing_box.py"

source "$PROJECT_ROOT/buildScript/init/env.sh"
ENV_NB4A=1
source "$PROJECT_ROOT/buildScript/lib/core/get_source_env.sh"
pushd ..

####

if [ ! -d "sing-box" ]; then
  git clone --no-checkout https://github.com/MatsuriDayo/sing-box.git
fi
pushd sing-box
git checkout "$COMMIT_SING_BOX"
if [ ! -f "$CORE_PATCH" ]; then
  echo "Missing core patch: $CORE_PATCH" >&2
  exit 1
fi
python3 "$CORE_PATCH"
popd

####

if [ ! -d "libneko" ]; then
  git clone --no-checkout https://github.com/MatsuriDayo/libneko.git
fi
pushd libneko
git checkout "$COMMIT_LIBNEKO"
popd

####

popd
