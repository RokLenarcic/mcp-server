# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is a Clojure library for building MCP (Model Context Protocol) servers. It provides a flexible, lightweight framework that allows developers to choose their own JSON parsers, web servers, and async patterns rather than forcing specific technology choices.

## Key Architecture

The library follows a layered architecture:

- **Core protocols** (src/main/org/clojars/roklenarcic/mcp_server/protocol.clj) - Define contracts for content types and responses
- **Main API** (src/main/org/clojars/roklenarcic/mcp_server/core.clj) - Primary public API with helper functions for creating MCP objects
- **Server management** (src/main/org/clojars/roklenarcic/mcp_server/server.clj) - Session management, handler registration, server lifecycle
- **JSON-RPC layer** (src/main/org/clojars/roklenarcic/mcp_server/json_rpc.clj) - JSON-RPC protocol implementation
- **Transport layer** (src/main/org/clojars/roklenarcic/mcp_server/server/) - HTTP, SSE, and stream-based transports
- **Handlers** (src/main/org/clojars/roklenarcic/mcp_server/handler/) - Built-in handlers for MCP operations
- **JSON implementations** (src/main/org/clojars/roklenarcic/mcp_server/json/) - Pluggable JSON serialization adapters

## Session Architecture

Sessions are the central abstraction - they're maps stored in atoms that represent client connections. Sessions contain:
- Server info and capabilities
- JSON serialization implementation
- Dispatch table for RPC handlers
- Tools, prompts, resources, and completion handlers
- Connection-specific context data

## Development Commands

### Testing
```bash
# Run all tests
clj -M:test -m cognitect.test-runner

# Run tests for a specific namespace
clj -M:test -m cognitect.test-runner -n org.clojars.roklenarcic.mcp-server.core-test

# Run a specific test function
clj -M:test -m cognitect.test-runner -v org.clojars.roklenarcic.mcp-server.core-test/request-exchange-test

# Tests are located in test/ directory
# Uses test-check for property-based testing
# Uses matcher-combinators for test assertions
```

### Building
The build system uses tools.build:
```bash
# Build is configured in build.clj
# Currently has some namespace loading issues that need resolution
```

### Examples
```bash
# Run example server (needs tool argument)
clj -X:examples

# Examples are in examples/ directory
# Shows weather service implementation
```

## Key Namespaces

- `org.clojars.roklenarcic.mcp-server.core` - Main API for creating MCP objects and handling requests
- `org.clojars.roklenarcic.mcp-server.server` - Server lifecycle and session management
- `org.clojars.roklenarcic.mcp-server.json-rpc` - JSON-RPC protocol implementation
- `org.clojars.roklenarcic.mcp-server.handler.*` - Built-in handlers for different MCP operations

## Development Notes

- The library uses pluggable JSON serialization - supports Charred, Cheshire, Jsonista, Babashka JSON, and clj-data
- Handlers can return plain values or CompletableFuture instances for async operations
- Middleware pattern is used for customizing the RPC dispatch table
- Error handling uses JSON-RPC error objects with specific error codes
- The library uses clojure.tools.logging for internal logging
- Session modifications (adding/removing tools, prompts, etc.) automatically notify clients

## Common Patterns

- Use `server/make-session` to create session templates
- Add tools, prompts, and resources to sessions using server namespace functions
- Handlers receive an `exchange` parameter for accessing session and client communication
- Content objects are created using helper functions in the core namespace
- Resource handling is protocol-based with pluggable implementations

## Known Issues

- Some test failures related to CompletableFuture method resolution
- Build namespace loading issues in the current setup
- Alpha stage library with some features not yet implemented (pagination, authentication, etc.)