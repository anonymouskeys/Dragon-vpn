#!/bin/bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"

source "$PROJECT_ROOT/buildScript/init/env.sh"
ENV_NB4A=1
source "$PROJECT_ROOT/buildScript/lib/core/get_source_env.sh"
pushd ..

####

DRAGON_CORE_REPOSITORY="${DRAGON_CORE_REPOSITORY:-https://github.com/anonymouskeys/Dragon-core.git}"
DRAGON_CORE_REVISION="${DRAGON_CORE_REVISION:-main}"

if [ ! -d "sing-box/.git" ]; then
  rm -rf sing-box
  git clone --no-checkout "$DRAGON_CORE_REPOSITORY" sing-box
fi
pushd sing-box
git remote set-url origin "$DRAGON_CORE_REPOSITORY"
git fetch --depth=1 origin "$DRAGON_CORE_REVISION"
git checkout --detach FETCH_HEAD
printf 'Using Dragon-core revision: '
git rev-parse HEAD
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
