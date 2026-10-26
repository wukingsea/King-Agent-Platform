package com.king.ai.skill.dto;

import java.util.List;

public record SkillDetail(
        SkillView skill,
        List<SkillFileView> files
) {
}
