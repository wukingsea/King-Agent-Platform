# Offline License

The API verifies a signed JSON license at startup. When `KING_AI_LICENSE_ENABLED=true`
and the license is missing, tampered with or expired, the process refuses to start.

## File format

```json
{
  "payload": "<base64url(claims JSON)>",
  "signature": "<base64url(Ed25519 signature over the claims bytes)>"
}
```

`base64url` without padding is recommended; standard base64 is also accepted.
The signature is computed over the decoded claims bytes exactly as stored.

### Claims

| Field | Required | Meaning |
| --- | --- | --- |
| `licenseId` | yes | Unique license identifier |
| `customer` | yes | Licensed organization |
| `edition` | no | Edition label, defaults to `enterprise` |
| `issuedAt` | yes | ISO-8601 instant/offset date-time, date-only, or epoch seconds |
| `expiresAt` | yes | Same formats; a date-only value covers the whole UTC day |
| `maxSpaces` | no | Maximum active (non-archived) spaces; `<= 0` means unlimited |
| `maxUsers` | no | Maximum local users; `<= 0` means unlimited |
| `maxAgents` | no | Maximum active agents; `<= 0` means unlimited |
| `features` | no | Free-form feature list exposed by `GET /api/license` |

The license is checked before creating a space, user or agent (including OIDC
just-in-time provisioning). Usage counts exclude archived spaces and agents.

## Generating a key pair and license

Requires OpenSSL 3.0+ for Ed25519. Keep the private key offline.

```bash
# 1. Key pair
openssl genpkey -algorithm ED25519 -out license-private.pem
openssl pkey -in license-private.pem -pubout -out license-public.pem

# 2. Public key for KING_AI_LICENSE_PUBLIC_KEY (raw 32-byte base64url)
openssl pkey -in license-private.pem -pubout -outform DER \
  | tail -c 32 | openssl base64 -A | tr '+/' '-_' | tr -d '='

# 3. Claims
cat > claims.json <<'JSON'
{
  "licenseId": "lic-2026-0001",
  "customer": "Example Corp",
  "edition": "enterprise",
  "issuedAt": "2026-10-30T00:00:00Z",
  "expiresAt": "2027-10-30T23:59:59Z",
  "maxSpaces": 20,
  "maxUsers": 500,
  "maxAgents": 100,
  "features": ["sso", "mfa", "ocr", "mcp-oauth", "air-gap"]
}
JSON

# 4. Sign and wrap
openssl pkeyutl -sign -inkey license-private.pem -rawin -in claims.json -out claims.sig
PAYLOAD=$(openssl base64 -A -in claims.json | tr '+/' '-_' | tr -d '=')
SIGNATURE=$(openssl base64 -A -in claims.sig | tr '+/' '-_' | tr -d '=')
printf '{"payload":"%s","signature":"%s"}\n' "$PAYLOAD" "$SIGNATURE" > license.json
```

`KING_AI_LICENSE_PUBLIC_KEY` also accepts the PEM text itself or the full base64
(DER) public key. Never ship the private key with a deployment.

## Deploying

Docker Compose: mount `license.json` and point the API at it.

```yaml
services:
  api:
    environment:
      KING_AI_LICENSE_ENABLED: "true"
      KING_AI_LICENSE_FILE: /app/license/license.json
      KING_AI_LICENSE_PUBLIC_KEY: "<raw-base64url-public-key>"
    volumes:
      - ./license:/app/license:ro
```

Helm: create the Secret and enable the chart flag.

```bash
kubectl -n king-ai create secret generic king-ai-license \
  --from-file=license.json=./license.json
helm upgrade --install king-ai deploy/helm/king-ai -n king-ai -f prod-values.yaml \
  --set api.license.enabled=true \
  --set api.license.existingSecret=king-ai-license \
  --set-string api.license.publicKey="<raw-base64url-public-key>"
```

`load-images.sh --license ./license.json --license-public-key ...` performs the
Secret creation and Helm flags for an air-gapped install.

## Status endpoint

`GET /api/license` (platform administrator) returns:

```json
{
  "enabled": true,
  "status": "ACTIVE",
  "licenseId": "lic-2026-0001",
  "customer": "Example Corp",
  "edition": "enterprise",
  "issuedAt": "2026-10-30T00:00:00Z",
  "expiresAt": "2027-10-30T23:59:59Z",
  "limits": {"maxSpaces": 20, "maxUsers": 500, "maxAgents": 100},
  "usage": {"spaces": 3, "users": 12, "agents": 7},
  "features": ["sso", "mfa", "ocr", "mcp-oauth", "air-gap"]
}
```

`status` is `ACTIVE`, `EXPIRED` or `DISABLED` (no license configured).
