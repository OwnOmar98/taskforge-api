package com.taskforge.common.exception.support;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.taskforge.common.exception.ConflictException;
import com.taskforge.common.exception.GeneralErrorCode;
import com.taskforge.common.exception.ResourceNotFoundException;

@RestController
@RequestMapping("/test")
public class TestExceptionController {

	@GetMapping("/not-found")
	public void notFound() {
		throw new ResourceNotFoundException(GeneralErrorCode.RESOURCE_NOT_FOUND, "Widget 42 not found");
	}

	@GetMapping("/conflict")
	public void conflict() {
		throw new ConflictException(GeneralErrorCode.RESOURCE_CONFLICT, "Widget 42 already exists");
	}

	@PostMapping("/validate")
	public void validate(@Valid @RequestBody TestRequest request) {
	}

	@GetMapping("/boom")
	public void boom() {
		throw new RuntimeException("boom");
	}

	public record TestRequest(@NotBlank String name) {
	}

}
