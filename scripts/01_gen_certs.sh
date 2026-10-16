#!/bin/bash
# Generates a local CA and per-component certificates for p3 (mTLS):
#   - gateway                      : client certificate presented to the services
#   - ms-user, ms-location         : HTTPS server certificates (client-auth=need)
#   - ms-* (all four)              : Kafka client certificates
#   - kafka                        : broker certificate for the SSL listener
# Certificates carry a DNS SAN so that Kafka/JSSE hostname verification works.
# Idempotent: skips generation when testbed/certs-zt/ca.crt already exists.
set -e
CERTS=~/trustlens/testbed/certs-zt
PASS=changeit
if [ -f "$CERTS/ca.crt" ]; then echo "[certs] already present in $CERTS"; exit 0; fi
mkdir -p "$CERTS"

openssl req -x509 -newkey rsa:2048 -nodes -days 3650 \
  -keyout "$CERTS/ca.key" -out "$CERTS/ca.crt" -subj "/O=TrustLens/CN=TrustLens-CA" 2>/dev/null

for SVC in gateway ms-user ms-location ms-proximity-detection ms-notification kafka; do
  DIR="$CERTS/$SVC"; mkdir -p "$DIR"
  printf 'subjectAltName=DNS:%s,DNS:localhost\nextendedKeyUsage=serverAuth,clientAuth\n' "$SVC" > "$DIR/ext.cnf"
  openssl req -newkey rsa:2048 -nodes -keyout "$DIR/key.pem" -out "$DIR/csr.pem" \
    -subj "/O=TrustLens/CN=$SVC" 2>/dev/null
  openssl x509 -req -in "$DIR/csr.pem" -CA "$CERTS/ca.crt" -CAkey "$CERTS/ca.key" \
    -CAcreateserial -days 3650 -extfile "$DIR/ext.cnf" -out "$DIR/cert.pem" 2>/dev/null
  openssl pkcs12 -export -name "$SVC" -inkey "$DIR/key.pem" -in "$DIR/cert.pem" \
    -out "$DIR/keystore.p12" -passout pass:$PASS
  keytool -importcert -noprompt -alias trustlens-ca -file "$CERTS/ca.crt" \
    -keystore "$DIR/truststore.p12" -storetype PKCS12 -storepass $PASS >/dev/null
  rm -f "$DIR/csr.pem" "$DIR/ext.cnf"
  echo "  [OK] $SVC"
done
# Confluent images read keystore passwords from credential files
for f in keystore_creds key_creds truststore_creds; do echo "$PASS" > "$CERTS/kafka/$f"; done
chmod -R a+rX "$CERTS"
echo "=== Certificates ready in $CERTS ==="
