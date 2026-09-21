package com.linagent.web.dto;

import jakarta.validation.constraints.NotBlank;

public record ChatRequest(@NotBlank String content) {
}
