# Air-gap Delivery

Two scripts cover the connected build host and the isolated target host.

## Build (connected host)

Prerequisites: Docker, jq, sha256sum, git. `syft` is optional for SBOMs.

```bash
# Reuse images that were already built/pushed to a local registry:
deploy/airgap/build-bundle.sh --version 1.0.0 \
  --api-image registry.internal/king-ai-api:1.0.0 \
  --ui-image registry.internal/king-ai-ui:1.0.0

# Or build from this checkout and include CycloneDX SBOMs:
deploy/airgap/build-bundle.sh --version 1.0.0 --build --with-sbom
```

The bundle contains:

```
king-ai-<version>/
  images/{api,ui}.tar.gz
  chart/king-ai/            Helm chart
  compose/docker-compose.yml + .env.example
  migrations/{mysql,h2}/    Flyway SQL for DBA review
  license/README.md         license format and signing recipe
  license/license-public-key.pem.example
  sbom/                     optional CycloneDX SBOMs
  manifest.json
  SHA256SUMS
```

Transfer the directory through your approved media; keep `SHA256SUMS` and
`manifest.json` together with the archives.

## Load (isolated host)

```bash
# Verify checksums and import images only
deploy/airgap/load-images.sh --bundle /media/usb/king-ai-1.0.0

# Containerd nodes
deploy/airgap/load-images.sh --bundle ./king-ai-1.0.0 --runtime ctr

# Verify, import and install/upgrade the Helm release
deploy/airgap/load-images.sh --bundle ./king-ai-1.0.0 \
  --install --namespace king-ai --release king-ai \
  --values prod-values.yaml \
  --license ./license.json \
  --license-public-key "$KING_AI_LICENSE_PUBLIC_KEY"
```

`load-images.sh` always verifies `SHA256SUMS` before importing; `--skip-checksum`
exists only for emergency recovery and prints a warning.

## License

See [license/README.md](license/README.md) for the signed file format, the
OpenSSL signing steps and the `GET /api/license` status contract. When
`KING_AI_LICENSE_ENABLED=true`, an invalid license fails startup; capacity
limits are enforced before creating spaces, users and agents.

## Docker Compose targets

Set the imported image references before starting the bundled Compose file so
Compose does not try to build from sources that are not on the host:

```bash
export KING_AI_API_IMAGE=king-ai-api:<version>
export KING_AI_UI_IMAGE=king-ai-ui:<version>
docker compose --env-file .env -f compose/docker-compose.yml up -d
```
