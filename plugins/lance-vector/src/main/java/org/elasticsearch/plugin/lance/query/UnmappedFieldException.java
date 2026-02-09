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
 * Exception thrown when a filter references a field not in the Lance field mapping.
 * <p>
 * This triggers fallback to ES post-filtering with a warning to add the field mapping.
 */
public class UnmappedFieldException extends LanceFilterConversionException {

    private final String fieldName;

    /**
     * Constructs a new unmapped field exception.
     *
     * @param fieldName the name of the field that was not found in the mapping
     */
    public UnmappedFieldException(String fieldName) {
        super("Field '" + fieldName + "' not found in index.lance.field_mapping");
        this.fieldName = fieldName;
    }

    /**
     * Gets the name of the unmapped field.
     *
     * @return the field name
     */
    public String getFieldName() {
        return fieldName;
    }
}
