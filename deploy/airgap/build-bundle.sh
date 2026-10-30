#!/usr/bin/env bash
# Build an offline delivery bundle: images, Helm chart, Compose file, migrations,
# license templates, manifest and checksums. Run from a connected build host.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

usage() {
  cat <<'EOF'
Usage: build-bundle.sh [options]

  --version VERSION    Bundle version used in image tags and manifest
                       (default: git describe --tags --always --dirty)
  --api-image REF      API image reference (default: king-ai-api:<version>)
  --ui-image REF       UI image reference (default: king-ai-ui:<version>)
  --output DIR         Output directory (default: dist/airgap/king-ai-<version>)
  --build              Build both images from this checkout before saving them
  --with-sbom          Generate CycloneDX SBOMs with syft when available
  --force              Replace an existing output directory
  -h, --help           Show this help

Output layout:
  images/api.tar.gz, images/ui.tar.gz
  chart/king-ai/                 Helm chart
  compose/docker-compose.yml     Compose file and .env.example
  migrations/{mysql,h2}/         Flyway SQL for DBA review
  license/README.md              License format and signing recipe
  license/license-public-key.pem.example
  manifest.json                  Bundle metadata
  SHA256SUMS                     Checksums over every file above
EOF
}

die() {
  echo "error: $*" >&2
  exit 1
}

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || die "missing required command: $1"
}

VERSION=""
API_IMAGE=""
UI_IMAGE=""
OUTPUT=""
BUILD=0
WITH_SBOM=0
FORCE=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --version) VERSION="${2:?--version needs a value}"; shift 2 ;;
    --api-image) API_IMAGE="${2:?--api-image needs a value}"; shift 2 ;;
    --ui-image) UI_IMAGE="${2:?--ui-image needs a value}"; shift 2 ;;
    --output) OUTPUT="${2:?--output needs a value}"; shift 2 ;;
    --build) BUILD=1; shift ;;
    --with-sbom) WITH_SBOM=1; shift ;;
    --force) FORCE=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "unknown option: $1" >&2; usage >&2; exit 2 ;;
  esac
done

require_cmd docker
require_cmd jq
require_cmd sha256sum

if [[ -z "$VERSION" ]]; then
  if git -C "$REPO_ROOT" describe --tags --always --dirty >/dev/null 2>&1; then
    VERSION="$(git -C "$REPO_ROOT" describe --tags --always --dirty)"
  else
    VERSION="$(date -u +%Y%m%d%H%M)"
  fi
fi
VERSION="${VERSION//[[:space:]]/_}"
API_IMAGE="${API_IMAGE:-king-ai-api:$VERSION}"
UI_IMAGE="${UI_IMAGE:-king-ai-ui:$VERSION}"
OUTPUT="${OUTPUT:-$REPO_ROOT/dist/airgap/king-ai-$VERSION}"

image_repository() {
  local reference=$1
  [[ "$reference" == *"@"* ]] && die "digest references are not supported: $reference"
  [[ "$reference" == *:* ]] || die "image reference must include a tag: $reference"
  printf '%s' "${reference%:*}"
}

image_tag() {
  local reference=$1
  printf '%s' "${reference##*:}"
}

if [[ -e "$OUTPUT" ]]; then
  [[ "$FORCE" == "1" ]] || die "output already exists: $OUTPUT (use --force to replace it)"
  rm -rf "$OUTPUT"
fi

if [[ "$BUILD" == "1" ]]; then
  echo "Building $API_IMAGE"
  docker build -f "$REPO_ROOT/king-ai-api/Dockerfile" -t "$API_IMAGE" "$REPO_ROOT"
  echo "Building $UI_IMAGE"
  docker build -f "$REPO_ROOT/king-ai-ui/Dockerfile" -t "$UI_IMAGE" "$REPO_ROOT"
fi

docker image inspect "$API_IMAGE" >/dev/null 2>&1 || die "image not found: $API_IMAGE"
docker image inspect "$UI_IMAGE" >/dev/null 2>&1 || die "image not found: $UI_IMAGE"

mkdir -p "$OUTPUT"/{images,chart,compose,migrations,license}

