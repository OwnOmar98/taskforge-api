package com.taskforge.integration;

public enum WebhookDeliveryStatus {

	PENDING,
	IN_FLIGHT,
	DELIVERED,
	// Gave up after the maximum number of attempts - the dead-letter state,
	// only left again by an explicit admin redelivery.
	FAILED

}
