package com.king.ai.system.dto;

import java.util.List;

public record MemberUpdateRequest(List<Member> members) {

    public record Member(Long userId, String role) {
    }
}
