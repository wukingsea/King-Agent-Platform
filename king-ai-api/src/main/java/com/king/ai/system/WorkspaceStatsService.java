package com.king.ai.system;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.king.ai.agent.entity.Agent;
import com.king.ai.agent.mapper.AgentMapper;
import com.king.ai.chat.entity.ChatSession;
import com.king.ai.chat.mapper.ChatSessionMapper;
import com.king.ai.knowledge.entity.KnowledgeBase;
import com.king.ai.knowledge.entity.KnowledgeChunk;
import com.king.ai.knowledge.entity.KnowledgeDoc;
import com.king.ai.knowledge.mapper.KnowledgeBaseMapper;
import com.king.ai.knowledge.mapper.KnowledgeChunkMapper;
import com.king.ai.knowledge.mapper.KnowledgeDocMapper;
import com.king.ai.mcp.entity.McpServer;
import com.king.ai.mcp.mapper.McpServerMapper;
import com.king.ai.model.entity.Supplier;
import com.king.ai.model.entity.SupplierModel;
import com.king.ai.model.mapper.SupplierMapper;
import com.king.ai.model.mapper.SupplierModelMapper;
import com.king.ai.skill.entity.Skill;
import com.king.ai.skill.mapper.SkillMapper;
import com.king.ai.system.dto.WorkspaceStatsView;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Aggregated counters for the workspace dashboard. Keeps summary pages from loading every
 * resource list just to count rows, which does not scale with tenant size.
 */
@Service
@RequiredArgsConstructor
public class WorkspaceStatsService {

    private final AgentMapper agentMapper;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final KnowledgeDocMapper knowledgeDocMapper;
    private final KnowledgeChunkMapper knowledgeChunkMapper;
    private final ChatSessionMapper chatSessionMapper;
    private final McpServerMapper mcpServerMapper;
    private final SkillMapper skillMapper;
    private final SupplierMapper supplierMapper;
    private final SupplierModelMapper supplierModelMapper;

    public WorkspaceStatsView stats(Long spaceId) {
        return new WorkspaceStatsView(
                agentMapper.selectCount(new LambdaQueryWrapper<Agent>().eq(Agent::getSpaceId, spaceId)),
                agentMapper.selectCount(new LambdaQueryWrapper<Agent>()
                        .eq(Agent::getSpaceId, spaceId).eq(Agent::getPublishStatus, 1)),
                knowledgeBaseMapper.selectCount(new LambdaQueryWrapper<KnowledgeBase>()
                        .eq(KnowledgeBase::getSpaceId, spaceId)),
                knowledgeDocMapper.selectCount(new LambdaQueryWrapper<KnowledgeDoc>()
                        .eq(KnowledgeDoc::getSpaceId, spaceId)),
                knowledgeChunkMapper.selectCount(new LambdaQueryWrapper<KnowledgeChunk>()
                        .eq(KnowledgeChunk::getSpaceId, spaceId)),
                chatSessionMapper.selectCount(new LambdaQueryWrapper<ChatSession>()
                        .eq(ChatSession::getSpaceId, spaceId)),
                mcpServerMapper.selectCount(new LambdaQueryWrapper<McpServer>()
                        .eq(McpServer::getSpaceId, spaceId)),
                skillMapper.selectCount(new LambdaQueryWrapper<Skill>()
                        .eq(Skill::getSpaceId, spaceId)),
                supplierMapper.selectCount(new LambdaQueryWrapper<Supplier>()
                        .eq(Supplier::getSpaceId, spaceId)),
                supplierModelMapper.selectCount(new LambdaQueryWrapper<SupplierModel>()
                        .eq(SupplierModel::getSpaceId, spaceId)));
    }
}
