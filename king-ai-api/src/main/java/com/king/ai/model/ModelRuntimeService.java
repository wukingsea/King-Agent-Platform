package com.king.ai.model;

import com.king.ai.common.BizException;
import com.king.ai.llm.LlmClient;
import com.king.ai.llm.LlmClientFactory;
import com.king.ai.model.entity.Supplier;
import com.king.ai.model.entity.SupplierModel;
import com.king.ai.model.mapper.SupplierMapper;
import com.king.ai.model.mapper.SupplierModelMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Objects;

@Service
@RequiredArgsConstructor
public class ModelRuntimeService {

    private final SupplierModelMapper modelMapper;
    private final SupplierMapper supplierMapper;
    private final LlmClientFactory clientFactory;

    public record ResolvedModel(SupplierModel model, Supplier supplier, LlmClient client) {
    }

    public ResolvedModel resolveChat(Long spaceId, Long modelId) {
        return resolve(spaceId, modelId, "CHAT");
    }

    public ResolvedModel resolveEmbedding(Long spaceId, Long modelId) {
        return resolve(spaceId, modelId, "EMBEDDING");
    }

    private ResolvedModel resolve(Long spaceId, Long modelId, String expectedType) {
        if (modelId == null) {
            throw BizException.badRequest("请先配置模型");
        }
        SupplierModel model = modelMapper.selectById(modelId);
        if (model == null || !Objects.equals(model.getSpaceId(), spaceId)) {
            throw BizException.badRequest("模型不存在或不属于当前空间");
        }
        if (model.getStatus() == null || model.getStatus() != 0) {
            throw BizException.badRequest("模型已停用：" + model.getName());
        }
        if (!expectedType.equalsIgnoreCase(model.getType())) {
            throw BizException.badRequest("EMBEDDING".equals(expectedType)
                    ? "知识库需要绑定向量模型"
                    : "智能体需要绑定对话模型");
        }
        if ("EMBEDDING".equals(expectedType) && (model.getDimension() == null || model.getDimension() <= 0)) {
            throw BizException.badRequest("向量模型未配置向量维度");
        }
        Supplier supplier = supplierMapper.selectById(model.getSupplierId());
        if (supplier == null || !Objects.equals(supplier.getSpaceId(), spaceId)) {
            throw BizException.badRequest("模型供应商不存在");
        }
        if (supplier.getStatus() == null || supplier.getStatus() != 0) {
            throw BizException.badRequest("模型供应商已停用：" + supplier.getName());
        }
        return new ResolvedModel(model, supplier, clientFactory.clientFor(supplier));
    }
}
