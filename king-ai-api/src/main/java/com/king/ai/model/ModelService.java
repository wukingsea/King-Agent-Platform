package com.king.ai.model;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.king.ai.agent.entity.Agent;
import com.king.ai.agent.mapper.AgentMapper;
import com.king.ai.common.BizException;
import com.king.ai.common.OptimisticLocks;
import com.king.ai.common.Texts;
import com.king.ai.knowledge.entity.KnowledgeBase;
import com.king.ai.knowledge.mapper.KnowledgeBaseMapper;
import com.king.ai.model.dto.ModelCreateRequest;
import com.king.ai.model.dto.ModelImportRequest;
import com.king.ai.model.dto.ModelOption;
import com.king.ai.model.dto.ModelUpdateRequest;
import com.king.ai.model.dto.ModelView;
import com.king.ai.model.entity.Supplier;
import com.king.ai.model.entity.SupplierModel;
import com.king.ai.model.mapper.SupplierMapper;
import com.king.ai.model.mapper.SupplierModelMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ModelService {

    private final SupplierModelMapper modelMapper;
    private final SupplierMapper supplierMapper;
    private final AgentMapper agentMapper;
    private final KnowledgeBaseMapper knowledgeBaseMapper;

    public List<ModelView> list(Long spaceId, Long supplierId, String type, String keyword) {
        LambdaQueryWrapper<SupplierModel> wrapper = new LambdaQueryWrapper<SupplierModel>()
                .eq(SupplierModel::getSpaceId, spaceId);
        if (supplierId != null) {
            wrapper.eq(SupplierModel::getSupplierId, supplierId);
        }
        if (!Texts.isBlank(type)) {
            wrapper.eq(SupplierModel::getType, normalizeType(type));
        }
        if (!Texts.isBlank(keyword)) {
            String kw = keyword.trim();
            wrapper.and(w -> w.like(SupplierModel::getName, kw).or().like(SupplierModel::getModel, kw));
        }
        wrapper.orderByAsc(SupplierModel::getId);
        List<SupplierModel> models = modelMapper.selectList(wrapper);
        if (models.isEmpty()) {
            return List.of();
        }
        Map<Long, String> names = supplierNames(models.stream().map(SupplierModel::getSupplierId).toList());
        Map<Long, Long> usages = usageCounts(models);
        return models.stream()
                .map(model -> view(model, names.getOrDefault(model.getSupplierId(), "-"),
                        usages.getOrDefault(model.getId(), 0L)))
                .toList();
    }

    public List<ModelOption> options(Long spaceId, String type) {
        String modelType = normalizeType(Texts.blankToDefault(type, "CHAT"));
        List<Supplier> suppliers = supplierMapper.selectList(new LambdaQueryWrapper<Supplier>()
                .eq(Supplier::getSpaceId, spaceId)
                .eq(Supplier::getStatus, 0));
        if (suppliers.isEmpty()) {
            return List.of();
        }
        Map<Long, String> names = new HashMap<>();
        suppliers.forEach(supplier -> names.put(supplier.getId(), supplier.getName()));
        return modelMapper.selectList(new LambdaQueryWrapper<SupplierModel>()
                        .eq(SupplierModel::getSpaceId, spaceId)
                        .eq(SupplierModel::getType, modelType)
                        .eq(SupplierModel::getStatus, 0)
                        .in(SupplierModel::getSupplierId, names.keySet())
                        .orderByAsc(SupplierModel::getName))
                .stream()
                .map(model -> new ModelOption(model.getId(), model.getName(), model.getModel(), model.getType(),
                        model.getDimension(), names.get(model.getSupplierId())))
                .toList();
    }

    @Transactional
    public ModelView create(Long spaceId, ModelCreateRequest request) {
        Supplier supplier = requireSupplier(spaceId, request.supplierId());
        String type = normalizeType(request.type());
        int dimension = dimensionForCreate(type, request.dimension());
        String modelId = request.model().trim();
        ensureUnique(supplier.getId(), modelId, null);
        SupplierModel model = new SupplierModel();
        model.setSpaceId(spaceId);
        model.setSupplierId(supplier.getId());
        model.setName(request.name().trim());
        model.setModel(modelId);
        model.setType(type);
        model.setDimension(dimension);
        model.setStatus(request.status() == null || request.status() == 0 ? 0 : 1);
        modelMapper.insert(model);
        return view(model, supplier.getName(), 0);
    }

    public ModelView update(Long spaceId, Long id, ModelUpdateRequest request) {
        SupplierModel existing = require(spaceId, id);
        String type = normalizeType(request.type());
        int dimension = dimensionForCreate(type, request.dimension());
        long usage = usage(existing.getId());
        if (usage > 0 && (!type.equals(existing.getType())
                || ("EMBEDDING".equals(type) && !Objects.equals(dimension, existing.getDimension())))) {
            throw BizException.conflict("模型正在被使用，不能修改类型或向量维度");
        }
        String modelId = request.model().trim();
        ensureUnique(existing.getSupplierId(), modelId, id);
        existing.setName(request.name().trim());
        existing.setModel(modelId);
        existing.setType(type);
        existing.setDimension(dimension);
        existing.setStatus(request.status() == null || request.status() == 0 ? 0 : 1);
        OptimisticLocks.requireUpdated(modelMapper.updateById(existing), "模型");
        Supplier supplier = supplierMapper.selectById(existing.getSupplierId());
        return view(existing, supplier == null ? "-" : supplier.getName(), usage);
    }

    @Transactional
    public void delete(Long spaceId, Long id) {
        SupplierModel model = require(spaceId, id);
        long agents = agentMapper.selectCount(new LambdaQueryWrapper<Agent>().eq(Agent::getModelId, id));
        long bases = knowledgeBaseMapper.selectCount(new LambdaQueryWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getEmbeddingModelId, id));
        if (agents > 0 || bases > 0) {
            throw BizException.conflict("模型正在被使用：" + agents + " 个智能体、" + bases + " 个知识库");
        }
        modelMapper.deleteById(model.getId());
    }

    @Transactional
    public List<ModelView> importModels(Long spaceId, Long supplierId, ModelImportRequest request) {
        Supplier supplier = requireSupplier(spaceId, supplierId);
        String type = normalizeType(Texts.blankToDefault(request.type(), "CHAT"));
        int dimension = "EMBEDDING".equals(type)
                ? (request.dimension() == null || request.dimension() <= 0 ? 1536 : request.dimension())
                : 0;
        Set<String> existing = new LinkedHashSet<>();
        modelMapper.selectList(new LambdaQueryWrapper<SupplierModel>()
                        .eq(SupplierModel::getSupplierId, supplierId))
                .forEach(model -> existing.add(model.getModel()));
        Set<String> candidates = new LinkedHashSet<>();
        for (String value : request.models()) {
            if (!Texts.isBlank(value)) {
                candidates.add(value.trim());
            }
        }
        List<ModelView> created = new ArrayList<>();
        for (String modelId : candidates) {
            if (modelId.length() > 150) {
                throw BizException.badRequest("模型标识过长：" + Texts.oneLine(modelId, 60));
            }
            if (!existing.add(modelId)) {
                continue;
            }
            SupplierModel model = new SupplierModel();
            model.setSpaceId(spaceId);
            model.setSupplierId(supplierId);
            model.setName(Texts.trimTo(modelId, 80));
            model.setModel(modelId);
            model.setType(type);
            model.setDimension(dimension);
            model.setStatus(0);
            modelMapper.insert(model);
            created.add(view(model, supplier.getName(), 0));
        }
        return created;
    }

    public SupplierModel require(Long spaceId, Long id) {
        if (id == null) {
            throw BizException.badRequest("缺少模型 ID");
        }
        SupplierModel model = modelMapper.selectById(id);
        if (model == null || !Objects.equals(model.getSpaceId(), spaceId)) {
            throw BizException.notFound("模型不存在");
        }
        return model;
    }

    private Supplier requireSupplier(Long spaceId, Long supplierId) {
        if (supplierId == null) {
            throw BizException.badRequest("请选择模型供应商");
        }
        Supplier supplier = supplierMapper.selectById(supplierId);
        if (supplier == null || !Objects.equals(supplier.getSpaceId(), spaceId)) {
            throw BizException.notFound("模型供应商不存在");
        }
        return supplier;
    }

    private void ensureUnique(Long supplierId, String modelId, Long excludeId) {
        LambdaQueryWrapper<SupplierModel> wrapper = new LambdaQueryWrapper<SupplierModel>()
                .eq(SupplierModel::getSupplierId, supplierId)
                .eq(SupplierModel::getModel, modelId);
        if (excludeId != null) {
            wrapper.ne(SupplierModel::getId, excludeId);
        }
        if (modelMapper.selectCount(wrapper) > 0) {
            throw BizException.conflict("该供应商下已存在相同的模型标识");
        }
    }

    private long usage(Long modelId) {
        return agentMapper.selectCount(new LambdaQueryWrapper<Agent>().eq(Agent::getModelId, modelId))
                + knowledgeBaseMapper.selectCount(new LambdaQueryWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getEmbeddingModelId, modelId));
    }

    private Map<Long, Long> usageCounts(List<SupplierModel> models) {
        List<Long> ids = models.stream().map(SupplierModel::getId).toList();
        Map<Long, Long> counts = new HashMap<>();
        agentMapper.selectList(new LambdaQueryWrapper<Agent>().in(Agent::getModelId, ids))
                .forEach(agent -> counts.merge(agent.getModelId(), 1L, Long::sum));
        knowledgeBaseMapper.selectList(new LambdaQueryWrapper<KnowledgeBase>()
                        .in(KnowledgeBase::getEmbeddingModelId, ids))
                .forEach(base -> counts.merge(base.getEmbeddingModelId(), 1L, Long::sum));
        return counts;
    }

    private Map<Long, String> supplierNames(List<Long> supplierIds) {
        Map<Long, String> names = new HashMap<>();
        supplierMapper.selectBatchIds(supplierIds.stream().distinct().toList())
                .forEach(supplier -> names.put(supplier.getId(), supplier.getName()));
        return names;
    }

    private static String normalizeType(String type) {
        String value = Texts.blankToDefault(type, "CHAT").trim().toUpperCase(Locale.ROOT);
        if (!"CHAT".equals(value) && !"EMBEDDING".equals(value)) {
            throw BizException.badRequest("模型类型不合法");
        }
        return value;
    }

    private static int dimensionForCreate(String type, Integer dimension) {
        if (!"EMBEDDING".equals(type)) {
            return 0;
        }
        if (dimension == null || dimension <= 0) {
            throw BizException.badRequest("向量模型需要填写向量维度");
        }
        if (dimension > 8192) {
            throw BizException.badRequest("向量维度不能超过 8192");
        }
        return dimension;
    }

    private static ModelView view(SupplierModel model, String supplierName, long usage) {
        return new ModelView(model.getId(), model.getSpaceId(), model.getSupplierId(), supplierName,
                model.getName(), model.getModel(), model.getType(), model.getDimension(), model.getStatus(),
                usage, model.getCreatedAt());
    }
}
