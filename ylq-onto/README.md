# Yangliuqing Ontology (YLQ-Onto) — w3id.org permanent identifier

## Identifier

**Base IRI**: `https://w3id.org/ylq-onto/`

## Purpose

This identifier serves as the namespace for the Yangliuqing Ontology
(YLQ-Onto), a domain ontology for the knowledge organization of
Chinese woodblock New Year prints. It implements the
Material–Process–Semantics (M-P-S) triadic symbiosis model and reuses
CIDOC-CRM and SKOS for semantic interoperability.

## Resource

The ontology is permanently archived on Zenodo:

- DOI: **10.5281/zenodo.[YOUR_ZENODO_ID]**
- License: CC BY 4.0
- Files: `01_YLQ-Onto.ttl` (Turtle), `01_YLQ-Onto.owl` (RDF/XML)

## Content negotiation

The redirect rules in `.htaccess` perform 303 See Other redirects:

| Accept header | Target |
|---|---|
| `text/turtle` | `01_YLQ-Onto.ttl` on Zenodo |
| `application/rdf+xml` | `01_YLQ-Onto.owl` on Zenodo |
| `text/html` (browser, default) | Zenodo record landing page |

Term-level IRIs of the form `https://w3id.org/ylq-onto/ClassName`
redirect to the Turtle file with the class name as a hash fragment.

## Citation

If you use this ontology, please cite:

> [Author]. ([Year]). "Knowledge Organization of Yangliuqing Woodblock
> New Year Prints: A Material–Process–Semantics Ontology for
> Craft-Type Intangible Cultural Heritage." *Knowledge Organization*
> [Vol]([Issue]): [Pages]. DOI: 10.31083/[KO_DOI]

## Contact

- **Maintainer**: [wang]
- **Email**: [wcg46888@gmail.com]
- **ORCID**: [XXXXXX]
- **Affiliation**: [College of Art and Design, Tianjin Normal University, Tianjin, China]