echo "Saving images"
docker save "$API_IMAGE" | gzip -9 > "$OUTPUT/images/api.tar.gz"
docker save "$UI_IMAGE" | gzip -9 > "$OUTPUT/images/ui.tar.gz"

echo "Copying chart, compose and migrations"
cp -R "$REPO_ROOT/deploy/helm/king-ai" "$OUTPUT/chart/"
cp "$REPO_ROOT/docker-compose.yml" "$OUTPUT/compose/"
cp "$REPO_ROOT/.env.example" "$OUTPUT/compose/.env.example"
cp -R "$REPO_ROOT/king-ai-api/src/main/resources/db/migration/mysql" "$OUTPUT/migrations/mysql"
cp -R "$REPO_ROOT/king-ai-api/src/main/resources/db/migration/h2" "$OUTPUT/migrations/h2"

echo "Copying license templates"
cp "$SCRIPT_DIR/license/README.md" "$OUTPUT/license/README.md"
cp "$SCRIPT_DIR/license/license-public-key.pem.example" "$OUTPUT/license/license-public-key.pem.example"

CHART_VERSION="$(sed -n 's/^version:[[:space:]]*//p' "$OUTPUT/chart/king-ai/Chart.yaml" | head -n 1)"
CHART_APP_VERSION="$(sed -n 's/^appVersion:[[:space:]]*//p' "$OUTPUT/chart/king-ai/Chart.yaml" | head -n 1 | tr -d '"')"

SBOM_PATH=""
if [[ "$WITH_SBOM" == "1" ]]; then
  if command -v syft >/dev/null 2>&1; then
    mkdir -p "$OUTPUT/sbom"
    echo "Generating SBOMs with syft"
    syft "$API_IMAGE" -o cyclonedx-json > "$OUTPUT/sbom/api.cdx.json"
    syft "$UI_IMAGE" -o cyclonedx-json > "$OUTPUT/sbom/ui.cdx.json"
    SBOM_PATH="sbom"
  else
    echo "warning: syft is not installed; skipping SBOM generation" >&2
  fi
fi

echo "Writing manifest"
jq -n \
  --arg product "king-ai" \
  --arg version "$VERSION" \
  --arg createdAt "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  --arg apiImage "$API_IMAGE" \
  --arg apiRepository "$(image_repository "$API_IMAGE")" \
  --arg apiTag "$(image_tag "$API_IMAGE")" \
  --arg uiImage "$UI_IMAGE" \
  --arg uiRepository "$(image_repository "$UI_IMAGE")" \
  --arg uiTag "$(image_tag "$UI_IMAGE")" \
  --arg chartVersion "$CHART_VERSION" \
  --arg chartAppVersion "$CHART_APP_VERSION" \
  --arg sbom "$SBOM_PATH" \
  '{
    product: $product,
    version: $version,
    createdAt: $createdAt,
    images: {
      api: {reference: $apiImage, repository: $apiRepository, tag: $apiTag},
      ui: {reference: $uiImage, repository: $uiRepository, tag: $uiTag}
    },
    chart: {path: "chart/king-ai", version: $chartVersion, appVersion: $chartAppVersion},
    compose: {path: "compose/docker-compose.yml", envExample: "compose/.env.example"},
    migrations: {mysql: "migrations/mysql", h2: "migrations/h2"},
    license: {docs: "license/README.md", publicKeyExample: "license/license-public-key.pem.example"},
    sbom: (if $sbom == "" then null else $sbom end),
    checksums: "SHA256SUMS"
  }' > "$OUTPUT/manifest.json"

echo "Writing checksums"
(
  cd "$OUTPUT"
  find . -type f ! -name SHA256SUMS -print0 | sort -z | xargs -0 sha256sum > SHA256SUMS
)

cat <<EOF

Bundle ready: $OUTPUT
  images   $(image_tag "$API_IMAGE") / $(image_tag "$UI_IMAGE")
  chart    $CHART_VERSION
  checksum SHA256SUMS

On the air-gapped host:
  ./load-images.sh --bundle "$OUTPUT"
  ./load-images.sh --bundle "$OUTPUT" --install --values prod-values.yaml
EOF
