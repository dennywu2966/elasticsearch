# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build and Development Commands

### Building the Project

Elasticsearch uses Gradle for its build system. The project requires JDK 21.

```bash
# Build a local distribution
./gradlew localDistro

# Build platform-specific distributions
./gradlew :distribution:archives:linux-tar:assemble
./gradlew :distribution:archives:darwin-tar:assemble
./gradlew :distribution:archives:windows-zip:assemble

# Build Docker image
./gradlew buildDockerImage
```

### Running Elasticsearch

```bash
# Run Elasticsearch locally from source
./gradlew run

# Run with debugging enabled (connects on port 5007)
./gradlew run --debug-jvm

# Run without security
./gradlew run -Dtests.es.xpack.security.enabled=false

# Run with trial license (enables security)
./gradlew run -Drun.license_type=trial
# Default credentials: elastic-admin / elastic-password
```

### Testing

```bash
# Run all verification tasks (unit tests, integration tests, static checks)
./gradlew check

# Run precommit checks only
./gradlew precommit

# Run unit tests for a specific project
./gradlew :server:test

# Run a single test class
./gradlew :server:test --tests org.elasticsearch.package.ClassName

# Run tests in a package
./gradlew :server:test --tests 'org.elasticsearch.package.*'

# Run a specific test method
./gradlew :server:test --tests org.elasticsearch.package.ClassName.methodName

# Run tests with specific seed (for reproducibility)
./gradlew test -Dtests.seed=DEADBEEF

# Run YAML REST tests
./gradlew :rest-api-spec:yamlRestTest

# Run Java REST tests
./gradlew :modules:mapper-extras:javaRestTest

# Run integration tests
./gradlew internalClusterTest

# Debug tests (connects on port 5005)
./gradlew :server:test --debug-jvm

# Debug the server during REST tests (connects on port 5007)
./gradlew :rest-api-spec:yamlRestTest --debug-server-jvm
```

### Code Quality

```bash
# Format code with Spotless
./gradlew spotlessApply

# Check formatting
./gradlew spotlessJavaCheck

# Format specific project
./gradlew server:spotlessApply
```

### Backward Compatibility Tests

```bash
# Run all BWC tests
./gradlew bwcTest

# Test specific version
./gradlew v5.3.2#bwcTest
```

## High-Level Architecture

### Project Structure

- **`server/`**: Core Elasticsearch server containing all fundamental components
- **`modules/`**: Features shipped with Elasticsearch by default but not built into the server (e.g., `ingest-common`, `lang-painless`, `analysis-common`)
- **`plugins/`**: Optional plugins for subset of users (e.g., `discovery-gce`, `analysis-icu`, `repository-hdfs`)
- **`x-pack/`**: Commercially licensed code with Security, ML, Monitoring, etc.
- **`libs/`**: Internal libraries used across the project (not general-purpose)
- **`client/`**: REST client and sniffer implementations
- **`distribution/`**: Build artifacts (tar, zip, deb, rpm, Docker images)
- **`qa/`**: Integration tests requiring multiple modules/plugins or special configurations
- **`test/`**: Test framework and fixtures used across the project
- **`docs/`**: Documentation in AsciiDoc format
- **`build-tools/`**: Published Gradle plugins for third-party plugin authors
- **`build-tools-internal/`**: Elasticsearch-specific build logic
- **`build-conventions/`**: Build conventions applied to all projects

### Key Architectural Layers

#### REST Layer (HTTP)
- **Entry point**: External clients interact via REST API over HTTP
- **Handler registration**: All REST handlers registered in `ActionModule#initRestHandlers`
- **Naming convention**: `Rest*Action` classes (e.g., `RestGetAction`, `RestBulkAction`)
- **Flow**: `RestController` → `BaseRestHandler` → `Rest*Action#prepareRequest` → `RestChannelConsumer`
- **HTTP implementation**: Netty-based via `Netty4HttpServerTransport`
- **Path**: `docs/internal/GeneralArchitectureGuide.md` has detailed REST layer documentation

#### Transport Layer (TCP)
- **Purpose**: Internal node-to-node communication using custom binary protocol
- **Action registration**: Configured in `ActionModule#setupActions`
- **Naming convention**: `Transport*Action` classes corresponding to `Rest*Action`
- **Action types**:
  - `TransportMasterNodeAction`: Executes on master node (cluster state updates)
  - `TransportReplicationAction`: Executes on primary then replica shards
  - `TransportSingleShardAction`: Executes read operation on specific shard
  - `TransportNodesAction`: Executes on many nodes, collates responses
  - `TransportLocalClusterStateAction`: Reads cluster state on coordinating node
- **Entry point**: `NodeClient` invokes actions locally
- **Transport implementation**: Netty-based via `Netty4Transport`
- **Connection model**: Pool of ~13 channels per node pair (bidirectional)

#### Key Package Locations
- **Actions**: `server/src/main/java/org/elasticsearch/action/`
- **Cluster coordination**: `server/src/main/java/org/elasticsearch/cluster/`
- **Index/Shard management**: `server/src/main/java/org/elasticsearch/index/`
- **Indices service**: `server/src/main/java/org/elasticsearch/indices/`
- **Networking**: `server/src/main/java/org/elasticsearch/http/` and `server/src/main/java/org/elasticsearch/transport/`
- **Discovery**: `server/src/main/java/org/elasticsearch/discovery/`
- **Gateway/Recovery**: `server/src/main/java/org/elasticsearch/gateway/`
- **Snapshots**: `server/src/main/java/org/elasticsearch/snapshots/`

