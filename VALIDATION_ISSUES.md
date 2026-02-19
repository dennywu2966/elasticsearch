# Lance Vector Plugin - Known Issues

## Issue #1: kNN Query Not Supported (BLOCKING)

**Status**: BLOCKING - Core design issue that prevents standard kNN queries

**Symptom**:
```
[knn] queries are only supported on [dense_vector] fields
```

**Root Cause**:
The `KnnVectorQueryBuilder` in Elasticsearch 9.2.4 has a hard-coded check at line 568:

```java
if (fieldType instanceof DenseVectorFieldType == false) {
    throw new IllegalArgumentException(
        "[" + NAME + "] queries are only supported on [" + DenseVectorFieldMapper.CONTENT_TYPE + "] fields"
    );
}
```

The `LanceVectorFieldType` does NOT extend `DenseVectorFieldType`, so it fails this validation.

**Impact**:
- Cannot use the standard kNN query DSL with lance_vector fields
- The `createKnnQuery()` method in `LanceVectorFieldType` is never called
- The entire kNN search functionality is broken

**Solution Options**:

### Option 1: Extend DenseVectorFieldType (RECOMMENDED)
Make `LanceVectorFieldType` extend `DenseVectorFieldType` and implement all required methods:

```java
public class LanceVectorFieldType extends DenseVectorFieldType {
    // Override createKnnQuery to use Lance implementation
    @Override
    public Query createKnnQuery(...) {
        // Custom Lance kNN implementation
    }

    // Implement other required methods from DenseVectorFieldType
    // - getIndexOptions()
    // - vectorEncodingType()
    // - etc.
}
```

**Pros**:
- Works with standard kNN query DSL
- Native integration with ES filtering, aggregations
- Maintains compatibility with ES query syntax

**Cons**:
- Requires significant refactoring
- May need to implement unused DenseVector methods
- Tighter coupling to ES internals

### Option 2: Custom Query Type
Create a custom "lance_knn" query that bypasses the standard kNN validation:

```java
public class LanceKnnQueryBuilder extends AbstractQueryBuilder<LanceKnnQueryBuilder> {
    // Custom query parser that doesn't validate field type
    // Directly calls LanceVectorFieldType.createKnnQuery()
}
```

**Pros**:
- Independent of ES dense_vector implementation
- Can be implemented incrementally

**Cons**:
- Non-standard query syntax (e.g., "lance_knn" instead of "knn")
- May not work with standard ES features (filtered kNN, hybrid search)
- Requires users to learn custom query syntax

### Option 3: Patch KnnVectorQueryBuilder
Modify the ES core to allow kNN queries on fields with `createKnnQuery()` method:

```java
// Check if field type supports kNN via interface check
if (fieldType instanceof DenseVectorFieldType == false
    && fieldType.hasMethod("createKnnQuery") == false) {
    throw new IllegalArgumentException(...);
}
```

**Pros**:
- Most flexible solution
- Allows any field type to provide kNN functionality

**Cons**:
- Requires modifying ES core code
- May not be accepted upstream
- Maintenance burden for ES version upgrades

## Current State of Plugin

The plugin is **NOT READY** for production use or testing. The core kNN functionality is completely broken due to this validation issue.

## Recommended Next Steps

1. **Choose Option 1** (Extend DenseVectorFieldType)
   - This provides the best user experience and compatibility
   - Aligns with ES architecture patterns

2. **Implement the fix**
   - Refactor `LanceVectorFieldType` to extend `DenseVectorFieldType`
   - Implement all required abstract methods
   - Update field mapper registration

3. **Add comprehensive tests**
   - Unit tests for kNN query creation
   - Integration tests for end-to-end kNN search
   - Tests for filtered kNN and hybrid search

4. **Re-run validation**
   - Complete Phase 4 (Local Filesystem Testing)
   - Complete Phase 4B (Primary Key Verification)
   - Complete Phase 6+ (remaining phases)

## Files Requiring Changes

### Core Plugin Files:
- `plugins/lance-vector/src/main/java/org/elasticsearch/plugin/lance/mapper/LanceVectorFieldMapper.java`
  - `LanceVectorFieldType` class needs to extend `DenseVectorFieldType`

### Test Files:
- `plugins/lance-vector/src/test/java/org/elasticsearch/plugin/lance/query/LanceKnnQueryTests.java`
  - Add tests for kNN query execution
  - Test filtered kNN scenarios
  - Test hybrid search patterns

## Updated Validation Checklist

### Build & Startup
- [x] Plugin builds without errors
- [x] JVM options configured for Arrow
- [x] ES starts successfully
- [x] Lance plugin loaded

### Local Storage
- [x] Lance dataset created (300 vectors, 128 dims)
- [x] IVF-PQ index created
- [x] Index mapping created
- [x] Metadata documents indexed
- [ ] **kNN search returns results** - BLOCKED by Issue #1
- [ ] Path handling correct (file:///tmp/...)
