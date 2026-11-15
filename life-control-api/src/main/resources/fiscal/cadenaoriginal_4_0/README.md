# The SAT's official `cadena original` transform for CFDI 4.0

The `Sello` of a CFDI 4.0 is an RSA signature over the SAT's **cadena original**, and that string is
produced by transforming the document with the authority's own XSLT. Writing it by hand is the one thing
`L12(j)` of `odd/tasks/cfdi-fiscal-document.md` forbids: the shape of a fiscal document is a fact to look
up, never one to recall. So the stylesheet is committed here, verbatim, and the sealer transforms with it.

## Provenance of `cadenaoriginal_4_0.xslt`

| Fact | Value |
| --- | --- |
| Source | <http://www.sat.gob.mx/sitio_internet/cfd/4/cadenaoriginal_4_0/cadenaoriginal_4_0.xslt> |
| Retrieved | 2026-11-15, once, at authoring time |
| HTTP status | `200` |
| Bytes | `21324` |
| SHA-256 | `b0559b380e73b850ca8a3da53b077a6051a09d87068d8d98e82dfd4acfba7565` |
| Modified | **No.** Not one byte, not one line ending, not one comment. |

`FiscalCadenaOriginalTest` asserts that hash, so the file cannot drift silently: a stylesheet swapped for
another one compiles, transforms and produces a `cadena original` the authority refuses — the failure this
pin exists to make visible in a test instead of at the PAC.

## Provenance of `includes/`

The stylesheet is not self-contained. It declares `version="2.0"` and **statically includes 33 other
stylesheets** by absolute URL, all of them under `www.sat.gob.mx` — `utilerias.xslt`, which defines the
`Requerido` and `Opcional` templates the document template calls for every attribute, plus one module per
complemento the authority defines. An `xsl:include` is resolved at compile time, so Saxon cannot compile
this stylesheet offline with any of them missing: a resolver that guessed, stubbed or skipped one would
change the transform this file exists to keep exact.

They are therefore committed too, verbatim, flattened to their basenames (all 33 are distinct — verified, and a
test fails the moment the stylesheet names an include this set does not carry). The fetch walked the includes
recursively and found no further ones: 34 documents, `225339` bytes in total.

