package com.king.ai.model;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.king.ai.common.BizException;
import com.king.ai.common.CryptoService;
import com.king.ai.common.OptimisticLocks;
import com.king.ai.common.PageQuery;
import com.king.ai.common.PageResult;
import com.king.ai.common.Texts;
import com.king.ai.common.web.OutboundUrlGuard;
import com.king.ai.llm.LlmClient;
import com.king.ai.llm.LlmClientFactory;
import com.king.ai.model.dto.SupplierSaveRequest;
import com.king.ai.model.dto.SupplierTestResult;
import com.king.ai.model.dto.SupplierView;
import com.king.ai.model.entity.Supplier;
import com.king.ai.model.entity.SupplierModel;
import com.king.ai.model.mapper.SupplierMapper;
import com.king.ai.model.mapper.SupplierModelMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SupplierService {

    private final SupplierMapper supplierMapper;
    private final SupplierModelMapper modelMapper;
    private final CryptoService cryptoService;
    private final OutboundUrlGuard outboundUrlGuard;
    private final LlmClientFactory clientFactory;
    private final ObjectMapper objectMapper;

    public PageResult<SupplierView> list(Long spaceId, String keyword, PageQuery query) {
        LambdaQueryWrapper<Supplier> wrapper = new LambdaQueryWrapper<Supplier>()
                .eq(Supplier::getSpaceId, spaceId);
        if (!Texts.isBlank(keyword)) {
            String kw = keyword.trim();
            wrapper.and(w -> w.like(Supplier::getName, kw).or().like(Supplier::getRemark, kw));
        }
        wrapper.orderByAsc(Supplier::getId);
        Page<Supplier> result = supplierMapper.selectPage(new Page<>(query.page(), query.size()), wrapper);
        List<Supplier> suppliers = result.getRecords();
        if (suppliers.isEmpty()) {
            return PageResult.of(List.of(), result.getTotal(), query.page(), query.size());
        }
        List<Long> ids = suppliers.stream().map(Supplier::getId).toList();
        Map<Long, Long> counts = new HashMap<>();
        modelMapper.selectList(new LambdaQueryWrapper<SupplierModel>()
                        .select(SupplierModel::getSupplierId)
                        .in(SupplierModel::getSupplierId, ids))
                .forEach(model -> counts.merge(model.getSupplierId(), 1L, Long::sum));
        List<SupplierView> views = suppliers.stream()
                .map(supplier -> view(supplier, counts.getOrDefault(supplier.getId(), 0L)))
                .toList();
        return PageResult.of(views, result.getTotal(), query.page(), query.size());
    }

    public Supplier require(Long spaceId, Long id) {
        if (id == null) {
            throw BizException.badRequest("缺少供应商 ID");
        }
        Supplier supplier = supplierMapper.selectById(id);
        if (supplier == null || !Objects.equals(supplier.getSpaceId(), spaceId)) {
            throw BizException.notFound("模型供应商不存在");
        }
        return supplier;
    }

    @Transactional
    public SupplierView create(Long spaceId, SupplierSaveRequest request) {
        String name = request.name().trim();
        requireUniqueName(spaceId, name, null);
        Supplier supplier = new Supplier();
        supplier.setSpaceId(spaceId);
        apply(supplier, request, true);
        supplierMapper.insert(supplier);
        return view(supplier, 0);
    }

    public SupplierView update(Long spaceId, Long id, SupplierSaveRequest request) {
        Supplier supplier = require(spaceId, id);
        String name = request.name().trim();
        requireUniqueName(spaceId, name, id);
        apply(supplier, request, false);
        OptimisticLocks.requireUpdated(supplierMapper.updateById(supplier), "供应商");
        long modelCount = modelMapper.selectCount(new LambdaQueryWrapper<SupplierModel>()
                .eq(SupplierModel::getSupplierId, id));
        return view(supplier, modelCount);
    }

    public void delete(Long spaceId, Long id) {
        require(spaceId, id);
        long models = modelMapper.selectCount(new LambdaQueryWrapper<SupplierModel>()
                .eq(SupplierModel::getSupplierId, id));
        if (models > 0) {
            throw BizException.conflict("该供应商下仍有 " + models + " 个模型，请先删除模型");
        }
        supplierMapper.deleteById(id);
    }

    public SupplierTestResult test(Long spaceId, Long id) {
        Supplier supplier = require(spaceId, id);
        try {
            LlmClient client = clientFactory.clientFor(supplier);
            List<String> models = client.listModels();
            return new SupplierTestResult(true, "连接成功，发现 " + models.size() + " 个可用模型", models);
        } catch (RuntimeException ex) {
            log.warn("Supplier connection test failed: supplierId={}, reason={}", id, ex.getMessage());
            return new SupplierTestResult(false, Texts.blankToDefault(ex.getMessage(), "连接测试失败"), List.of());
        }
    }

    public SupplierView view(Supplier supplier) {
        long modelCount = modelMapper.selectCount(new LambdaQueryWrapper<SupplierModel>()
                .eq(SupplierModel::getSupplierId, supplier.getId()));
        return view(supplier, modelCount);
    }

    private SupplierView view(Supplier supplier, long modelCount) {
        boolean hasKey = !Texts.isBlank(supplier.getApiKey());
        String masked = "";
        if (hasKey) {
            try {
                masked = Texts.maskSecret(cryptoService.decrypt(supplier.getApiKey()));
            } catch (RuntimeException ex) {
                masked = "••••••••";
            }
        }
        return new SupplierView(supplier.getId(), supplier.getSpaceId(), supplier.getName(), supplier.getType(),
                supplier.getBaseUrl(), masked, hasKey, !Texts.isBlank(supplier.getHeaders()), supplier.getStatus(),
                supplier.getRemark(), modelCount, supplier.getCreatedAt());
    }

    private void apply(Supplier supplier, SupplierSaveRequest request, boolean creating) {
        String type = Texts.blankToDefault(request.type(), "OPENAI").trim().toUpperCase(Locale.ROOT);
        if (!"OPENAI".equals(type) && !"MOCK".equals(type)) {
            throw BizException.badRequest("供应商类型不合法");
        }
        String baseUrl;
        if ("MOCK".equals(type)) {
            baseUrl = "mock://local";
        } else {
            baseUrl = Texts.blankToDefault(request.baseUrl(), "");
            String violation = outboundUrlGuard.violation(baseUrl);
            if (violation != null) {
                throw BizException.badRequest(violation);
            }
        }
        supplier.setName(request.name().trim());
        supplier.setType(type);
        supplier.setBaseUrl(Texts.trimTo(baseUrl, 500));
        if (creating) {
            supplier.setHeaders(encryptHeaders(request.headers()));
        } else if (request.headers() != null) {
            supplier.setHeaders(encryptHeaders(request.headers()));
        }
        supplier.setStatus(request.status() == null || request.status() == 0 ? 0 : 1);
        supplier.setRemark(Texts.trimTo(request.remark(), 500));
        if (creating) {
            String key = Texts.blankToDefault(request.apiKey(), "");
            supplier.setApiKey(Texts.isBlank(key) ? "" : cryptoService.encrypt(key));
            supplier.setApiKeyRotatedAt(Texts.isBlank(key) ? null : LocalDateTime.now());
        } else if (request.apiKey() != null) {
            String key = request.apiKey().trim();
            supplier.setApiKey(Texts.isBlank(key) ? "" : cryptoService.encrypt(key));
            supplier.setApiKeyRotatedAt(Texts.isBlank(key) ? null : LocalDateTime.now());
        }
    }

    private String normalizeHeaders(String headers) {
        if (Texts.isBlank(headers)) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(headers);
            if (!node.isObject()) {
                throw BizException.badRequest("自定义请求头必须是 JSON 对象");
            }
            var fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                if (Texts.isBlank(entry.getKey()) || !entry.getValue().isValueNode()) {
                    throw BizException.badRequest("自定义请求头必须是扁平的键值对象");
                }
            }
            return objectMapper.writeValueAsString(node);
        } catch (BizException ex) {
            throw ex;
        } catch (Exception ex) {
            throw BizException.badRequest("自定义请求头不是合法 JSON");
        }
    }

    private String encryptHeaders(String headers) {
        String normalized = normalizeHeaders(headers);
        return Texts.isBlank(normalized) ? "" : cryptoService.encrypt(normalized);
    }

    private void requireUniqueName(Long spaceId, String name, Long excludeId) {
        LambdaQueryWrapper<Supplier> wrapper = new LambdaQueryWrapper<Supplier>()
                .eq(Supplier::getSpaceId, spaceId)
                .eq(Supplier::getName, name);
        if (excludeId != null) {
            wrapper.ne(Supplier::getId, excludeId);
        }
        if (supplierMapper.selectCount(wrapper) > 0) {
            throw BizException.conflict("当前空间已存在同名供应商");
        }
    }

    public Map<Long, String> supplierNames(List<Long> supplierIds) {
        if (supplierIds == null || supplierIds.isEmpty()) {
            return Map.of();
        }
        return supplierMapper.selectBatchIds(supplierIds.stream().distinct().toList()).stream()
                .collect(Collectors.toMap(Supplier::getId, Supplier::getName));
    }
}
