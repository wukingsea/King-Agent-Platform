#!/usr/bin/env bash
# Verify and import an offline bundle produced by build-bundle.sh, then optionally
# install the Helm chart. Safe to run on a host without internet access.
set -euo pipefail

usage() {
  cat <<'EOF'
Usage: load-images.sh --bundle DIR [options]

  --bundle DIR             Bundle directory containing manifest.json and SHA256SUMS
  --runtime docker|ctr     Image importer (default: docker; ctr targets containerd/k8s)
  --containerd-namespace   Namespace used by ctr (default: k8s.io)
  --install                Run helm upgrade --install after importing the images
  --namespace NS           Kubernetes namespace (default: helm release namespace, else king-ai)
  --release NAME           Helm release name (default: king-ai)
  --values FILE            Extra Helm values file (repeatable)
  --license FILE           Signed license.json; applied as a Secret and enabled in Helm
  --license-secret NAME    Secret name holding license.json (default: king-ai-license)
  --license-public-key K   Ed25519 public key (base64url/base64 raw or PEM) for Helm
  --skip-checksum          Skip SHA256SUMS verification (not recommended)
  -h, --help               Show this help

Examples:
  ./load-images.sh --bundle /media/usb/king-ai-1.0.0
  ./load-images.sh --bundle ./king-ai-1.0.0 --runtime ctr
  ./load-images.sh --bundle ./king-ai-1.0.0 --install --values prod-values.yaml \
      --license ./license.json --license-public-key "$KING_AI_LICENSE_PUBLIC_KEY"
EOF
}

die() {
  echo "error: $*" >&2
  exit 1
}

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || die "missing required command: $1"
}

BUNDLE=""
RUNTIME="docker"
CONTAINERD_NAMESPACE="k8s.io"
INSTALL=0
NAMESPACE="king-ai"
RELEASE="king-ai"
VALUES_FILES=()
LICENSE_FILE=""
LICENSE_SECRET="king-ai-license"
LICENSE_PUBLIC_KEY=""
SKIP_CHECKSUM=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --bundle) BUNDLE="${2:?--bundle needs a value}"; shift 2 ;;
    --runtime) RUNTIME="${2:?--runtime needs a value}"; shift 2 ;;
    --containerd-namespace) CONTAINERD_NAMESPACE="${2:?}"; shift 2 ;;
    --install) INSTALL=1; shift ;;
    --namespace) NAMESPACE="${2:?--namespace needs a value}"; shift 2 ;;
    --release) RELEASE="${2:?--release needs a value}"; shift 2 ;;
    --values) VALUES_FILES+=("${2:?--values needs a value}"); shift 2 ;;
    --license) LICENSE_FILE="${2:?--license needs a value}"; shift 2 ;;
    --license-secret) LICENSE_SECRET="${2:?}"; shift 2 ;;
    --license-public-key) LICENSE_PUBLIC_KEY="${2:?}"; shift 2 ;;
    --skip-checksum) SKIP_CHECKSUM=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "unknown option: $1" >&2; usage >&2; exit 2 ;;
  esac
done

[[ -n "$BUNDLE" ]] || die "--bundle is required"
[[ -d "$BUNDLE" ]] || die "bundle directory not found: $BUNDLE"
[[ -f "$BUNDLE/manifest.json" ]] || die "manifest.json missing from $BUNDLE"
[[ -f "$BUNDLE/SHA256SUMS" ]] || die "SHA256SUMS missing from $BUNDLE"

cd "$BUNDLE"

if [[ "$SKIP_CHECKSUM" != "1" ]]; then
  require_cmd sha256sum
  echo "Verifying bundle checksums"
  sha256sum -c SHA256SUMS
else
  echo "warning: checksum verification skipped" >&2
fi

require_cmd jq
API_IMAGE="$(jq -r '.images.api.reference' manifest.json)"
UI_IMAGE="$(jq -r '.images.ui.reference' manifest.json)"
API_REPOSITORY="$(jq -r '.images.api.repository' manifest.json)"
API_TAG="$(jq -r '.images.api.tag' manifest.json)"
UI_REPOSITORY="$(jq -r '.images.ui.repository' manifest.json)"
UI_TAG="$(jq -r '.images.ui.tag' manifest.json)"
[[ -n "$API_IMAGE" && "$API_IMAGE" != "null" ]] || die "manifest.json has no api image"
[[ -n "$UI_IMAGE" && "$UI_IMAGE" != "null" ]] || die "manifest.json has no ui image"

shopt -s nullglob
IMAGES=("$BUNDLE"/images/*.tar.gz)
shopt -u nullglob
[[ ${#IMAGES[@]} -gt 0 ]] || die "no image archives under images/"

case "$RUNTIME" in
  docker)
    require_cmd docker
    for archive in "${IMAGES[@]}"; do
      echo "Loading $(basename "$archive")"
      docker load -i "$archive"
    done
    ;;
  ctr)
    require_cmd ctr
    for archive in "${IMAGES[@]}"; do
      echo "Importing $(basename "$archive") into containerd namespace $CONTAINERD_NAMESPACE"
      ctr -n "$CONTAINERD_NAMESPACE" images import "$archive"
    done
    ;;
  *)
    die "--runtime must be docker or ctr"
    ;;
esac

echo "Imported: $API_IMAGE"
echo "Imported: $UI_IMAGE"

if [[ "$INSTALL" != "1" ]]; then
  cat <<EOF

Images are ready. Install with Helm:
  helm upgrade --install $RELEASE "$BUNDLE/chart/king-ai" -n $NAMESPACE --create-namespace \\
    -f <your-values.yaml> \\
    --set api.image.repository=$API_REPOSITORY --set api.image.tag=$API_TAG \\
    --set ui.image.repository=$UI_REPOSITORY --set ui.image.tag=$UI_TAG
EOF
  exit 0
fi

require_cmd helm
require_cmd kubectl

HELM_ARGS=(
  upgrade --install "$RELEASE" "$BUNDLE/chart/king-ai"
  --namespace "$NAMESPACE" --create-namespace
  --set "api.image.repository=$API_REPOSITORY"
  --set "api.image.tag=$API_TAG"
  --set "ui.image.repository=$UI_REPOSITORY"
  --set "ui.image.tag=$UI_TAG"
)
for values in "${VALUES_FILES[@]}"; do
  [[ -f "$values" ]] || die "values file not found: $values"
  HELM_ARGS+=(--values "$values")
done

if [[ -n "$LICENSE_FILE" ]]; then
  [[ -f "$LICENSE_FILE" ]] || die "license file not found: $LICENSE_FILE"
  echo "Creating license Secret $LICENSE_SECRET"
  kubectl -n "$NAMESPACE" create secret generic "$LICENSE_SECRET" \
    --from-file=license.json="$LICENSE_FILE" \
    --dry-run=client -o yaml | kubectl apply -f -
  HELM_ARGS+=(
    --set "api.license.enabled=true"
    --set "api.license.existingSecret=$LICENSE_SECRET"
    --set "api.license.secretKey=license.json"
  )
  if [[ -n "$LICENSE_PUBLIC_KEY" ]]; then
    HELM_ARGS+=(--set-string "api.license.publicKey=$LICENSE_PUBLIC_KEY")
  fi
fi

echo "Installing release $RELEASE in namespace $NAMESPACE"
helm "${HELM_ARGS[@]}" --wait --timeout 15m

cat <<EOF

Release installed. Check status with:
  kubectl -n $NAMESPACE get pods
  kubectl -n $NAMESPACE logs job/${RELEASE}-migrate
  curl -fsS https://<your-host>/api/health
EOF
