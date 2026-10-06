package com.smartroute.events;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs a block in a new transaction that the caller can let fail.
 *
 * <p>Needed because a constraint violation marks its transaction as rollback-only: the exception has to
 * leave the transaction boundary before it can be caught, otherwise the commit itself fails with a
 * confusing "transaction silently rolled back".
 */
@Component
class TransactionalRunner {

    @Transactional
    void inTransaction(Runnable work) {
        work.run();
    }
}