| Local file | Source URL | Bytes | SHA-256 |
| --- | --- | --- | --- |
| `includes/utilerias.xslt` | <http://www.sat.gob.mx/sitio_internet/cfd/2/cadenaoriginal_2_0/utilerias.xslt> | 920 | `1f7e9c9c04b2239cdf0928e6471d4f48165535c4d4d3ea445ec310b65cdf6c2c` |
| `includes/donat11.xslt` | <http://www.sat.gob.mx/sitio_internet/cfd/donat/donat11.xslt> | 994 | `608a65b07c5841857562026c244c81f9cf3ddd32ff1d28bebab347bb4230fee3` |
| `includes/divisas.xslt` | <http://www.sat.gob.mx/sitio_internet/cfd/divisas/divisas.xslt> | 707 | `64e24ea80b61f6a48ab242875e66877a49449286140187a668aa88241464640e` |
| `includes/implocal.xslt` | <http://www.sat.gob.mx/sitio_internet/cfd/implocal/implocal.xslt> | 1827 | `f99e91c983b7a43b72dc41129afca7342a30e6e45de71b34f7d4e5b6154439d4` |
| `includes/leyendasFisc.xslt` | <http://www.sat.gob.mx/sitio_internet/cfd/leyendasFiscales/leyendasFisc.xslt> | 1410 | `1588e059a3fdfc29bcca4de27f99dcf52abbaa7d815f428f2353a3105f52328c` |
| `includes/pfic.xslt` | <http://www.sat.gob.mx/sitio_internet/cfd/pfic/pfic.xslt> | 1010 | `5f18f835cf6007e92b156ad4cc65ab9167becdcfb1742f6cd655fd2f173e8b1b` |
| `includes/TuristaPasajeroExtranjero.xslt` | <http://www.sat.gob.mx/sitio_internet/cfd/TuristaPasajeroExtranjero/TuristaPasajeroExtranjero.xslt> | 1931 | `5a65dd5bd8d340fdc547b92ff2797199a97674bf2c78feacb69186c91338a68b` |
| `includes/nomina12.xslt` | <http://www.sat.gob.mx/sitio_internet/cfd/nomina/nomina12.xslt> | 17105 | `4130bdb7f62d0987caa5af754ca0704c9e2693e3b454adfff02ba6146d673a08` |
| `includes/cfdiregistrofiscal.xslt` | <http://www.sat.gob.mx/sitio_internet/cfd/cfdiregistrofiscal/cfdiregistrofiscal.xslt> | 823 | `6f47783bfeafa3920cbb452a3adee0239efd2250c13b9d91d242930416d1dee2` |
| `includes/pagoenespecie.xslt` | <http://www.sat.gob.mx/sitio_internet/cfd/pagoenespecie/pagoenespecie.xslt> | 1484 | `685854e25f091ebcb95a4865a7df80630949b2f400e47676ff8dfa2f3019a404` |
| `includes/aerolineas.xslt` | <http://www.sat.gob.mx/sitio_internet/cfd/aerolineas/aerolineas.xslt> | 1912 | `b197afab9535802b04d5e614a2320c10b8b67180e97f120b51d7bb60e61e028d` |
| `includes/valesdedespensa.xslt` | <http://www.sat.gob.mx/sitio_internet/cfd/valesdedespensa/valesdedespensa.xslt> | 2806 | `dff69b6c188c1332b6908936767050121571952a09b99f881c39e41773392b92` |
| `includes/notariospublicos.xslt` | <http://www.sat.gob.mx/sitio_internet/cfd/notariospublicos/notariospublicos.xslt> | 11323 | `7d2609b4b2977ace634d022e83f49bb20534bf137d7bdba014a40ee4b85e1d21` |
| `includes/vehiculousado.xslt` | <http://www.sat.gob.mx/sitio_internet/cfd/vehiculousado/vehiculousado.xslt> | 3029 | `683b2a66cfd07d67f3c19c48499692e648079d663475f2f04f14d37131a29235` |
| `includes/servicioparcialconstruccion.xslt` | <http://www.sat.gob.mx/sitio_internet/cfd/servicioparcialconstruccion/servicioparcialconstruccion.xslt> | 2145 | `692f0470dd0fd57e4c6ea87012296b78d67c31b00c3120eaba446d29219b29e4` |
| `includes/renovacionysustitucionvehiculos.xslt` | <http://www.sat.gob.mx/sitio_internet/cfd/renovacionysustitucionvehiculos/renovacionysustitucionvehiculos.xslt> | 6777 | `1cda351ecbe8c4ddb4c6bb6508e7ea9c36b11627317870f04939765274979aba` |
| `includes/certificadodedestruccion.xslt` | <http://www.sat.gob.mx/sitio_internet/cfd/certificadodestruccion/certificadodedestruccion.xslt> | 2882 | `e4b09099c1453b47bb286a69b05e60d5a5bccaaac466a4a2aebf788601f69349` |
| `includes/obrasarteantiguedades.xslt` | <http://www.sat.gob.mx/sitio_internet/cfd/arteantiguedades/obrasarteantiguedades.xslt> | 1603 | `0823ab4e0ad86ffc6b674680981f4471a60c8ab72431b8dedacd08b1e61ba8e8` |
| `includes/ComercioExterior11.xslt` | <http://www.sat.gob.mx/sitio_internet/cfd/ComercioExterior11/ComercioExterior11.xslt> | 7293 | `c96afd3f14e8c1802a653ffe71d7147f2f74eb4f5daf9e0b21afca0b2c80c7f7` |
| `includes/ComercioExterior20.xslt` | <http://www.sat.gob.mx/sitio_internet/cfd/ComercioExterior20/ComercioExterior20.xslt> | 7039 | `b7540d6878c8d1e14b3d7f8a8bf3889dc03b0dfb6dfbfc6d6b30e27a094b8446` |
| `includes/ine11.xslt` | <http://www.sat.gob.mx/sitio_internet/cfd/ine/ine11.xslt> | 1966 | `4098bf77bfee89e870604b7bc96ba4bdb19df78341f5e0cbffdd094eb916bcad` |
| `includes/iedu.xslt` | <http://www.sat.gob.mx/sitio_internet/cfd/iedu/iedu.xslt> | 1244 | `ef5e7e6cc0666cfc67d0c41ad865c2a044947289013ca2add0f89dea7fde667f` |
| `includes/ventavehiculos11.xslt` | <http://www.sat.gob.mx/sitio_internet/cfd/ventavehiculos/ventavehiculos11.xslt> | 1864 | `77a103915ea66d6ade5a23e5db7e3c66467abf744acef5f9ac8cafdc16362c7d` |
| `includes/detallista.xslt` | <http://www.sat.gob.mx/sitio_internet/cfd/detallista/detallista.xslt> | 2176 | `e09906ef78afc077aa3318ca87bf2469e8570f97299d2f0237a2bc81da1e5e12` |
| `includes/ecc12.xslt` | <http://www.sat.gob.mx/sitio_internet/cfd/EstadoDeCuentaCombustible/ecc12.xslt> | 4122 | `ad76f5a5c726df61fe82c1db5e3a189b1026fa0cd87518ea225498e24f4014fb` |
| `includes/consumodeCombustibles11.xslt` | <http://www.sat.gob.mx/sitio_internet/cfd/consumodecombustibles/consumodeCombustibles11.xslt> | 4192 | `f43a77f25aba6ea7ce48769a5c700d2edce78e75914fbe866eafdb816ac2a9c1` |
| `includes/GastosHidrocarburos10.xslt` | <http://www.sat.gob.mx/sitio_internet/cfd/GastosHidrocarburos10/GastosHidrocarburos10.xslt> | 5930 | `dc295079d9884a2afec09d63819eb874b4bede35ca8d4fe713625d0973e78a6b` |
| `includes/IngresosHidrocarburos.xslt` | <http://www.sat.gob.mx/sitio_internet/cfd/IngresosHidrocarburos10/IngresosHidrocarburos.xslt> | 1647 | `8dc9fb24cb47162da3d6fb831a1aa57a12571986eb123adfad2471370d56c10f` |
| `includes/CartaPorte20.xslt` | <http://www.sat.gob.mx/sitio_internet/cfd/CartaPorte/CartaPorte20.xslt> | 27549 | `0f8fa95e65eaf1fbfaba34ee7c0b78396dd04c920b1b54facddd178fad55add5` |
| `includes/Pagos20.xslt` | <http://www.sat.gob.mx/sitio_internet/cfd/Pagos/Pagos20.xslt> | 9827 | `9277a0a5049fbd56e3123e6489ffb16f6ccfbca30943aa7dbd0f969f3b70bd0f` |
| `includes/CartaPorte30.xslt` | <http://www.sat.gob.mx/sitio_internet/cfd/CartaPorte/CartaPorte30.xslt> | 33207 | `3da384b7797ab037c0fc37d79716e0f4eb7704c6a585353aa2d73c6f76892e35` |
| `includes/CartaPorte31.xslt` | <http://www.sat.gob.mx/sitio_internet/cfd/CartaPorte/CartaPorte31.xslt> | 34063 | `6ef3db6a004a7b27014bf8f03b9762f337479f0694525b193eaa9e3ff058fa0f` |
| `includes/hidrocarburospetroliferos.xslt` | <http://www.sat.gob.mx/sitio_internet/cfd/HidrocarburosPetro/hidrocarburospetroliferos.xslt> | 1208 | `b212b9df8119de4278a24d5258b27ac295746b7476cc6aef969a3ad1d6c55a4b` |

Nothing here is fetched at runtime. `CadenaOriginal#include` resolves every include to the file in `includes/`,
and refuses — by name — an include the committed set does not carry, so a dependency added to the transform
cannot be satisfied by the network in one environment and by a stale local file in another.

## What the transform is used for

`seal/CadenaOriginal` compiles this stylesheet once, transforms the seal-less CFDI the builder renders into
the `cadena original`, and `seal/CsdCfdiSealer` signs those UTF-8 bytes with `SHA256withRSA`. The stylesheet is
XSLT 2.0, which the JDK's built-in transformer cannot run — it processes a 2.0 stylesheet with 1.0
semantics and answers a different `cadena original` without failing — so the sealer runs on Saxon-HE,
declared in `build.gradle` with its version.
