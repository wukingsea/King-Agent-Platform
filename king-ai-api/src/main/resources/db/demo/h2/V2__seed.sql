INSERT INTO king_ai_user (username, password, nickname, email, role, status, created_at, updated_at)
VALUES ('admin', '$2b$10$lsmOp6EiL1o1seX6V8cfFemiU4lY/j40roaUqVMb.iJGzG2UufCMe', '平台管理员', 'admin@king.ai', 'ADMIN', 0, NOW(), NOW());

INSERT INTO king_ai_space (name, code, description, status, created_at, updated_at)
VALUES ('King AI 演示空间', 'demo', '用于体验智能体编排、知识库检索、MCP 工具与技能', 0, NOW(), NOW());

INSERT INTO king_ai_user_space (user_id, space_id, created_at, updated_at)
SELECT u.id, s.id, NOW(), NOW()
FROM king_ai_user u, king_ai_space s
WHERE u.username = 'admin' AND s.code = 'demo';

INSERT INTO king_ai_supplier (space_id, name, type, base_url, api_key, headers, status, remark, created_at, updated_at)
SELECT s.id, '内置演示供应商', 'MOCK', 'mock://local', NULL, NULL, 0,
       '无需 API Key，用于本地体验完整对话、RAG 与工具调用链路', NOW(), NOW()
FROM king_ai_space s
WHERE s.code = 'demo';

INSERT INTO king_ai_supplier_model (space_id, supplier_id, name, model, type, dimension, status, created_at, updated_at)
SELECT sup.space_id, sup.id, 'King Chat Demo', 'king-chat-demo', 'CHAT', 0, 0, NOW(), NOW()
FROM king_ai_supplier sup
WHERE sup.name = '内置演示供应商';

INSERT INTO king_ai_supplier_model (space_id, supplier_id, name, model, type, dimension, status, created_at, updated_at)
SELECT sup.space_id, sup.id, 'King Embedding Demo', 'king-embed-demo', 'EMBEDDING', 256, 0, NOW(), NOW()
FROM king_ai_supplier sup
WHERE sup.name = '内置演示供应商';

INSERT INTO king_ai_knowledge_base (space_id, name, description, embedding_model_id, chunk_size, chunk_overlap, top_k, vector_collection, embedding_dim, status, created_at, updated_at)
SELECT s.id, 'King AI 产品知识库', '平台能力、部署与安全说明，用于 RAG 演示', m.id, 600, 80, 4, 'king_kb_demo', 256, 0, NOW(), NOW()
FROM king_ai_space s
JOIN king_ai_supplier sup ON sup.space_id = s.id AND sup.name = '内置演示供应商'
JOIN king_ai_supplier_model m ON m.supplier_id = sup.id AND m.model = 'king-embed-demo'
WHERE s.code = 'demo';

INSERT INTO king_ai_knowledge_doc (space_id, knowledge_base_id, name, source_type, content, chunk_count, status, error_message, created_at, updated_at)
SELECT s.id, kb.id, 'King AI 平台介绍.md', 'TEXT',
'# King AI 平台介绍

King AI 是一个面向团队与企业的 AI Agent 开发平台。平台以 Agent 编排为中枢，把模型、系统指令、知识库、MCP 工具和技能组合成可直接发布的智能体。

## 核心能力

1. 智能体编排与发布：选择对话模型、填写系统指令、绑定知识库、MCP 服务与技能，即可一键发布。发布后会生成公开访问地址，访客无需登录即可对话。
2. 流式对话：基于 SSE 输出思考过程、回复内容、工具调用与知识引用。生成过程中可以随时停止，刷新页面后仍可查看已经落库的内容。
3. 知识库 RAG：支持文本与文件导入，按照段落与句子边界分片，生成向量后写入 Milvus。对话时自动检索相关片段并注入上下文。
4. MCP 工具：通过 Streamable HTTP 接入远程 MCP 服务，自动发现工具并将工具定义装配给模型，支持多轮工具调用。
5. 技能 SKILL：以 SKILL.md 为核心，配合文件树沉淀领域知识。技能内容会注入系统提示词，并允许智能体按需读取技能参考文件。
6. 多空间与权限：业务空间隔离数据，管理员可以统一管理用户、空间和资源授权；普通用户只能访问被授权的空间。
7. 多模型供应商：统一接入 OpenAI 兼容协议，支持对话模型与嵌入模型，可测试连通性并导入供应商模型列表。
8. 安全设计：API Key 使用 AES-GCM 加密存储，登录态使用不透明 Token，Markdown 渲染经过净化，公开对话按访客进行频率限制。

## 部署方式

开发环境可以使用 H2 文件数据库、内存 Token 存储与本地向量索引，一条命令启动。生产环境使用 MySQL、Redis 与 Milvus，提供 Docker Compose 一键部署栈。

