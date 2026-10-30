package com.king.ai.auth.dto;

import com.king.ai.system.dto.SpaceView;
import com.king.ai.system.dto.UserView;

import java.util.List;

public record AuthResponse(String token, UserView user, List<SpaceView> spaces, Long currentSpaceId,
                           boolean mfaRequired, String mfaChallenge) {

    public AuthResponse(String token, UserView user, List<SpaceView> spaces, Long currentSpaceId) {
        this(token, user, spaces, currentSpaceId, false, null);
    }
}
