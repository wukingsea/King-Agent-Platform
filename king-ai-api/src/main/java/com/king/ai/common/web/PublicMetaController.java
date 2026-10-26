package com.king.ai.common.web;

import com.king.ai.common.ApiResponse;
import com.king.ai.config.KingAiProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/public/meta")
@RequiredArgsConstructor
public class PublicMetaController {

    private final KingAiProperties properties;

    @GetMapping
    public ApiResponse<PublicMeta> meta() {
        return ApiResponse.ok(new PublicMeta(properties.getDemo().isEnabled()));
    }

    public record PublicMeta(boolean demoEnabled) {
    }
}
