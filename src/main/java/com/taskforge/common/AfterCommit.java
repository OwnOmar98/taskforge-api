package com.taskforge.common;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

// Cache eviction (and anything else that must see the post-commit state, not
// the still-uncommitted one) needs to run after the enclosing transaction
// actually commits, not inline mid-transaction - running it inline leaves a
// window where a concurrent read repopulates the cache with the pre-change
// value, which nothing evicts again afterward. Falls back to running
// immediately when there's no active transaction (a direct, non-@Transactional
// call), rather than silently dropping the action.
public final class AfterCommit {

	private AfterCommit() {
	}

	public static void run(Runnable action) {
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					action.run();
				}
			});
		}
		else {
			action.run();
		}
	}

}
