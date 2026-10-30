package com.king.ai.knowledge;

import com.king.ai.agent.mapper.AgentKnowledgeMapper;
import com.king.ai.common.BizException;
import com.king.ai.knowledge.dto.KnowledgeDocDetail;
import com.king.ai.knowledge.entity.KnowledgeBase;
import com.king.ai.knowledge.entity.KnowledgeDoc;
import com.king.ai.knowledge.mapper.KnowledgeBaseMapper;
import com.king.ai.knowledge.mapper.KnowledgeChunkMapper;
import com.king.ai.knowledge.mapper.KnowledgeDocMapper;
import com.king.ai.knowledge.ocr.OcrService;
import com.king.ai.knowledge.vector.VectorStore;
import com.king.ai.model.ModelRuntimeService;
import com.king.ai.model.mapper.SupplierModelMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeServiceAclTest {

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

    @InjectMocks
    private KnowledgeService knowledgeService;

    @Test
    void viewerCannotReadRestrictedDocDetail() {
        stubDoc("ADMIN");

        assertThatThrownBy(() -> knowledgeService.docDetail(7L, 8L, 9L, "VIEWER"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("无权访问");
    }

    @Test
    void editorCannotReadAdminOnlyDocDetail() {
        stubDoc("ADMIN");

        assertThatThrownBy(() -> knowledgeService.docDetail(7L, 8L, 9L, "EDITOR"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("无权访问");
    }

    @Test
    void adminCanReadRestrictedDocDetail() {
        stubDoc("ADMIN");

        KnowledgeDocDetail detail = knowledgeService.docDetail(7L, 8L, 9L, "ADMIN");

        assertThat(detail.content()).isEqualTo("secret");
    }

    @Test
    void viewerCannotReadRestrictedChunks() {
        stubDoc("ADMIN");

        assertThatThrownBy(() -> knowledgeService.chunks(7L, 8L, 9L, 1, 20, "VIEWER"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("无权访问");
        verifyNoInteractions(chunkMapper);
    }

    @Test
    void editorCannotRewriteAclOfAdminOnlyDoc() {
        stubDoc("ADMIN");

        assertThatThrownBy(() -> knowledgeService.updateDocAcl(7L, 8L, 9L, List.of("VIEWER"), "EDITOR"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("无权访问");
    }

    @Test
    void listDocsFiltersByCallerReadableRoles() {
        KnowledgeBase base = new KnowledgeBase();
        base.setId(8L);
        base.setSpaceId(7L);
        when(baseMapper.selectById(8L)).thenReturn(base);
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(1L);
        doReturn(List.of()).when(jdbcTemplate)
                .query(anyString(), any(Object[].class), any(RowMapper.class));

        knowledgeService.listDocs(7L, 8L, null, 1, 20, "VIEWER");

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).queryForObject(sqlCaptor.capture(), eq(Long.class), argsCaptor.capture());

        assertThat(sqlCaptor.getValue()).contains("allowed_roles IS NULL", "allowed_roles LIKE");
        assertThat(argsCaptor.getValue())
                .contains("VIEWER", "VIEWER,%", "%,VIEWER", "%,VIEWER,%",
                        "AUDITOR", "AUDITOR,%", "%,AUDITOR", "%,AUDITOR,%")
                .doesNotContain("EDITOR", "EDITOR,%", "%,EDITOR", "%,EDITOR,%");
    }

    private void stubDoc(String allowedRoles) {
        KnowledgeDoc doc = new KnowledgeDoc();
        doc.setId(9L);
        doc.setSpaceId(7L);
        doc.setKnowledgeBaseId(8L);
        doc.setName("薪酬制度");
        doc.setContent("secret");
        doc.setAllowedRoles(allowedRoles);
        when(docMapper.selectById(9L)).thenReturn(doc);
    }
}
