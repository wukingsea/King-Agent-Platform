package com.king.ai.knowledge;

import com.king.ai.agent.mapper.AgentKnowledgeMapper;
import com.king.ai.common.BizException;
import com.king.ai.knowledge.entity.KnowledgeBase;
import com.king.ai.knowledge.entity.KnowledgeDoc;
import com.king.ai.knowledge.mapper.KnowledgeBaseMapper;
import com.king.ai.knowledge.mapper.KnowledgeChunkMapper;
import com.king.ai.knowledge.mapper.KnowledgeDocMapper;
import com.king.ai.knowledge.ocr.OcrService;
import com.king.ai.knowledge.rerank.RerankClient;
import com.king.ai.knowledge.vector.VectorStore;
import com.king.ai.model.ModelRuntimeService;
import com.king.ai.model.mapper.SupplierModelMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeServiceUploadValidationTest {

    @Mock
    private KnowledgeBaseMapper baseMapper;
    @Mock
    private KnowledgeDocMapper docMapper;
    @Mock
    private KnowledgeChunkMapper chunkMapper;
    @Mock
    private SupplierModelMapper modelMapper;
    @Mock
    private AgentKnowledgeMapper agentKnowledgeMapper;
    @Mock
    private VectorStore vectorStore;
    @Mock
    private EmbeddingService embeddingService;
    @Mock
    private KnowledgeTextSplitter splitter;
    @Mock
    private ModelRuntimeService modelRuntimeService;
    @Mock
    private KnowledgeIndexQueue indexQueue;
    @Mock
    private JdbcTemplate jdbcTemplate;
    @Mock
    private OcrService ocrService;
    @Mock
    private ObjectProvider<RerankClient> rerankClient;

    @InjectMocks
    private KnowledgeService knowledgeService;

    @Test
    void pdfUploadWithNonPdfContentIsRejected() {
        stubBase();
        MockMultipartFile file = new MockMultipartFile("file", "report.pdf", "application/pdf",
                "this is not a pdf".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> knowledgeService.uploadDoc(7L, 8L, file))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("扩展名");
        verify(docMapper, never()).insert(any(KnowledgeDoc.class));
    }

    @Test
    void pngUploadWithNonImageContentIsRejected() {
        stubBase();
        MockMultipartFile file = new MockMultipartFile("file", "scan.png", "image/png",
                "plain text payload".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> knowledgeService.uploadDoc(7L, 8L, file))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("扩展名");
        verify(docMapper, never()).insert(any(KnowledgeDoc.class));
    }

    @Test
    void textUploadContainingNulBytesIsRejected() {
        stubBase();
        byte[] binary = new byte[]{'h', 'e', 'l', 'l', 'o', 0, 'w', 'o', 'r', 'l', 'd'};
        MockMultipartFile file = new MockMultipartFile("file", "notes.txt", "text/plain", binary);

        assertThatThrownBy(() -> knowledgeService.uploadDoc(7L, 8L, file))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("扩展名");
        verify(docMapper, never()).insert(any(KnowledgeDoc.class));
    }

    @Test
    void validTextUploadPassesContentCheck() {
        stubBase();
        MockMultipartFile file = new MockMultipartFile("file", "notes.txt", "text/plain",
                "hello knowledge base".getBytes(StandardCharsets.UTF_8));

        assertThatCode(() -> knowledgeService.uploadDoc(7L, 8L, file))
                .doesNotThrowAnyException();
        verify(docMapper).insert(any(KnowledgeDoc.class));
    }

    private void stubBase() {
        KnowledgeBase base = new KnowledgeBase();
        base.setId(8L);
        base.setSpaceId(7L);
        when(baseMapper.selectById(8L)).thenReturn(base);
    }
}
