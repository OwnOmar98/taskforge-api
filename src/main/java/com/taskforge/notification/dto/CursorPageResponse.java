package com.taskforge.notification.dto;

import java.util.List;

// No totalElements/totalPages, unlike PageResponse: computing a total count
// would need a separate COUNT query, which defeats the point of switching to
// keyset pagination in the first place. hasMore/nextCursor is all a feed-style
// "load more" UI ever actually needs.
public record CursorPageResponse<T>(List<T> content, String nextCursor, boolean hasMore) {
}
