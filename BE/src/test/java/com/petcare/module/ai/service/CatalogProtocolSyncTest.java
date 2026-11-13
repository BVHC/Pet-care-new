package com.petcare.module.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.petcare.module.ai.repository.AiDocumentRepository;
import com.petcare.module.ai.repository.AiDocumentRepository.PublishedProtocol;
import com.petcare.module.catalog.api.CatalogQueryApi;
import com.petcare.module.catalog.api.CatalogQueryApi.ProtocolDose;
import com.petcare.module.customer.api.Species;

/**
 * Bước lưu trữ của {@link CatalogProtocolSync} (docs/adr/0130 #5) ở mức đơn vị: thứ tự đọc và cô lập lỗi. Phần chạy thật
 * trên PostgreSQL nằm ở {@code CatalogProtocolSyncIT}.
 */
class CatalogProtocolSyncTest {

    /** Một dòng của truy vấn {@code findPublishedProtocols} (interface projection). */
    private record Published(Long getId, Long getVaccineTypeId) implements PublishedProtocol {
    }

    private final CatalogQueryApi catalog = mock(CatalogQueryApi.class);
    private final CatalogProtocolWriter writer = mock(CatalogProtocolWriter.class);
    private final AiDocumentRepository docs = mock(AiDocumentRepository.class);
    private final CatalogProtocolSync sync = new CatalogProtocolSync(catalog, writer, docs);

    /**
     * Một lượt khác (bản BE khác) tạo tài liệu của loại 99 trong lúc lượt này đang đọc phác đồ; lượt này đã đọc phác đồ
     * trước khi loại 99 có dòng nên không thấy nó. Nếu lượt này liệt kê tài liệu <b>sau</b> khi đọc phác đồ thì thấy tài
     * liệu mới đó, tưởng loại 99 đã hết phác đồ và lưu trữ nhầm (không tự quay lại được, docs/adr/0130 #5).
     */
    @Test
    void aDocumentCreatedByAConcurrentRunWhileThisRunReadsTheCatalogIsNeverArchivedByThisRun() {
        List<PublishedProtocol> inDatabase = new ArrayList<>();
        when(docs.findPublishedProtocols()).thenAnswer(call -> List.copyOf(inDatabase));
        when(catalog.listActiveProtocols(any())).thenAnswer(call -> {
            inDatabase.add(new Published(7L, 99L));
            return List.of();
        });

        sync.sync();

        verify(writer, never()).archiveRetired(anyLong());
    }

    @Test
    void aFailureToArchiveOneDocumentDoesNotStopTheOthersAndOnlyTheArchivedOnesAreCounted() {
        when(docs.findPublishedProtocols()).thenReturn(List.of(new Published(1L, 10L), new Published(2L, 20L)));
        when(catalog.listActiveProtocols(any())).thenReturn(List.of());
        when(writer.archiveRetired(1L)).thenThrow(new IllegalStateException("IT: lỗi lưu trữ"));
        when(writer.archiveRetired(2L)).thenReturn(true);

        assertThat(sync.sync()).isEqualTo(1);

        verify(writer).archiveRetired(1L);
        verify(writer).archiveRetired(2L);
    }

    @Test
    void aTypeWithAnActiveDoseInAnySpeciesIsNotArchived() {
        when(docs.findPublishedProtocols()).thenReturn(List.of(new Published(1L, 10L), new Published(2L, 20L)));
        when(catalog.listActiveProtocols(any())).thenReturn(List.of());
        when(catalog.listActiveProtocols(Species.CAT))
                .thenReturn(List.of(new ProtocolDose(5L, Species.CAT, 10L, 1, 21, 8, false, true)));
        when(writer.archiveRetired(2L)).thenReturn(true);

        sync.sync();

        verify(writer, never()).archiveRetired(1L);
        verify(writer).archiveRetired(2L);
    }
}
