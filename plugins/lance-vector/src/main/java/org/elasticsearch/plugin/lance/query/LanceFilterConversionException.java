/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.plugin.lance.query;

/**
 * Base exception for Lance filter conversion errors.
 * <p>
 * Thrown when an Elasticsearch filter cannot be converted to a Lance SQL WHERE clause.
 * This is typically used to trigger fallback to ES post-filtering.
 */
public class LanceFilterConversionException extends Exception {

    /**
     * Constructs a new filter conversion exception with the specified detail message.
     *
     * @param message the detail message
     */
    public LanceFilterConversionException(String message) {
        super(message);
    }

    /**
     * Constructs a new filter conversion exception with the specified detail message and cause.
     *
     * @param message the detail message
     * @param cause   the cause
     */
    public LanceFilterConversionException(String message, Throwable cause) {
        super(message, cause);
    }
}
