package com.king.ai.knowledge;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.king.ai.agent.mapper.AgentKnowledgeMapper;
import com.king.ai.common.BizException;
import com.king.ai.common.Ids;
import com.king.ai.common.OptimisticLocks;
import com.king.ai.common.PageQuery;
import com.king.ai.common.PageResult;
import com.king.ai.common.Texts;
import com.king.ai.knowledge.dto.KnowledgeBaseSaveRequest;
import com.king.ai.knowledge.dto.KnowledgeBaseView;
import com.king.ai.knowledge.dto.KnowledgeChunkView;
import com.king.ai.knowledge.dto.KnowledgeDocDetail;
import com.king.ai.knowledge.dto.KnowledgeDocTextRequest;
import com.king.ai.knowledge.dto.KnowledgeDocView;
import com.king.ai.knowledge.dto.KnowledgeSearchResult;
import com.king.ai.knowledge.dto.RetrievedChunk;
import com.king.ai.knowledge.entity.KnowledgeBase;
import com.king.ai.knowledge.entity.KnowledgeChunk;
import com.king.ai.knowledge.entity.KnowledgeDoc;
import com.king.ai.knowledge.mapper.KnowledgeBaseMapper;
import com.king.ai.knowledge.mapper.KnowledgeChunkMapper;
import com.king.ai.knowledge.mapper.KnowledgeDocMapper;
import com.king.ai.knowledge.vector.VectorHit;
import com.king.ai.knowledge.vector.VectorRecord;
import com.king.ai.knowledge.vector.VectorStore;
import com.king.ai.knowledge.vector.VectorStoreException;
import com.king.ai.model.ModelRuntimeService;
import com.king.ai.model.entity.SupplierModel;
import com.king.ai.model.mapper.SupplierModelMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeService {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_PROCESSING = "PROCESSING";
    public static final String STATUS_READY = "READY";
    public static final String STATUS_FAILED = "FAILED";

    private static final int MAX_CONTENT_CHARS = 2_000_000;
    private static final long MAX_FILE_BYTES = 20L * 1024 * 1024;

    private static final RowMapper<KnowledgeDocView> DOC_VIEW_MAPPER = (rs, rowNum) -> new KnowledgeDocView(
            rs.getLong("id"),
            rs.getLong("knowledge_base_id"),
            rs.getString("name"),
            rs.getString("source_type"),
            rs.getInt("chunk_count"),
            rs.getString("status"),
            rs.getString("error_message"),
            rs.getLong("char_count"),
            parseRoles(rs.getString("allowed_roles")),
            rs.getTimestamp("created_at").toLocalDateTime(),
            rs.getTimestamp("updated_at").toLocalDateTime());

    private final KnowledgeBaseMapper baseMapper;
    private final KnowledgeDocMapper docMapper;
    private final KnowledgeChunkMapper chunkMapper;
    private final SupplierModelMapper modelMapper;
    private final AgentKnowledgeMapper agentKnowledgeMapper;
    private final VectorStore vectorStore;
    private final EmbeddingService embeddingService;
    private final KnowledgeTextSplitter splitter;
    private final ModelRuntimeService modelRuntimeService;
    private final KnowledgeIndexQueue indexQueue;
    private final JdbcTemplate jdbcTemplate;

    public PageResult<KnowledgeBaseView> list(Long spaceId, String keyword, PageQuery query) {
        LambdaQueryWrapper<KnowledgeBase> wrapper = new LambdaQueryWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getSpaceId, spaceId);
        if (!Texts.isBlank(keyword)) {
            String kw = keyword.trim();
            wrapper.and(w -> w.like(KnowledgeBase::getName, kw).or().like(KnowledgeBase::getDescription, kw));
        }
        wrapper.orderByDesc(KnowledgeBase::getId);
        Page<KnowledgeBase> result = baseMapper.selectPage(new Page<>(query.page(), query.size()), wrapper);
        List<KnowledgeBase> bases = result.getRecords();
        if (bases.isEmpty()) {
            return PageResult.of(List.of(), result.getTotal(), query.page(), query.size());
        }
        List<Long> baseIds = bases.stream().map(KnowledgeBase::getId).toList();
        Map<Long, String> modelNames = modelNames(bases.stream().map(KnowledgeBase::getEmbeddingModelId).toList());
        Map<Long, Long> docCounts = countByBase("king_ai_knowledge_doc", spaceId, baseIds);
        Map<Long, Long> chunkCounts = countByBase("king_ai_knowledge_chunk", spaceId, baseIds);
        List<KnowledgeBaseView> views = bases.stream()
                .map(base -> view(base, modelNames.getOrDefault(base.getEmbeddingModelId(), "-"),
                        docCounts.getOrDefault(base.getId(), 0L), chunkCounts.getOrDefault(base.getId(), 0L)))
                .toList();
        return PageResult.of(views, result.getTotal(), query.page(), query.size());
    }

    public KnowledgeBase require(Long spaceId, Long id) {
        if (id == null) {
            throw BizException.badRequest("缺少知识库 ID");
        }
        KnowledgeBase base = baseMapper.selectById(id);
        if (base == null || !Objects.equals(base.getSpaceId(), spaceId)) {
            throw BizException.notFound("知识库不存在");
        }
        return base;
    }

    public KnowledgeBaseView detail(Long spaceId, Long id) {
        KnowledgeBase base = require(spaceId, id);
        long docs = docMapper.selectCount(new LambdaQueryWrapper<KnowledgeDoc>()
                .eq(KnowledgeDoc::getKnowledgeBaseId, id));
        long chunks = chunkMapper.selectCount(new LambdaQueryWrapper<KnowledgeChunk>()
                .eq(KnowledgeChunk::getKnowledgeBaseId, id));
        return view(base, modelName(base.getEmbeddingModelId()), docs, chunks);
    }

    @Transactional
    public KnowledgeBaseView create(Long spaceId, KnowledgeBaseSaveRequest request) {
        ModelRuntimeService.ResolvedModel resolved = modelRuntimeService.resolveEmbedding(
                spaceId, request.embeddingModelId());
        ChunkSettings settings = settings(request);
        requireUniqueName(spaceId, request.name().trim(), null);
        KnowledgeBase base = new KnowledgeBase();
        base.setSpaceId(spaceId);
        base.setName(request.name().trim());
        base.setDescription(Texts.trimTo(request.description(), 500));
        base.setEmbeddingModelId(request.embeddingModelId());
        base.setEmbeddingDim(resolved.model().getDimension());
        base.setVectorCollection("king_kb_" + Ids.publicId().substring(0, 24));
        base.setChunkSize(settings.chunkSize());
        base.setChunkOverlap(settings.overlap());
        base.setTopK(settings.topK());
        base.setStatus(request.status() == null || request.status() == 0 ? 0 : 1);
        baseMapper.insert(base);
        try {
            vectorStore.ensureCollection(base);
        } catch (VectorStoreException ex) {
            throw new BizException(500, "向量集合初始化失败：" + ex.getMessage());
        }
        return view(base, resolved.model().getName(), 0, 0);
    }

    @Transactional
    public KnowledgeBaseView update(Long spaceId, Long id, KnowledgeBaseSaveRequest request) {
        KnowledgeBase base = require(spaceId, id);
        ChunkSettings settings = settings(request);
        requireUniqueName(spaceId, request.name().trim(), id);
        boolean modelChanged = !Objects.equals(base.getEmbeddingModelId(), request.embeddingModelId());
        String oldCollection = base.getVectorCollection();
        Integer oldDimension = base.getEmbeddingDim();
        if (modelChanged) {
            long docs = docMapper.selectCount(new LambdaQueryWrapper<KnowledgeDoc>()
                    .eq(KnowledgeDoc::getKnowledgeBaseId, id));
            if (docs > 0) {
                throw BizException.conflict("知识库已有文档，不能更换向量模型");
            }
            ModelRuntimeService.ResolvedModel resolved = modelRuntimeService.resolveEmbedding(
                    spaceId, request.embeddingModelId());
            base.setEmbeddingModelId(request.embeddingModelId());
            base.setEmbeddingDim(resolved.model().getDimension());
            base.setVectorCollection("king_kb_" + Ids.publicId().substring(0, 24));
        }
        base.setName(request.name().trim());
        base.setDescription(Texts.trimTo(request.description(), 500));
        base.setChunkSize(settings.chunkSize());
        base.setChunkOverlap(settings.overlap());
        base.setTopK(settings.topK());
        base.setStatus(request.status() == null || request.status() == 0 ? 0 : 1);
        OptimisticLocks.requireUpdated(baseMapper.updateById(base), "知识库");
        if (modelChanged) {
            if (!Texts.isBlank(oldCollection)) {
                KnowledgeBase old = new KnowledgeBase();
                old.setId(base.getId());
                old.setSpaceId(spaceId);
                old.setVectorCollection(oldCollection);
                old.setEmbeddingDim(oldDimension);
                try {
                    vectorStore.deleteCollection(old);
                } catch (RuntimeException ex) {
                    log.warn("Failed to drop old collection {}: {}", oldCollection, ex.getMessage());
                }
            }
            try {
                vectorStore.ensureCollection(base);
            } catch (VectorStoreException ex) {
                throw new BizException(500, "向量集合初始化失败：" + ex.getMessage());
            }
        }
        long docs = docMapper.selectCount(new LambdaQueryWrapper<KnowledgeDoc>()
                .eq(KnowledgeDoc::getKnowledgeBaseId, id));
        long chunks = chunkMapper.selectCount(new LambdaQueryWrapper<KnowledgeChunk>()
                .eq(KnowledgeChunk::getKnowledgeBaseId, id));
        return view(base, modelName(base.getEmbeddingModelId()), docs, chunks);
    }

    @Transactional
    public void delete(Long spaceId, Long id) {
        KnowledgeBase base = require(spaceId, id);
        long bindings = agentKnowledgeMapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.king.ai.agent.entity.AgentKnowledge>()
                        .eq(com.king.ai.agent.entity.AgentKnowledge::getKnowledgeBaseId, id));
        if (bindings > 0) {
            throw BizException.conflict("知识库已绑定 " + bindings + " 个智能体，请先解除绑定");
        }
        try {
            vectorStore.deleteCollection(base);
        } catch (RuntimeException ex) {
            log.warn("Failed to drop vector collection for kb {}: {}", id, ex.getMessage());
        }
        jdbcTemplate.update("DELETE FROM king_ai_chunk_vector WHERE knowledge_base_id = ?", id);
        jdbcTemplate.update("DELETE FROM king_ai_knowledge_chunk WHERE knowledge_base_id = ?", id);
        jdbcTemplate.update("DELETE FROM king_ai_knowledge_index_job WHERE doc_id IN "
                + "(SELECT id FROM king_ai_knowledge_doc WHERE knowledge_base_id = ?)", id);
        jdbcTemplate.update("DELETE FROM king_ai_knowledge_doc WHERE knowledge_base_id = ?", id);
        baseMapper.deleteById(id);
    }

    public PageResult<KnowledgeDocView> listDocs(Long spaceId, Long knowledgeBaseId, String keyword,
                                                 int pageNo, int size) {
        require(spaceId, knowledgeBaseId);
        int page = Math.max(1, pageNo);
        int pageSize = Math.max(1, Math.min(size, 100));
        StringBuilder where = new StringBuilder(" WHERE knowledge_base_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(knowledgeBaseId);
        if (!Texts.isBlank(keyword)) {
            where.append(" AND name LIKE ?");
            args.add("%" + keyword.trim() + "%");
        }
        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM king_ai_knowledge_doc" + where, Long.class, args.toArray());
        String sql = "SELECT id, knowledge_base_id, name, source_type, chunk_count, status, error_message, "
                + "CHAR_LENGTH(content) AS char_count, allowed_roles, created_at, updated_at "
                + "FROM king_ai_knowledge_doc" + where + " ORDER BY id DESC LIMIT ? OFFSET ?";
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(pageSize);
        pageArgs.add((long) (page - 1) * pageSize);
        List<KnowledgeDocView> views = jdbcTemplate.query(sql, pageArgs.toArray(), DOC_VIEW_MAPPER);
        return PageResult.of(views, total == null ? 0 : total, page, pageSize);
    }

    @Transactional
    public KnowledgeDocView updateDocAcl(Long spaceId, Long knowledgeBaseId, Long docId,
                                         List<String> allowedRoles) {
        KnowledgeDoc doc = requireDoc(spaceId, knowledgeBaseId, docId);
        doc.setAllowedRoles(normalizeRoles(allowedRoles));
        OptimisticLocks.requireUpdated(docMapper.updateById(doc), "知识库文档");
        int chars = doc.getContent() == null ? 0 : doc.getContent().length();
        return view(doc, chars);
    }

    public KnowledgeDocView createTextDoc(Long spaceId, Long knowledgeBaseId, KnowledgeDocTextRequest request) {
        require(spaceId, knowledgeBaseId);
        String content = request.content().trim();
        if (content.length() > MAX_CONTENT_CHARS) {
            throw BizException.badRequest("文档内容不能超过 200 万字符");
        }
        KnowledgeDoc doc = new KnowledgeDoc();
        doc.setSpaceId(spaceId);
        doc.setKnowledgeBaseId(knowledgeBaseId);
        doc.setName(request.name().trim());
        doc.setSourceType("TEXT");
        doc.setContent(content);
        doc.setChunkCount(0);
        doc.setStatus(STATUS_PENDING);
        docMapper.insert(doc);
        scheduleIndex(doc.getId());
        return view(doc, content.length());
    }

    public KnowledgeDocView uploadDoc(Long spaceId, Long knowledgeBaseId, MultipartFile file) {
        require(spaceId, knowledgeBaseId);
        if (file == null || file.isEmpty()) {
            throw BizException.badRequest("请选择要上传的文件");
        }
        if (file.getSize() > MAX_FILE_BYTES) {
            throw BizException.badRequest("文件大小不能超过 20MB");
        }
        String originalName = Texts.blankToDefault(file.getOriginalFilename(), "未命名文档");
        originalName = originalName.replace('\\', '/');
        int slash = originalName.lastIndexOf('/');
        if (slash >= 0) {
            originalName = originalName.substring(slash + 1);
        }
        originalName = Texts.trimTo(originalName, 200);
        String lower = originalName.toLowerCase(Locale.ROOT);
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException ex) {
            throw BizException.badRequest("文件读取失败");
        }
        String content;
        String sourceType;
        if (lower.endsWith(".pdf")) {
            content = extractPdf(bytes);
            sourceType = "PDF";
        } else if (lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".markdown")) {
            content = new String(bytes, StandardCharsets.UTF_8).replace("\uFEFF", "");
            sourceType = "TEXT";
        } else {
            throw BizException.badRequest("仅支持 TXT、Markdown 和 PDF 文件");
        }
        content = content == null ? "" : content.trim();
        if (content.isEmpty()) {
            throw BizException.badRequest("未能从文件中提取到文本内容");
        }
        if (content.length() > MAX_CONTENT_CHARS) {
            throw BizException.badRequest("文档内容不能超过 200 万字符");
        }
        KnowledgeDoc doc = new KnowledgeDoc();
        doc.setSpaceId(spaceId);
        doc.setKnowledgeBaseId(knowledgeBaseId);
        doc.setName(originalName);
        doc.setSourceType(sourceType);
        doc.setContent(content);
        doc.setChunkCount(0);
        doc.setStatus(STATUS_PENDING);
        docMapper.insert(doc);
        scheduleIndex(doc.getId());
        return view(doc, content.length());
    }

    public KnowledgeDocDetail docDetail(Long spaceId, Long knowledgeBaseId, Long docId) {
        KnowledgeDoc doc = requireDoc(spaceId, knowledgeBaseId, docId);
        return new KnowledgeDocDetail(doc.getId(), doc.getKnowledgeBaseId(), doc.getName(), doc.getSourceType(),
                doc.getContent(), doc.getChunkCount(), doc.getStatus(), doc.getErrorMessage(),
                doc.getCreatedAt(), doc.getUpdatedAt());
    }

    public PageResult<KnowledgeChunkView> chunks(Long spaceId, Long knowledgeBaseId, Long docId,
                                                 int pageNo, int size) {
        requireDoc(spaceId, knowledgeBaseId, docId);
        int page = Math.max(1, pageNo);
        int pageSize = Math.max(1, Math.min(size, 100));
        Page<KnowledgeChunk> result = chunkMapper.selectPage(new Page<>(page, pageSize),
                new LambdaQueryWrapper<KnowledgeChunk>()
                        .eq(KnowledgeChunk::getDocId, docId)
                        .orderByAsc(KnowledgeChunk::getChunkIndex));
        List<KnowledgeChunkView> views = result.getRecords().stream()
                .map(chunk -> new KnowledgeChunkView(chunk.getId(), chunk.getChunkIndex(),
                        chunk.getCharCount(), chunk.getContent()))
                .toList();
        return PageResult.of(views, result.getTotal(), page, pageSize);
    }

    public KnowledgeDocView reindex(Long spaceId, Long knowledgeBaseId, Long docId) {
        KnowledgeDoc doc = requireDoc(spaceId, knowledgeBaseId, docId);
        if (STATUS_PROCESSING.equals(doc.getStatus()) && doc.getUpdatedAt() != null
                && doc.getUpdatedAt().isAfter(LocalDateTime.now().minusMinutes(10))) {
            throw BizException.conflict("文档正在处理中，请稍后再试");
        }
        updateDocState(docId, STATUS_PENDING, 0, "");
        scheduleIndex(docId);
        doc.setStatus(STATUS_PENDING);
        doc.setChunkCount(0);
        int chars = doc.getContent() == null ? 0 : doc.getContent().length();
        return view(doc, chars);
    }

    @Transactional
    public void deleteDoc(Long spaceId, Long knowledgeBaseId, Long docId) {
        KnowledgeBase base = require(spaceId, knowledgeBaseId);
        requireDoc(spaceId, knowledgeBaseId, docId);
        indexQueue.remove(docId);
        try {
            vectorStore.deleteDoc(base, docId);
        } catch (RuntimeException ex) {
            log.warn("Failed to delete vectors for doc {}: {}", docId, ex.getMessage());
        }
        jdbcTemplate.update("DELETE FROM king_ai_chunk_vector WHERE doc_id = ?", docId);
        chunkMapper.delete(new LambdaQueryWrapper<KnowledgeChunk>().eq(KnowledgeChunk::getDocId, docId));
        docMapper.deleteById(docId);
    }

    public void indexDocument(Long docId) {
        KnowledgeDoc doc = docMapper.selectById(docId);
        if (doc == null) {
            throw new PermanentIndexException("文档不存在");
        }
        KnowledgeBase base = baseMapper.selectById(doc.getKnowledgeBaseId());
        if (base == null) {
            throw new PermanentIndexException("知识库不存在");
        }
        if (Texts.isBlank(doc.getContent())) {
            throw new PermanentIndexException("文档内容为空，无法生成知识片段");
        }
        updateDocState(docId, STATUS_PROCESSING, 0, "");
        try {
            vectorStore.ensureCollection(base);
            vectorStore.deleteDoc(base, docId);
            chunkMapper.delete(new LambdaQueryWrapper<KnowledgeChunk>().eq(KnowledgeChunk::getDocId, docId));
            List<String> pieces = splitter.split(doc.getContent(), base.getChunkSize(), base.getChunkOverlap());
            if (pieces.isEmpty()) {
                throw new PermanentIndexException("文档内容为空，无法生成知识片段");
            }
            List<KnowledgeChunk> chunks = new ArrayList<>(pieces.size());
            for (int i = 0; i < pieces.size(); i++) {
                String piece = pieces.get(i);
                KnowledgeChunk chunk = new KnowledgeChunk();
                chunk.setSpaceId(base.getSpaceId());
                chunk.setKnowledgeBaseId(base.getId());
                chunk.setDocId(docId);
                chunk.setChunkIndex(i);
                chunk.setContent(piece);
                chunk.setCharCount(piece.length());
                chunkMapper.insert(chunk);
                chunks.add(chunk);
            }
            List<float[]> embeddings = embeddingService.embed(base, pieces);
            List<VectorRecord> records = new ArrayList<>(chunks.size());
            for (int i = 0; i < chunks.size(); i++) {
                records.add(new VectorRecord(chunks.get(i).getId(), docId, embeddings.get(i)));
            }
            vectorStore.upsert(base, records);
            updateDocState(docId, STATUS_READY, chunks.size(), "");
            log.info("Knowledge doc indexed: docId={}, chunks={}", docId, chunks.size());
        } catch (PermanentIndexException ex) {
            cleanupIndex(docId, base);
            throw ex;
        } catch (Exception ex) {
            cleanupIndex(docId, base);
            throw new IllegalStateException(Texts.oneLine(
                    Texts.blankToDefault(ex.getMessage(), ex.getClass().getSimpleName()), 900), ex);
        }
    }

    private void cleanupIndex(Long docId, KnowledgeBase base) {
        try {
            vectorStore.deleteDoc(base, docId);
        } catch (RuntimeException ignored) {
        }
        try {
            chunkMapper.delete(new LambdaQueryWrapper<KnowledgeChunk>().eq(KnowledgeChunk::getDocId, docId));
        } catch (RuntimeException ignored) {
        }
    }

    public void markDocFailed(Long docId, String error) {
        updateDocState(docId, STATUS_FAILED, 0, error);
    }

    public void markDocPendingRetry(Long docId, String error) {
        updateDocState(docId, STATUS_PENDING, 0, error);
    }

    public List<KnowledgeSearchResult> search(Long spaceId, Long knowledgeBaseId, String query,
                                              Integer topK, String role) {
        KnowledgeBase base = require(spaceId, knowledgeBaseId);
        int limit = topK == null ? base.getTopK() : topK;
        return retrieve(base, query, limit, role).stream()
                .map(chunk -> new KnowledgeSearchResult(chunk.chunkId(), chunk.docId(), chunk.docName(),
                        chunk.chunkIndex(), chunk.content(), chunk.score()))
                .toList();
    }

    public List<RetrievedChunk> retrieve(KnowledgeBase base, String query, int limit, String role) {
        if (Texts.isBlank(query)) {
            return List.of();
        }
        if (base.getStatus() != null && base.getStatus() != 0) {
            return List.of();
        }
        float[] vector = embeddingService.embedQuery(base, query);
        return retrieve(base, query, limit, role, vector);
    }

    /**
     * Hybrid retrieval: vector similarity combined with a lexical pass, then filtered by
     * document ACL and ranked by the merged score. The query vector is supplied by the caller
     * so multiple knowledge bases sharing one embedding model only pay for one embedding call.
     */
    private List<RetrievedChunk> retrieve(KnowledgeBase base, String query, int limit, String role,
                                          float[] vector) {
        int topK = Math.max(1, Math.min(limit, 20));
        List<VectorHit> hits = vectorStore.search(base, vector, topK);
        LinkedHashMap<Long, RetrievedChunk> candidates = new LinkedHashMap<>();
        if (!hits.isEmpty()) {
            List<Long> chunkIds = hits.stream().map(VectorHit::chunkId).distinct().toList();
            Map<Long, KnowledgeChunk> chunks = chunkMapper.selectBatchIds(chunkIds).stream()
                    .collect(Collectors.toMap(KnowledgeChunk::getId, chunk -> chunk));
            Map<Long, KnowledgeDoc> docs = docMap(chunks.values().stream().map(KnowledgeChunk::getDocId).toList());
            for (VectorHit hit : hits) {
                KnowledgeChunk chunk = chunks.get(hit.chunkId());
                if (chunk == null || !Objects.equals(chunk.getKnowledgeBaseId(), base.getId())) {
                    continue;
                }
                KnowledgeDoc doc = docs.get(chunk.getDocId());
                if (doc != null && !canRead(doc, role)) {
                    continue;
                }
                candidates.put(chunk.getId(), new RetrievedChunk(chunk.getId(), chunk.getDocId(),
                        doc == null ? "未知文档" : doc.getName(), base.getId(), base.getName(),
                        chunk.getChunkIndex(), chunk.getContent(), hit.score()));
            }
        }

        String lexicalTerm = lexicalTerm(query);
        if (!lexicalTerm.isEmpty()) {
            try {
                List<KnowledgeChunk> lexical = chunkMapper.selectList(new LambdaQueryWrapper<KnowledgeChunk>()
                        .eq(KnowledgeChunk::getKnowledgeBaseId, base.getId())
                        .like(KnowledgeChunk::getContent, lexicalTerm)
                        .last("LIMIT " + Math.max(topK * 2, 10)));
                Map<Long, KnowledgeDoc> docs = docMap(
                        lexical.stream().map(KnowledgeChunk::getDocId).toList());
                Set<Long> lexicalIds = new HashSet<>();
                for (KnowledgeChunk chunk : lexical) {
                    KnowledgeDoc doc = docs.get(chunk.getDocId());
                    if (doc != null && !canRead(doc, role)) {
                        continue;
                    }
                    lexicalIds.add(chunk.getId());
                    RetrievedChunk existing = candidates.get(chunk.getId());
                    double boosted = existing == null ? 0.35 : Math.min(0.99, existing.score() + 0.08);
                    candidates.put(chunk.getId(), new RetrievedChunk(chunk.getId(), chunk.getDocId(),
                            doc == null ? "未知文档" : doc.getName(), base.getId(), base.getName(),
                            chunk.getChunkIndex(), chunk.getContent(), boosted));
                }
                for (Map.Entry<Long, RetrievedChunk> entry : candidates.entrySet()) {
                    if (lexicalIds.contains(entry.getKey())) {
                        RetrievedChunk chunk = entry.getValue();
                        candidates.put(entry.getKey(), new RetrievedChunk(chunk.chunkId(), chunk.docId(),
                                chunk.docName(), chunk.knowledgeBaseId(), chunk.knowledgeBaseName(),
                                chunk.chunkIndex(), chunk.content(), Math.min(0.99, chunk.score() + 0.04)));
                    }
                }
            } catch (RuntimeException ex) {
                log.debug("Lexical retrieval failed for kb {}: {}", base.getId(), ex.getMessage());
            }
        }

        List<RetrievedChunk> results = new ArrayList<>(candidates.values());
        results.sort(Comparator.comparingDouble(RetrievedChunk::score).reversed());
        return results.size() > topK ? results.subList(0, topK) : results;
    }

    public List<RetrievedChunk> retrieveForAgent(Long spaceId, List<Long> knowledgeBaseIds, String query,
                                                 int maxChars, double minScore, String role) {
        if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty() || Texts.isBlank(query)) {
            return List.of();
        }
        List<Long> ids = knowledgeBaseIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        List<KnowledgeBase> bases = baseMapper.selectBatchIds(ids).stream()
                .filter(base -> Objects.equals(base.getSpaceId(), spaceId))
                .filter(base -> base.getStatus() == null || base.getStatus() == 0)
                .toList();
        Map<Long, List<KnowledgeBase>> groups = bases.stream().collect(Collectors.groupingBy(
                base -> base.getEmbeddingModelId() == null ? -1L : base.getEmbeddingModelId(),
                LinkedHashMap::new, Collectors.toList()));
        List<RetrievedChunk> chunks = new ArrayList<>();
        Set<Long> seenChunks = new HashSet<>();
        Set<Long> seenDocs = new HashSet<>();
        for (List<KnowledgeBase> group : groups.values()) {
            if (group.isEmpty()) {
                continue;
            }
            float[] vector;
            try {
                vector = embeddingService.embedQuery(group.get(0), query);
            } catch (RuntimeException ex) {
                log.warn("Query embedding failed for model {}: {}",
                        group.get(0).getEmbeddingModelId(), ex.getMessage());
                continue;
            }
            for (KnowledgeBase base : group) {
                try {
                    int limit = base.getTopK() == null || base.getTopK() <= 0 ? 4 : base.getTopK();
                    for (RetrievedChunk chunk : retrieve(base, query, limit, role, vector)) {
                        if (chunk.score() >= minScore && seenChunks.add(chunk.chunkId())) {
                            chunks.add(chunk);
                        }
                    }
                } catch (RuntimeException ex) {
                    log.warn("Knowledge retrieval failed for kb {}: {}", base.getId(), ex.getMessage());
                }
            }
        }
        chunks.sort(Comparator.comparingDouble(RetrievedChunk::score).reversed());

        // Document-level deduplication: keep only the highest-scoring chunk per document
        List<RetrievedChunk> deduped = new ArrayList<>();
        for (RetrievedChunk chunk : chunks) {
            if (seenDocs.add(chunk.docId())) {
                deduped.add(chunk);
            }
        }

        int budget = Math.max(500, maxChars);
        List<RetrievedChunk> selected = new ArrayList<>();
        int used = 0;
        for (RetrievedChunk chunk : deduped) {
            int length = chunk.content() == null ? 0 : chunk.content().length();
            if (!selected.isEmpty() && used + length > budget) {
                continue;
            }
            selected.add(chunk);
            used += length;
            if (selected.size() >= 24) {
                break;
            }
        }
        return selected;
    }

    private static String lexicalTerm(String query) {
        String value = Texts.oneLine(query, 120);
        if (Texts.isBlank(value)) {
            return "";
        }
        String best = "";
        for (String part : value.split("\\s+")) {
            if (part.length() > best.length()) {
                best = part;
            }
        }
        return best.length() > 40 ? best.substring(0, 40) : best;
    }

    private Map<Long, KnowledgeDoc> docMap(List<Long> docIds) {
        List<Long> ids = docIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, KnowledgeDoc> docs = new HashMap<>();
        docMapper.selectBatchIds(ids).forEach(doc -> docs.put(doc.getId(), doc));
        return docs;
    }

    public KnowledgeDoc requireDoc(Long spaceId, Long knowledgeBaseId, Long docId) {
        if (docId == null) {
            throw BizException.badRequest("缺少文档 ID");
        }
        KnowledgeDoc doc = docMapper.selectById(docId);
        if (doc == null || !Objects.equals(doc.getSpaceId(), spaceId)
                || !Objects.equals(doc.getKnowledgeBaseId(), knowledgeBaseId)) {
            throw BizException.notFound("文档不存在");
        }
        return doc;
    }

    private void scheduleIndex(Long docId) {
        try {
            indexQueue.enqueue(docId);
        } catch (RuntimeException ex) {
            log.warn("Failed to schedule doc indexing: docId={}", docId, ex);
            updateDocState(docId, STATUS_FAILED, 0, "文档处理队列繁忙，请稍后重试");
        }
    }

    private void updateDocState(Long docId, String status, int chunkCount, String error) {
        KnowledgeDoc patch = new KnowledgeDoc();
        patch.setId(docId);
        patch.setStatus(status);
        patch.setChunkCount(chunkCount);
        patch.setErrorMessage(Texts.oneLine(error, 900));
        docMapper.updateById(patch);
    }

    private String extractPdf(byte[] bytes) {
        try (PDDocument document = Loader.loadPDF(bytes)) {
            return new PDFTextStripper().getText(document);
        } catch (IOException ex) {
            throw BizException.badRequest("PDF 解析失败：" + Texts.oneLine(ex.getMessage(), 200));
        }
    }

    private KnowledgeDocView view(KnowledgeDoc doc, int charCount) {
        return new KnowledgeDocView(doc.getId(), doc.getKnowledgeBaseId(), doc.getName(), doc.getSourceType(),
                doc.getChunkCount(), doc.getStatus(), doc.getErrorMessage(), charCount,
                parseRoles(doc.getAllowedRoles()), doc.getCreatedAt(), doc.getUpdatedAt());
    }

    private static List<String> parseRoles(String value) {
        return KnowledgeAcl.parse(value);
    }

    private static String normalizeRoles(List<String> roles) {
        return KnowledgeAcl.normalize(roles);
    }

    private static boolean canRead(KnowledgeDoc doc, String role) {
        return KnowledgeAcl.canRead(doc.getAllowedRoles(), role);
    }

    private KnowledgeBaseView view(KnowledgeBase base, String modelName, long docCount, long chunkCount) {
        return new KnowledgeBaseView(base.getId(), base.getSpaceId(), base.getName(), base.getDescription(),
                base.getEmbeddingModelId(), modelName, base.getChunkSize(), base.getChunkOverlap(),
                base.getTopK(), base.getVectorCollection(), base.getEmbeddingDim(), docCount, chunkCount,
                base.getStatus(), base.getCreatedAt());
    }

    private String modelName(Long modelId) {
        if (modelId == null) {
            return "-";
        }
        SupplierModel model = modelMapper.selectById(modelId);
        return model == null ? "-" : model.getName();
    }

    private Map<Long, String> modelNames(List<Long> modelIds) {
        List<Long> ids = modelIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> names = new LinkedHashMap<>();
        modelMapper.selectBatchIds(ids).forEach(model -> names.put(model.getId(), model.getName()));
        return names;
    }

    private Map<Long, Long> countByBase(String table, Long spaceId, List<Long> baseIds) {
        Map<Long, Long> counts = new HashMap<>();
        if (baseIds == null || baseIds.isEmpty()) {
            return counts;
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(baseIds.size(), "?"));
        List<Object> args = new ArrayList<>(baseIds.size() + 1);
        args.add(spaceId);
        args.addAll(baseIds);
        jdbcTemplate.query("SELECT knowledge_base_id, COUNT(*) AS total FROM " + table
                        + " WHERE space_id = ? AND knowledge_base_id IN (" + placeholders + ")"
                        + " GROUP BY knowledge_base_id",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> counts.put(
                        rs.getLong("knowledge_base_id"), rs.getLong("total")), args.toArray());
        return counts;
    }

    private Map<Long, String> docNames(List<Long> docIds) {
        List<Long> ids = docIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> names = new HashMap<>();
        docMapper.selectBatchIds(ids).forEach(doc -> names.put(doc.getId(), doc.getName()));
        return names;
    }

    private void requireUniqueName(Long spaceId, String name, Long excludeId) {
        LambdaQueryWrapper<KnowledgeBase> wrapper = new LambdaQueryWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getSpaceId, spaceId)
                .eq(KnowledgeBase::getName, name);
        if (excludeId != null) {
            wrapper.ne(KnowledgeBase::getId, excludeId);
        }
        if (baseMapper.selectCount(wrapper) > 0) {
            throw BizException.conflict("当前空间已存在同名知识库");
        }
    }

    private ChunkSettings settings(KnowledgeBaseSaveRequest request) {
        int chunkSize = request.chunkSize() == null ? 800 : request.chunkSize();
        int overlap = request.chunkOverlap() == null ? 100 : request.chunkOverlap();
        int topK = request.topK() == null ? 4 : request.topK();
        if (overlap >= chunkSize) {
            throw BizException.badRequest("分块重叠必须小于分块大小");
        }
        return new ChunkSettings(chunkSize, overlap, topK);
    }

    private record ChunkSettings(int chunkSize, int overlap, int topK) {
    }
}
