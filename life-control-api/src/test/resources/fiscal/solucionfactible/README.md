# Solución Factible's published examples, committed verbatim

Every file in this directory is **the vendor's own published example**, committed unmodified so each suite is
pinned against a real document and never against one this tree invented. They were fetched once, at authoring time;
no test touches the network — the adapter's suite serves the two responses from a loopback
`com.sun.net.httpserver.HttpServer` on port `0`, and the request example is read from the classpath.

## The stamping answers

| File | Fetched from |
| --- | --- |
| `response_correcto_Timbrado.timbrarBase64.xml` | `https://www.solucionfactible.com/sfic/resources/files/response_correcto_Timbrado.timbrarBase64.zip` |
| `response_soap_Timbrado.timbrarBase64.xml` | `https://www.solucionfactible.com/sfic/resources/files/response_error_Timbrado.timbrarBase64.zip` |

Fetched on **2026-11-15**, both `HTTP 200`, `cf742e64533199a1c61948c3d25d06ae` and
`2da94a17de6099485b78d1a2934c1b30` (MD5 of the uncompressed members). Each zip carries a single member and
both were extracted with no edit. The second file's member is named `response_soap_…` inside the vendor's
`response_error_…` archive, and the vendor's name is kept here rather than harmonized: a reader who downloads
the archive again has to find the same file.

## What these examples do not cover, and why the six-field case is built in the suite

They were published in 2012, against **CFDI 3.2**, and their `cfdiTimbrado` carries a
`TimbreFiscalDigital` of **version 1.0**, which predates two attributes the adapter has to read: its timbre
states neither `RfcProvCertif` nor `Version="1.1"`, and `FechaTimbrado` there is local time while the
response's own `fechaTimbrado` field is UTC (`…10:26:24` in the document, `…15:26:24.718Z` at the envelope).

So these two files pin the **envelope** and the two-level numeric mapping as the vendor serializes them — one
answer that stamped a document and one that refused it with `401` while the call itself was `200` — which is
what a published serialization can be pinned against. The **six facts of a stamped answer** are pinned
against a CFDI 4.0 document with a
`TimbreFiscalDigital` version 1.1 built inside the suite from the published contract
(`odd/tasks/cfdi-fiscal-document.md`, `L18`(b)), because that is the class of document this tree sends and a
2012 example cannot state it.

Both examples also order the fields of `resultados` alphabetically, which no WSDL requires: the adapter reads
them by name and namespace and never by position, and these fixtures are what makes that a proven claim
rather than an assumption.

## The cancellation request (`CancelaCFDIEjemplo.xml`)

The Anexo 20 cancellation request the SAT's schema defines, as Solución Factible publishes it beside
`enviarSolicitudCancelacion`. It is the **known-answer fixture** of
`VendorCancellationKnownAnswerTest` (`odd/tasks/cfdi-fiscal-document.md`, `L20`(g) and `L20`(h)): the suite asks
the JDK to validate the file's own signature against the certificate embedded in its own `KeyInfo`, and asserts
that `CfdiCancellationRequestBuilder` renders the same bytes from the same values.

| File | Fetched from | Bytes | SHA-256 |
| --- | --- | --- | --- |
| `CancelaCFDIEjemplo.xml` | `https://solucionfactible.com/sfic/resources/files/solicitudCancelacion-anexo20.zip` | `3306` | `5bd1e32025d9d87d68dc5267f6a88bb261537e60e4e59bc575d4e4ed62137a2e` |

Fetched on **2026-11-15**, `HTTP 200`. The archive is `997d6b857d8df3abbf6c3f4f857afa9f` (MD5) and
`cf6124d2e89bd3546132a51180a6233aaeb79d3f4a2a593aa7344aa855a94cac` (SHA-256); it carries a single member,
`CancelaCFDIEjemplo.xml`, extracted with no edit.

### Why its SHA-1 signature does not weaken the known-answer test

The file was signed in 2021 with `rsa-sha1` over a `sha1` digest, and **this tree emits SHA-256 and RSA-SHA256**
instead: the example's algorithms are its age, not the contract (`L20`(g)). That is why
`CsdCfdiCancellationSealerTest` pins the algorithm URIs the sealer writes and validates its own round trip with
the JDK's secure validation **on** — a regression back to SHA-1 fails there, and cannot hide behind this file.

What this file answers is a different and stronger question: **does this tree read the format the way its author
wrote it?** A digest is a digest whatever its algorithm, and a canonicalisation either reproduces the bytes the
signer saw or it does not, so validating this file proves the namespace, the empty `Reference` URI, the single
enveloped transform and the inclusive C14N in the suite are the vendor's — over a real CSD's signature, produced
by someone else, which no test this tree writes for itself can claim. The suite turns the JDK's secure validation
**off for this file alone**, and asserts that the JDK refuses it while the flag is on: the refusal is what makes
the override a documented exception instead of a habit.

The file also states the two facts a renderer can get wrong silently: `Fecha="2021-12-22T09:04:06"` is local time
with **no offset**, and every UUID is **upper case**, which is the form `CfdiCancellationRequestBuilder` writes even
though `java.util.UUID#toString()` lower-cases.
