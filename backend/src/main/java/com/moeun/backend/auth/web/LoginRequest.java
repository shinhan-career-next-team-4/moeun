package com.moeun.backend.auth.web;

import jakarta.validation.constraints.NotEmpty;

public record LoginRequest(@NotEmpty String loginId, @NotEmpty String password) {
}
