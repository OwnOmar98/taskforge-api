package com.taskforge.auth.dto;

import java.util.UUID;

public record MeResponse(UUID id, String email, String fullName) {
}
