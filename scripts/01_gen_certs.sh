#!/bin/bash
set -e
TESTBED=~/trustlens/testbed
mkdir -p "$TESTBED/certs/"{ms-user,ms-location,ms-proximity-detection}

openssl req -x509 -newkey rsa:4096 \
  -keyout "$TESTBED/certs/ca.key" \
  -out    "$TESTBED/certs/ca.crt" \
  -days 365 -nodes -subj "/CN=TrustLens-CA"

for SVC in ms-user ms-location ms-proximity-detection; do
  DIR="$TESTBED/certs/$SVC"
  openssl req -newkey rsa:2048 -keyout "$DIR/key.pem" \
    -out "$DIR/csr.pem" -nodes -subj "/CN=$SVC"
  openssl x509 -req -in "$DIR/csr.pem" \
    -CA "$TESTBED/certs/ca.crt" -CAkey "$TESTBED/certs/ca.key" \
    -CAcreateserial -out "$DIR/cert.pem" -days 365
  openssl pkcs12 -export -out "$DIR/keystore.p12" \
    -inkey "$DIR/key.pem" -in "$DIR/cert.pem" -passout pass:changeit
  keytool -importcert -noprompt \
    -file "$TESTBED/certs/ca.crt" -keystore "$DIR/truststore.p12" \
    -storetype PKCS12 -storepass changeit -alias trustlens-ca
  echo "  [OK] $SVC"
done
echo "=== Certificates ready ==="
