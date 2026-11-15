# The certificate fixture the sealer is tested against

`cadena-original-fixture.crt.der` is a **self-signed, test-only** X.509 certificate. It exists for exactly
two facts a sealer has to state and a test has to read back: its **serial in decimal** (the CFDI's
`NoCertificado`) and its **DER bytes in base 64** (the CFDI's `Certificado`).

## How it was made

Once, at authoring time, on 2026-11-15:

```bash
openssl req -x509 -newkey rsa:2048 -nodes -days 3650 \
  -set_serial 30001000000400002434 \
  -subj "/C=MX/O=LifeControl Test Fixture/CN=Cadena Original Fixture" \
  -keyout /dev/stdout -out fixture.crt > /dev/null
openssl x509 -in fixture.crt -outform DER -out cadena-original-fixture.crt.der
```

| Fact | Value |
| --- | --- |
| Bytes | `861` |
| SHA-256 of the DER | `80b848c5415f488da6ba17445255266a19afcef6d52b6f4e3b12879a35989d8f` |
| Serial (hex, as `openssl` prints it) | `01A058F68C5A560D82` |
| Serial (decimal, as the CFDI states it) | `30001000000400002434` |
| Subject | `C=MX, O=LifeControl Test Fixture, CN=Cadena Original Fixture` |
| Validity | 2026-11-15 → 2036-11-12 |

## Its private key is deliberately not here

The generation discarded the key (`-keyout /dev/stdout` into `/dev/null`), and this directory carries only
the certificate. Nothing needs the fixture's key: the suite that proves the signature **generates its own
RSA key pair** at run time, hands its PKCS#8 to the sealer as configuration, and verifies the seal with the
matching public key. A committed private key would be a credential in a repository, and asserting a
signature against a fixed byte string would prove only that the test can copy a constant.

## What it is not

It is not a CSD, it was not issued by the SAT, and it is not valid for stamping anything. It is a pair of
bytes whose serial and encoding a test can compare against, and nothing more.
