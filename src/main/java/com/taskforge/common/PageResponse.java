package com.taskforge.common;

import java.util.List;

import org.springframework.data.domain.Page;

// A stable, versioned response shape instead of exposing Spring Data's own
// Page/PageImpl directly - that type isn't meant for direct JSON
// serialization and its shape isn't part of any API contract we control.
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

	public static <T> PageResponse<T> from(Page<T> page) {
		return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements(),
				page.getTotalPages());
	}

}
