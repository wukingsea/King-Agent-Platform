package com.king.ai.skill;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.king.ai.agent.entity.AgentSkill;
import com.king.ai.agent.mapper.AgentSkillMapper;
import com.king.ai.common.BizException;
import com.king.ai.common.OptimisticLocks;
import com.king.ai.common.PageQuery;
import com.king.ai.common.PageResult;
import com.king.ai.common.Texts;
import com.king.ai.skill.dto.SkillDetail;
import com.king.ai.skill.dto.SkillFileCreateRequest;
import com.king.ai.skill.dto.SkillFileUpdateRequest;
import com.king.ai.skill.dto.SkillFileView;
import com.king.ai.skill.dto.SkillSaveRequest;
import com.king.ai.skill.dto.SkillView;
import com.king.ai.skill.entity.Skill;
import com.king.ai.skill.entity.SkillFile;
import com.king.ai.skill.mapper.SkillFileMapper;
import com.king.ai.skill.mapper.SkillMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class SkillService {

    private static final int MAX_FILE_CHARS = 200000;
    private static final int MAX_PROMPT_CHARS = 12000;
    private static final int MAX_TOOL_CHARS = 100000;

    private final SkillMapper skillMapper;
    private final SkillFileMapper fileMapper;
    private final AgentSkillMapper agentSkillMapper;

    public PageResult<SkillView> list(Long spaceId, String keyword, PageQuery query) {
        LambdaQueryWrapper<Skill> wrapper = new LambdaQueryWrapper<Skill>()
                .eq(Skill::getSpaceId, spaceId);
        if (!Texts.isBlank(keyword)) {
            String kw = keyword.trim();
            wrapper.and(w -> w.like(Skill::getName, kw).or().like(Skill::getDescription, kw));
        }
        wrapper.orderByDesc(Skill::getId);
        Page<Skill> result = skillMapper.selectPage(new Page<>(query.page(), query.size()), wrapper);
        List<Skill> skills = result.getRecords();
        if (skills.isEmpty()) {
            return PageResult.of(List.of(), result.getTotal(), query.page(), query.size());
        }
        Map<Long, Long> counts = new HashMap<>();
        fileMapper.selectList(new LambdaQueryWrapper<SkillFile>()
                        .select(SkillFile::getSkillId)
                        .in(SkillFile::getSkillId, skills.stream().map(Skill::getId).toList()))
                .forEach(file -> counts.merge(file.getSkillId(), 1L, Long::sum));
        List<SkillView> views = skills.stream()
                .map(skill -> view(skill, counts.getOrDefault(skill.getId(), 0L)))
                .toList();
        return PageResult.of(views, result.getTotal(), query.page(), query.size());
    }

    public Skill require(Long spaceId, Long id) {
        if (id == null) {
            throw BizException.badRequest("缺少技能 ID");
        }
        Skill skill = skillMapper.selectById(id);
        if (skill == null || !Objects.equals(skill.getSpaceId(), spaceId)) {
            throw BizException.notFound("技能不存在");
        }
        return skill;
    }

    public SkillDetail detail(Long spaceId, Long id) {
        Skill skill = require(spaceId, id);
        List<SkillFile> files = filesOf(id);
        long count = files.size();
        return new SkillDetail(view(skill, count), files.stream().map(this::fileView).toList());
    }

    @Transactional
    public SkillDetail create(Long spaceId, SkillSaveRequest request) {
        requireUniqueName(spaceId, request.name().trim(), null);
        Skill skill = new Skill();
        skill.setSpaceId(spaceId);
        apply(skill, request);
        skillMapper.insert(skill);
        SkillFile root = new SkillFile();
        root.setSkillId(skill.getId());
        root.setParentId(0L);
        root.setName("SKILL.md");
        root.setType(1);
        root.setContent(defaultSkillContent(skill));
        root.setLocked(1);
        root.setSortOrder(0);
        fileMapper.insert(root);
        return detail(spaceId, skill.getId());
    }

    public SkillView update(Long spaceId, Long id, SkillSaveRequest request) {
        Skill skill = require(spaceId, id);
        requireUniqueName(spaceId, request.name().trim(), id);
        apply(skill, request);
        OptimisticLocks.requireUpdated(skillMapper.updateById(skill), "技能");
        long count = fileMapper.selectCount(new LambdaQueryWrapper<SkillFile>()
                .eq(SkillFile::getSkillId, id));
        return view(skill, count);
    }

    @Transactional
    public void delete(Long spaceId, Long id) {
        require(spaceId, id);
        long agents = agentSkillMapper.selectCount(new LambdaQueryWrapper<AgentSkill>()
                .eq(AgentSkill::getSkillId, id));
        if (agents > 0) {
            throw BizException.conflict("技能已绑定 " + agents + " 个智能体，请先解除绑定");
        }
        fileMapper.delete(new LambdaQueryWrapper<SkillFile>().eq(SkillFile::getSkillId, id));
        skillMapper.deleteById(id);
    }

    public SkillFileView createFile(Long spaceId, Long skillId, SkillFileCreateRequest request) {
        require(spaceId, skillId);
        long parentId = request.parentId() == null ? 0L : request.parentId();
        if (parentId != 0L) {
            SkillFile parent = requireFile(spaceId, skillId, parentId);
            if (parent.getType() == null || parent.getType() != 0) {
                throw BizException.badRequest("父级必须是目录");
            }
        }
        String name = validateFileName(request.name());
        int type = request.type() == null ? 1 : request.type();
        if (type != 0 && type != 1) {
            throw BizException.badRequest("文件类型不合法");
        }
        requireUniqueFile(skillId, parentId, name, null);
        String content = type == 0 ? null : clampContent(request.content());
        SkillFile file = new SkillFile();
        file.setSkillId(skillId);
        file.setParentId(parentId);
        file.setName(name);
        file.setType(type);
        file.setContent(content);
        file.setLocked(0);
        file.setSortOrder(request.sortOrder() == null ? 0 : request.sortOrder());
        fileMapper.insert(file);
        return fileView(file);
    }

    public SkillFileView updateFile(Long spaceId, Long skillId, Long fileId, SkillFileUpdateRequest request) {
        SkillFile file = requireFile(spaceId, skillId, fileId);
        boolean locked = file.getLocked() != null && file.getLocked() == 1;
        if (!locked && request.name() != null) {
            String name = validateFileName(request.name());
            if (!name.equals(file.getName())) {
                requireUniqueFile(skillId, file.getParentId(), name, fileId);
                file.setName(name);
            }
        }
        if (request.content() != null && file.getType() != null && file.getType() == 1) {
            file.setContent(clampContent(request.content()));
        }
        if (request.sortOrder() != null) {
            file.setSortOrder(request.sortOrder());
        }
        fileMapper.updateById(file);
        return fileView(file);
    }

    public void deleteFile(Long spaceId, Long skillId, Long fileId) {
        SkillFile file = requireFile(spaceId, skillId, fileId);
        if (file.getLocked() != null && file.getLocked() == 1) {
            throw BizException.conflict("内置文件不能删除");
        }
        if (file.getType() != null && file.getType() == 0) {
            long children = fileMapper.selectCount(new LambdaQueryWrapper<SkillFile>()
                    .eq(SkillFile::getSkillId, skillId)
                    .eq(SkillFile::getParentId, fileId));
            if (children > 0) {
                throw BizException.conflict("目录下仍有文件，请先删除子项");
            }
        }
        fileMapper.deleteById(fileId);
    }

    public String promptFor(Long spaceId, List<Long> skillIds) {
        if (skillIds == null || skillIds.isEmpty()) {
            return "";
        }
        List<Skill> skills = skillMapper.selectBatchIds(skillIds.stream().distinct().toList()).stream()
                .filter(skill -> Objects.equals(skill.getSpaceId(), spaceId))
                .filter(skill -> skill.getStatus() != null && skill.getStatus() == 0)
                .sorted((left, right) -> Long.compare(left.getId(), right.getId()))
                .toList();
        if (skills.isEmpty()) {
            return "";
        }
        StringBuilder prompt = new StringBuilder();
        for (Skill skill : skills) {
            List<SkillFile> files = filesOf(skill.getId());
            Map<String, SkillFile> paths = pathMap(files);
            SkillFile root = files.stream()
                    .filter(file -> file.getParentId() != null && file.getParentId() == 0L
                            && "SKILL.md".equalsIgnoreCase(file.getName()))
                    .findFirst().orElse(null);
            prompt.append("## 技能：").append(skill.getName())
                    .append("（v").append(Texts.blankToDefault(skill.getVersion(), "1.0")).append("）\n");
            if (!Texts.isBlank(skill.getDescription())) {
                prompt.append(skill.getDescription().trim()).append("\n\n");
            }
            if (root != null && !Texts.isBlank(root.getContent())) {
                prompt.append(root.getContent().trim()).append("\n");
            }
            List<String> extraPaths = new ArrayList<>();
            for (Map.Entry<String, SkillFile> entry : paths.entrySet()) {
                if (root != null && Objects.equals(entry.getValue().getId(), root.getId())) {
                    continue;
                }
                extraPaths.add(entry.getKey());
            }
            if (!extraPaths.isEmpty()) {
                prompt.append("\n该技能包含以下文件，需要时使用 skill_read_file 工具读取：\n");
                for (String path : extraPaths) {
                    prompt.append("- 技能：").append(skill.getName()).append("，路径：").append(path).append('\n');
                }
            }
            prompt.append('\n');
        }
        if (prompt.length() > MAX_PROMPT_CHARS) {
            return prompt.substring(0, MAX_PROMPT_CHARS) + "\n…（技能内容已截断）";
        }
        return prompt.toString().trim();
    }

    public String readFile(Long spaceId, String skillName, String path) {
        Skill skill = resolveSkill(spaceId, skillName);
        if (skill.getStatus() == null || skill.getStatus() != 0) {
            throw BizException.badRequest("技能已停用：" + skill.getName());
        }
        List<SkillFile> files = filesOf(skill.getId());
        Map<String, SkillFile> paths = pathMap(files);
        String normalized = normalizePath(path);
        SkillFile file = paths.get(normalized);
        if (file == null) {
            for (Map.Entry<String, SkillFile> entry : paths.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(normalized)) {
                    file = entry.getValue();
                    normalized = entry.getKey();
                    break;
                }
            }
        }
        if (file == null) {
            return "技能中不存在路径：" + normalized + "\n可用路径：\n- " + String.join("\n- ", paths.keySet());
        }
        if (file.getType() != null && file.getType() == 0) {
            String prefix = normalized.endsWith("/") ? normalized : normalized + "/";
            List<String> children = paths.keySet().stream().filter(value -> value.startsWith(prefix)).toList();
            return "目录 " + normalized + " 下包含：\n- " + String.join("\n- ", children);
        }
        String content = file.getContent() == null ? "" : file.getContent();
        if (content.length() > MAX_TOOL_CHARS) {
            content = content.substring(0, MAX_TOOL_CHARS) + "\n…（文件内容已截断）";
        }
        return "文件：" + normalized + "\n\n" + content;
    }

    private Skill resolveSkill(Long spaceId, String skillName) {
        if (Texts.isBlank(skillName)) {
            throw BizException.badRequest("请指定技能名称");
        }
        Skill skill = skillMapper.selectOne(new LambdaQueryWrapper<Skill>()
                .eq(Skill::getSpaceId, spaceId)
                .eq(Skill::getName, skillName.trim()));
        if (skill == null && skillName.trim().matches("\\d+")) {
            Skill byId = skillMapper.selectById(Long.parseLong(skillName.trim()));
            if (byId != null && Objects.equals(byId.getSpaceId(), spaceId)) {
                skill = byId;
            }
        }
        if (skill == null) {
            throw BizException.badRequest("技能不存在：" + skillName);
        }
        return skill;
    }

    private SkillFile requireFile(Long spaceId, Long skillId, Long fileId) {
        require(spaceId, skillId);
        if (fileId == null) {
            throw BizException.badRequest("缺少文件 ID");
        }
        SkillFile file = fileMapper.selectById(fileId);
        if (file == null || !Objects.equals(file.getSkillId(), skillId)) {
            throw BizException.notFound("技能文件不存在");
        }
        return file;
    }

    private List<SkillFile> filesOf(Long skillId) {
        return fileMapper.selectList(new LambdaQueryWrapper<SkillFile>()
                .eq(SkillFile::getSkillId, skillId)
                .orderByAsc(SkillFile::getSortOrder)
                .orderByAsc(SkillFile::getId));
    }

    private Map<String, SkillFile> pathMap(List<SkillFile> files) {
        Map<Long, SkillFile> byId = new HashMap<>();
        files.forEach(file -> byId.put(file.getId(), file));
        Map<String, SkillFile> paths = new LinkedHashMap<>();
        for (SkillFile file : files) {
            List<String> parts = new ArrayList<>();
            SkillFile current = file;
            int guard = 0;
            while (current != null && guard++ < 30) {
                parts.add(0, current.getName());
                if (current.getParentId() == null || current.getParentId() == 0L) {
                    break;
                }
                current = byId.get(current.getParentId());
            }
            paths.put(String.join("/", parts), file);
        }
        return paths;
    }

    private static String normalizePath(String path) {
        String value = Texts.blankToDefault(path, "SKILL.md").replace('\\', '/').trim();
        while (value.startsWith("./")) {
            value = value.substring(2);
        }
        while (value.startsWith("/")) {
            value = value.substring(1);
        }
        if (value.contains("..")) {
            throw BizException.badRequest("非法文件路径");
        }
        return value.isBlank() ? "SKILL.md" : value;
    }

    private void apply(Skill skill, SkillSaveRequest request) {
        skill.setName(request.name().trim());
        skill.setDescription(Texts.trimTo(request.description(), 500));
        skill.setVersion(Texts.blankToDefault(request.version(), "1.0").trim());
        skill.setStatus(request.status() == null || request.status() == 0 ? 0 : 1);
    }

    private void requireUniqueName(Long spaceId, String name, Long excludeId) {
        LambdaQueryWrapper<Skill> wrapper = new LambdaQueryWrapper<Skill>()
                .eq(Skill::getSpaceId, spaceId)
                .eq(Skill::getName, name);
        if (excludeId != null) {
            wrapper.ne(Skill::getId, excludeId);
        }
        if (skillMapper.selectCount(wrapper) > 0) {
            throw BizException.conflict("当前空间已存在同名技能");
        }
    }

    private void requireUniqueFile(Long skillId, Long parentId, String name, Long excludeId) {
        LambdaQueryWrapper<SkillFile> wrapper = new LambdaQueryWrapper<SkillFile>()
                .eq(SkillFile::getSkillId, skillId)
                .eq(SkillFile::getParentId, parentId)
                .eq(SkillFile::getName, name);
        if (excludeId != null) {
            wrapper.ne(SkillFile::getId, excludeId);
        }
        if (fileMapper.selectCount(wrapper) > 0) {
            throw BizException.conflict("同级目录下已存在同名文件");
        }
    }

    private static String validateFileName(String name) {
        String value = name == null ? "" : name.trim();
        if (value.isEmpty()) {
            throw BizException.badRequest("文件名称不能为空");
        }
        if (value.length() > 200) {
            throw BizException.badRequest("文件名称不能超过 200 个字符");
        }
        if (value.contains("/") || value.contains("\\") || value.contains("..")
                || value.contains("\n") || value.contains("\r") || value.contains("\t")) {
            throw BizException.badRequest("文件名称不能包含路径分隔符或控制字符");
        }
        return value;
    }

    private static String clampContent(String content) {
        if (content == null) {
            return "";
        }
        if (content.length() > MAX_FILE_CHARS) {
            throw BizException.badRequest("单个技能文件不能超过 20 万字符");
        }
        return content;
    }

    private static String defaultSkillContent(Skill skill) {
        return "# " + skill.getName() + "\n\n"
                + "## 技能说明\n\n"
                + Texts.blankToDefault(skill.getDescription(), "请在此补充技能的目标、适用场景与执行规范。") + "\n\n"
                + "## 工作流程\n\n"
                + "1. 阅读用户请求，确认目标与约束。\n"
                + "2. 按技能规范执行，必要时读取 reference 目录下的文件。\n"
                + "3. 输出结论，并说明依据。\n\n"
                + "## 约束\n\n"
                + "- 不确定的信息要明确说明，不要编造。\n"
                + "- 保持语气专业、克制，并遵守平台安全要求。\n";
    }

    private SkillView view(Skill skill, long fileCount) {
        return new SkillView(skill.getId(), skill.getSpaceId(), skill.getName(), skill.getDescription(),
                skill.getVersion(), skill.getStatus(), fileCount, skill.getCreatedAt());
    }

    private SkillFileView fileView(SkillFile file) {
        return new SkillFileView(file.getId(), file.getParentId(), file.getName(), file.getType(),
                file.getContent(), file.getLocked(), file.getSortOrder(), file.getCreatedAt(), file.getUpdatedAt());
    }
}
