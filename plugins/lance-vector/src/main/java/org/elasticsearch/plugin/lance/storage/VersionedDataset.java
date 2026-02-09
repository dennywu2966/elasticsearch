/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.plugin.lance.storage;

import org.apache.arrow.vector.VarCharVector;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Lock-free dataset wrapper supporting atomic version swaps.
 * <p>
 * Wraps a LanceDataset with a version number and supports atomic replacement
 * of the underlying dataset. Reads are lock-free via AtomicReference.
 * <p>
 * Used for near-real-time refresh: when Lance detects a manifest change,
 * a new dataset is loaded and swapped in atomically.
 * <p>
 * The previous dataset is returned from swap() — the caller is responsible
 * for closing it after all in-flight reads have completed.
 */
public class VersionedDataset implements LanceDataset {
    private static final Logger logger = LogManager.getLogger(VersionedDataset.class);

    private final AtomicReference<LanceDataset> delegate;
    private volatile long version;

    /**
     * Create a versioned dataset wrapper.
     *
     * @param initial The initial dataset
     * @param version The initial version number
     */
    public VersionedDataset(LanceDataset initial, long version) {
        this.delegate = new AtomicReference<>(initial);
        this.version = version;
    }

    /**
     * Atomically swap to a new dataset version.
     * <p>
     * Reads in progress will continue using the old dataset until they
     * complete. New reads will use the new dataset.
     *
     * @param newDataset The new dataset
     * @param newVersion The new version number
     * @return The old dataset (caller should close it after grace period)
     */
    public LanceDataset swap(LanceDataset newDataset, long newVersion) {
        LanceDataset old = delegate.getAndSet(newDataset);
        long oldVersion = this.version;
        this.version = newVersion;
        logger.info("Swapped Lance dataset: version {} -> {}", oldVersion, newVersion);
        return old;
    }

    /** Get the current version number. */
    public long version() {
        return version;
    }

    @Override
    public List<Candidate> search(float[] queryVector, int k, String columnName) {
        return delegate.get().search(queryVector, k, columnName);
    }

    @Override
    public List<Candidate> search(float[] queryVector, int k, String columnName, VarCharVector idFilter) throws IOException {
        return delegate.get().search(queryVector, k, columnName, idFilter);
    }

    @Override
    public List<Candidate> search(float[] queryVector, int k, String columnName, int nprobes) throws IOException {
        return delegate.get().search(queryVector, k, columnName, nprobes);
    }

    @Override
    public List<Candidate> search(float[] queryVector, int k, String columnName, String sqlFilter) throws IOException {
        return delegate.get().search(queryVector, k, columnName, sqlFilter);
    }

    @Override
    public String uri() {
        return delegate.get().uri();
    }

    @Override
    public int dims() {
        return delegate.get().dims();
    }

    @Override
    public void close() throws IOException {
        delegate.get().close();
    }
}
