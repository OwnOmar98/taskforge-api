package com.taskforge.realtime;

import java.util.UUID;

// What travels over Redis between instances: the event plus who it's for, so
// each receiving instance can route it to that user's local streams.
record UserEventMessage(UUID userId, UserEvent event) {
}
