# ADR 001: scoped runbook retrieval before broader RAG orchestration

The initial support path reuses Ragent's PostgreSQL vector retriever and authoritative chunk metadata resolver. It requires an explicit collection allowlist, returns raw chunks and hashes, and propagates storage failures. There is no query rewrite, intent fallback, web search, hybrid fusion or answer-synthesis call in this path.

The broader design considered extending every retrieval channel and fallback. A narrow PG-only entry is easier to verify for the first public-runbook corpus and leaves the general Ragent pipeline unchanged. Existing upstream retriever tests cover parameterized collection filtering; the new adapter tests verify scope propagation and fail-closed behavior. Live embedding, pgvector ingestion and model use remain G3 requirements.

This is a deliberate MVP constraint. Adding another retrieval backend or private tenant knowledge requires its own access-before-retrieval tests. Do not claim hybrid retrieval or relevance improvements from this extension.
