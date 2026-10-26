INSERT INTO king_ai_mcp (space_id, name, transport, url, headers, status, remark, created_at, updated_at)
SELECT s.id, '本地演示 MCP', 'HTTP', 'http://localhost:8080/api/demo/mcp', NULL, 0,
       '内置演示 MCP 服务，提供时间与回显工具，需开启 king-ai.demo.enabled', NOW(), NOW()
FROM king_ai_space s
WHERE s.code = 'demo';

INSERT INTO king_ai_agent_mcp (agent_id, mcp_id, created_at, updated_at)
SELECT a.id, m.id, NOW(), NOW()
FROM king_ai_agent a
JOIN king_ai_mcp m ON m.space_id = a.space_id AND m.name = '本地演示 MCP'
WHERE a.public_id = 'demo-agent';
