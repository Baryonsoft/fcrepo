/**
 * Copyright 2013 DuraSpace, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
/**
 *
 */

package org.fcrepo.kernel.services;

import static java.lang.System.currentTimeMillis;
import static org.slf4j.LoggerFactory.getLogger;

import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;

import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.fcrepo.kernel.Transaction;
import org.fcrepo.kernel.TxSession;
import org.fcrepo.kernel.exception.TransactionMissingException;
import org.slf4j.Logger;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * This is part of the strawman implementation for Fedora transactions This
 * service implements a simple {@link Transaction} service which is able to
 * create/commit/rollback {@link Transaction} objects A {@link Scheduled}
 * annotation is used for removing timed out Transactions
 * 
 * @author frank asseg
 */
@Component
public class TransactionService {

    private static final Logger LOGGER = getLogger(TransactionService.class);

    /**
     * A key for looking up the transaction id in a session key-value pair
     */
    static final String FCREPO4_TX_ID = "fcrepo4.tx.id";

    /**
     * TODO since transactions have to be available on all nodes, they have to
     * be either persisted or written to a distributed map or sth, not just this
     * plain hashmap that follows
     */
    private static Map<String, Transaction> transactions =
            new ConcurrentHashMap<String, Transaction>();

    public static final long REAP_INTERVAL = 1000;

    /**
     * Every REAP_INTERVAL milliseconds, check for expired transactions. If the
     * tx is expired, roll it back and remove it from the registry.
     */
    @Scheduled(fixedRate = REAP_INTERVAL)
    public void removeAndRollbackExpired() {
        synchronized (transactions) {
            final Iterator<Entry<String, Transaction>> txs =
                    transactions.entrySet().iterator();
            while (txs.hasNext()) {
                final Transaction tx = txs.next().getValue();
                if (tx.getExpires().getTime() <= currentTimeMillis()) {
                    try {
                        tx.rollback();
                    } catch (final RepositoryException e) {
                        LOGGER.error(
                                "Got exception rolling back expired" +
                                        " transaction {}: {}",
                                        tx, e);
                    }
                    txs.remove();
                }
            }
        }
    }

    /**
     * Create a new Transaction and add it to the currently open ones
     * 
     * @param sess The session to use for this Transaction
     * @return the {@link Transaction}
     */
    public Transaction beginTransaction(final Session sess)
        throws RepositoryException {
        final Transaction tx = new Transaction(sess);
        final String txId = tx.getId();
        transactions.put(txId, tx);
        sess.setNamespacePrefix(FCREPO4_TX_ID, txId);
        return tx;
    }

    /**
     * Retrieve an open {@link Transaction}
     * 
     * @param txid the Id of the {@link Transaction}
     * @return the {@link Transaction}
     */
    public Transaction getTransaction(final String txid)
        throws TransactionMissingException {

        final Transaction tx = transactions.get(txid);

        if (tx == null) {
            throw new TransactionMissingException(
                    "Transaction is not available");
        }

        return tx;
    }

    /**
     * Get the current Transaction for a session
     * 
     * @param session
     * @return
     * @throws TransactionMissingException
     */
    public Transaction getTransaction(final Session session)
        throws TransactionMissingException {

        final String txId = getCurrentTransactionId(session);

        if (txId == null) {
            throw new TransactionMissingException(
                    "Transaction is not available");
        }

        return getTransaction(txId);
    }

    /**
     * Get the current Transaction ID for a session
     * 
     * @param session
     * @return
     */
    public static String getCurrentTransactionId(final Session session) {
        try {
            if (session instanceof TxSession) {
                return ((TxSession) session).getTxId();
            } else {
                return session.getNamespaceURI(FCREPO4_TX_ID);
            }
        } catch (final RepositoryException e) {
            return null;
        }
    }

    /**
     * Check if a Transaction exists
     * 
     * @param txid the Id of the {@link Transaction}
     * @return the {@link Transaction}
     */
    public boolean exists(final String txid) {
        return transactions.containsKey(txid);
    }

    /**
     * Commit a {@link Transaction} with the given id
     * 
     * @param txid the id of the {@link Transaction}
     * @throws RepositoryException
     */
    public Transaction commit(final String txid) throws RepositoryException {
        final Transaction tx = transactions.remove(txid);
        if (tx == null) {
            throw new RepositoryException("Transaction with id " + txid +
                    " is not available");
        }
        tx.commit();
        return tx;
    }

    /**
     * Roll a {@link Transaction} back
     * 
     * @param txid the id of the {@link Transaction}
     * @return the {@link Transaction} object
     * @throws RepositoryException if the {@link Transaction} could not be found
     */
    public Transaction rollback(final String txid) throws RepositoryException {
        final Transaction tx = transactions.remove(txid);
        if (tx == null) {
            throw new RepositoryException("Transaction with id " + txid +
                    " is not available");
        }
        tx.rollback();
        return tx;
    }

}
