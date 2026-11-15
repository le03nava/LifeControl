# Solución Factible's published stamping answers

Both files in this directory are **the vendor's own example responses**, committed unmodified so the
adapter's suite is pinned against a real serialization and never against one this tree invented. They were
fetched once, at authoring time; no test touches the network — the suite serves them from a loopback
`com.sun.net.httpserver.HttpServer` on port `0`.

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