### Cluster State and Coordination
- **Cluster state**: Immutable metadata maintained by master, published to all nodes
- **Master election**: Quorum-based using Raft-like consensus
- **Master service**: Processes cluster state updates sequentially on master node
- **Cluster state application**: Listeners react to cluster state changes on each node
- **Persistence**: Cluster metadata persisted on disk, survives restarts

### Data Path: Indexing and Storage
- **Write path**: REST → Transport → Primary Shard → Replica Shards
- **Engine**: `InternalEngine` manages Lucene IndexWriter and translog
- **Translog**: Write-ahead log for durability, fsync'd before acknowledging writes
- **Refresh**: Makes documents searchable (in-memory Lucene segments)
- **Flush**: Persists Lucene segments to disk, truncates translog
- **Store**: Manages Lucene directory and file references

### Shard Allocation
- **Allocator**: `DesiredBalanceShardsAllocator` with `DesiredBalanceComputer` and `DesiredBalanceReconciler`
- **Allocation decisions**: `AllocationDeciders` enforce constraints (disk thresholds, filtering, retries)
- **Rebalancing**: Weight-based algorithm considers disk usage, write load, shard count
- **Recovery**: Shards recovered from local data, peer nodes, or snapshots

### Networking and Threading
- **Netty**: Async I/O framework for HTTP and Transport
- **Event loops**: Small number of threads (one per CPU core) handle all connections
- **Thread pools**: Multiple pools for different workload types (search, write, management)
- **Critical rule**: Never block Netty event loop threads; fork heavy work to thread pools
- **ThreadContext**: Carries request context (headers, user info) across threads and nodes

## Important Development Guidelines

### Code Style
- Java indent: 4 spaces
- Line width: 140 characters (76 for documentation code snippets)
- Use Spotless for formatting: `./gradlew spotlessApply`
- No wildcard imports
- Boolean negation: Use `foo == false` instead of `!foo`

### Testing Best Practices
- Prefer unit tests over integration tests when possible
- Use `ESTestCase` for unit tests
- Use `ESRestTestCase` for integration tests via REST API
- Use `ESSingleNodeTestCase` for single-node cluster tests
- Use `ESIntegTest Case` only when you need multi-node distributed behavior
- Write YAML REST tests for API compatibility across clients

### License Headers
- Core Elasticsearch (outside `x-pack/`): Tri-license (Elastic License 2.0, AGPL v3, SSPL v1)
- X-Pack code: Elastic License 2.0 only
- IntelliJ automatically adds correct headers based on file location

### Dependency Management
- All versions declared in `build-tools-internal/version.properties`
- Update `gradle/verification-metadata.xml` for new/updated dependencies
- Use component metadata rules in `ComponentMetadataRulesPlugin` to control transitives
- Run `./gradlew --write-verification-metadata sha256 precommit` when adding dependencies

### Settings
- Declare in `Setting` class with appropriate `Property` flags
- Dynamic settings require `Property.Dynamic` and a volatile variable
- Register dynamic settings with `ClusterSettings#initializeAndWatch()`
- Scope settings: `Property.IndexScope` or `Property.NodeScope`
- Plugins contribute settings via `Plugin#getSettings()`

### Logging
- Use `Logger logger = LogManager.getLogger(ClassName.class)`
- Use placeholders: `logger.debug("operation failed [{}] times", count)`
- Log levels:
  - `TRACE`: Very verbose debugging (disabled by default)
  - `DEBUG`: Diagnostic information (disabled by default)
  - `INFO`: Important cluster events (enabled by default)
  - `WARN`: Events requiring investigation (enabled by default)
  - `ERROR`: Severe errors indicating degraded state (enabled by default)

### Javadoc
- Always add Javadoc to new public classes and methods
- Document the "why", not the "how"
- Skip Javadoc for trivial getters/setters
- Add examples when helpful
- Use `@link` to reference related code

### Task Management
- Tasks track long-running operations (TransportActions, cluster state publication)
- Register via `TaskManager#register`, unregister when complete
- `CancellableTask` for operations that can be cancelled
- Parent-child relationships track distributed operations
- Persistent tasks survive node failures (registered in cluster state)

### Security Considerations
- Avoid command injection, XSS, SQL injection vulnerabilities
- Validate at system boundaries (user input, external APIs)
- Transport actions represent security boundaries (authorization checked)
- Action names encode permissions (e.g., `indices:data/write/bulk`)

## Common Patterns and Idioms

### ActionListener
- Core async callback pattern throughout the codebase
- `onResponse()` for success, `onFailure()` for errors
- Many utility methods for composing, chaining, wrapping listeners
- See `ActionListener` Javadocs for comprehensive examples

### Reference Counting
- Netty ByteBuf uses reference counting for memory management
- Always release buffers when done (leak detection enabled in tests)
- Engine components use reference counting for lifecycle management

### Versioning
- Node versions: Major.Minor.Patch (e.g., 8.3.1)
- Transport compatibility: All nodes in major version + last minor of previous major
- Index format compatibility: Current major can read previous major
- See `docs/internal/Versioning.md` for details

## Architecture Documentation

For deeper architectural understanding, see:
- `docs/internal/GeneralArchitectureGuide.md` - REST/Transport layers, settings, serialization
- `docs/internal/DistributedArchitectureGuide.md` - Networking, cluster coordination, replication, allocation, recovery, snapshots, tasks
- `docs/internal/Versioning.md` - Version concepts and compatibility

## Important Files

- `TESTING.asciidoc` - Comprehensive testing guide
- `BUILDING.md` - Build system details and guidelines
- `CONTRIBUTING.md` - Contribution guidelines, code style, review process
- `TRACING.md` - APM tracing integration
