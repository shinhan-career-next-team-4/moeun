package com.moeun.backend.auth.web;

import jakarta.validation.constraints.NotBlank;

public record LoginRequest(@NotBlank String loginId, @NotBlank String password) {
}
