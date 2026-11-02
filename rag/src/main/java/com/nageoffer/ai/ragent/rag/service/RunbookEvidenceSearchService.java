/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.rag.service;

import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.knowledge.service.impl.ChunkMetadataResolver;
import com.nageoffer.ai.ragent.rag.core.retrieval.RetrieveRequest;
import com.nageoffer.ai.ragent.rag.core.vector.PgVectorRetrieverService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

@Service
@ConditionalOnProperty(name = "rag.vector.type", havingValue = "pg")
public class RunbookEvidenceSearchService {
    public record ChunkEvidence(String docId, String chunkId, Integer chunkIndex, String title,
                                String collection, String text, String contentHash, boolean truncated) { }
    private final PgVectorRetrieverService retriever;
    private final ChunkMetadataResolver metadata;
    private final List<String> collections;

    public RunbookEvidenceSearchService(PgVectorRetrieverService retriever, ChunkMetadataResolver metadata,
                                        @Value("${support.runbook-collections:}") List<String> collections) {
        this.retriever = retriever;
        this.metadata = metadata;
        this.collections = collections.stream().filter(name -> !name.isBlank()).distinct().toList();
        if (this.collections.size() > 10 || this.collections.stream().anyMatch(name -> !name.matches("[A-Za-z0-9_][A-Za-z0-9_-]{0,99}")))
            throw new IllegalArgumentException("Invalid support runbook collections");
    }

    public List<ChunkEvidence> search(String query, int topK) {
        if (query == null || query.isBlank() || query.length() > 2000 || topK < 1 || topK > 5)
            throw new IllegalArgumentException("Invalid runbook query");
        if (collections.isEmpty()) throw new IllegalStateException("Support runbooks are not configured");
        // A deliberately narrow path: PG applies the allowlist in SQL; no intent/global/web fallback.
        List<RetrievedChunk> chunks = retriever.retrieve(RetrieveRequest.builder()
                .query(query.strip()).collectionNames(collections).topK(topK).build());
        if (chunks.stream().anyMatch(chunk -> !collections.contains(chunk.getCollectionName())))
            throw new IllegalStateException("Runbook retrieval scope mismatch");
        var meta = metadata.resolve(chunks.stream().map(RetrievedChunk::getId).toList());
        return chunks.stream().sorted(RetrievedChunk.BY_SCORE_DESC).limit(topK).map(chunk -> {
            var source = meta.get(chunk.getId());
            if (source == null || source.docId() == null || chunk.getText() == null)
                throw new IllegalStateException("Runbook metadata unavailable");
            String text = chunk.getText();
            return new ChunkEvidence(source.docId(), chunk.getId(), source.chunkIndex(),
                    source.docName() == null ? source.docId() : source.docName(), chunk.getCollectionName(),
                    text.substring(0, Math.min(6000, text.length())), hash(text), text.length() > 6000);
        }).toList();
    }

    private static String hash(String content) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