## 默认演示

平台内置 Mock 供应商，不需要外部 API Key。Mock 模型会流式返回演示回复，并使用字符二元组哈希生成确定性向量，方便在离线环境验证 RAG 与工具调用流程。接入真实供应商时，只需要把供应商类型改为 OpenAI 兼容并填写 Base URL 与 API Key。',
0, 'PENDING', NULL, NOW(), NOW()
FROM king_ai_space s
JOIN king_ai_knowledge_base kb ON kb.space_id = s.id AND kb.name = 'King AI 产品知识库'
WHERE s.code = 'demo';

INSERT INTO king_ai_skill (space_id, name, description, version, status, created_at, updated_at)
SELECT s.id, '产品答疑规范', '约束智能体回答产品问题时的结构与语气', '1.0', 0, NOW(), NOW()
FROM king_ai_space s
WHERE s.code = 'demo';

INSERT INTO king_ai_skill_file (skill_id, parent_id, name, type, content, locked, sort_order, created_at, updated_at)
SELECT k.id, 0, 'SKILL.md', 1,
'# 产品答疑规范

## 目标

帮助访客准确理解 King AI 平台的能力、部署方式与安全边界。

## 回答结构

1. 先用一句话给出直接结论。
2. 再列出关键能力或步骤，必要时引用知识库来源。
3. 最后给出下一步建议或可操作的入口。

## 约束

- 不确定的内容要明确说明，不要编造能力。
- 涉及部署与安全时，优先使用知识库中的事实。
- 语气专业、克制，不使用夸张宣传语。
- 如果用户要求体验，建议前往工作台或公开智能体页面。',
1, 0, NOW(), NOW()
FROM king_ai_skill k
JOIN king_ai_space s ON s.id = k.space_id
WHERE k.name = '产品答疑规范' AND s.code = 'demo';

INSERT INTO king_ai_skill_file (skill_id, parent_id, name, type, content, locked, sort_order, created_at, updated_at)
SELECT k.id, 0, 'reference', 0, NULL, 0, 1, NOW(), NOW()
FROM king_ai_skill k
JOIN king_ai_space s ON s.id = k.space_id
WHERE k.name = '产品答疑规范' AND s.code = 'demo';

INSERT INTO king_ai_skill_file (skill_id, parent_id, name, type, content, locked, sort_order, created_at, updated_at)
SELECT k.id, dir.id, 'faq.md', 1,
'# 常见问题

Q: 平台是否必须使用 MySQL、Redis 和 Milvus？
A: 生产部署推荐使用三件套。本地开发可以使用零依赖 profile，数据落在 H2 文件中。

Q: 没有大模型 API Key 可以体验吗？
A: 可以。内置 Mock 供应商支持流式对话、确定性向量、知识库检索与 MCP 工具调用。

Q: 公开智能体会泄露系统提示词吗？
A: 公开接口只返回名称、介绍与能力标签，不返回系统提示词、供应商密钥或模型服务配置。',
0, 0, NOW(), NOW()
FROM king_ai_skill k
JOIN king_ai_space s ON s.id = k.space_id
JOIN king_ai_skill_file dir ON dir.skill_id = k.id AND dir.name = 'reference'
WHERE k.name = '产品答疑规范' AND s.code = 'demo';

INSERT INTO king_ai_agent (space_id, name, intro, model_id, system_prompt, temperature, max_tokens, publish_status, public_id, status, created_at, updated_at)
SELECT s.id, 'King 助手',
       'King AI 内置演示智能体，可体验流式对话、知识库检索与 MCP 工具调用。',
       m.id,
       '你是 King AI 平台的演示智能体。请使用中文、准确且克制地回答用户问题。优先依据知识库资料，必要时调用工具获取实时信息。',
       0.70, 2048, 1, 'demo-agent', 0, NOW(), NOW()
FROM king_ai_space s
JOIN king_ai_supplier sup ON sup.space_id = s.id AND sup.name = '内置演示供应商'
JOIN king_ai_supplier_model m ON m.supplier_id = sup.id AND m.model = 'king-chat-demo'
WHERE s.code = 'demo';

INSERT INTO king_ai_agent_knowledge (agent_id, knowledge_base_id, created_at, updated_at)
SELECT a.id, kb.id, NOW(), NOW()
FROM king_ai_agent a
JOIN king_ai_knowledge_base kb ON kb.space_id = a.space_id AND kb.name = 'King AI 产品知识库'
WHERE a.public_id = 'demo-agent';

INSERT INTO king_ai_agent_skill (agent_id, skill_id, created_at, updated_at)
SELECT a.id, k.id, NOW(), NOW()
FROM king_ai_agent a
JOIN king_ai_skill k ON k.space_id = a.space_id AND k.name = '产品答疑规范'
WHERE a.public_id = 'demo-agent';
