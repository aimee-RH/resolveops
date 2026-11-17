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
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.*;

class RunbookEvidenceSearchTest {
    @Test
    void allowlistIsPassedToStorageAndEvidenceRetainsSource() {
        var retriever = mock(PgVectorRetrieverService.class);
        var metadata = mock(ChunkMetadataResolver.class);
        when(retriever.retrieve(any(RetrieveRequest.class))).thenReturn(List.of(RetrievedChunk.builder()
                .id("chunk-1").collectionName("support_public").text("Paid activation SOP").score(0.9f).build()));
        when(metadata.resolve(anyCollection())).thenReturn(Map.of("chunk-1", new ChunkMetadataResolver.ChunkMeta("doc-1", 0, "Activation")));
        var service = new RunbookEvidenceSearchService(retriever, metadata, List.of("support_public"));
        var result = service.search(" activation ", 3);
        var request = ArgumentCaptor.forClass(RetrieveRequest.class);
        verify(retriever).retrieve(request.capture());
        assertThat(request.getValue().getEffectiveCollectionNames()).containsExactly("support_public");
        assertThat(result.get(0).docId()).isEqualTo("doc-1");
        assertThat(result.get(0).contentHash()).hasSize(64);
    }

    @Test
    void absentScopeNeverFallsBackToAllCollections() {
        var retriever = mock(PgVectorRetrieverService.class);
        var service = new RunbookEvidenceSearchService(retriever, mock(ChunkMetadataResolver.class), List.of());
        assertThatThrownBy(() -> service.search("activation", 3)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(retriever);
    }

    @Test
    void storageFailureAndOutOfScopeResultsAreNotReportedAsNoDocuments() {
        var retriever = mock(PgVectorRetrieverService.class);
        var metadata = mock(ChunkMetadataResolver.class);
        var service = new RunbookEvidenceSearchService(retriever, metadata, List.of("support_public"));
        when(retriever.retrieve(any(RetrieveRequest.class))).thenThrow(new IllegalStateException("storage down"));
        assertThatThrownBy(() -> service.search("activation", 3)).isInstanceOf(IllegalStateException.class);
        when(retriever.retrieve(any(RetrieveRequest.class))).thenReturn(List.of(RetrievedChunk.builder()
                .id("private").collectionName("other_customer").text("private").build()));
        assertThatThrownBy(() -> service.search("activation", 3)).hasMessage("Runbook retrieval scope mismatch");
        verifyNoInteractions(metadata);
    }
}
