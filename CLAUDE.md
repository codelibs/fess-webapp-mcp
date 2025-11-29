# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is a Maven-based Java plugin for Fess that provides a JSON-RPC 2.0 compliant MCP (Model Context Protocol) API. The plugin enables JSON-RPC-based interactions for executing search and administrative commands through Fess's search engine capabilities.

## Development Commands

### Build and Package
```bash
mvn clean package
```

### Run Tests
```bash
mvn test
```

### Run a Single Test
```bash
mvn test -Dtest=McpApiManagerTest
mvn test -Dtest=McpApiManagerTest#testHandleInitialize
```

### Code Formatting
```bash
mvn formatter:format
```

## Architecture

### Core Components

- **McpApiManager** (`src/main/java/.../api/mcp/McpApiManager.java`): Main API manager extending BaseApiManager, handles JSON-RPC 2.0 requests at `/mcp/*` endpoints. This is the central class containing all MCP protocol handlers.

- **McpApiException** (`src/main/java/.../exception/McpApiException.java`): Custom exception for MCP API errors with ErrorCode support.

- **ErrorCode** (`src/main/java/.../mcp/ErrorCode.java`): Enum defining standard JSON-RPC error codes (-32700, -32600, -32601, -32602, -32603).

### Plugin Registration

The plugin is registered through DI configuration in `src/main/resources/fess_api++.xml`, which registers the McpApiManager component with Fess's WebApiManagerFactory.

### MCP Protocol Methods

The API supports these JSON-RPC methods:
- `initialize` - Returns server capabilities and metadata
- `tools/list` - Lists available tools (search, get_index_stats)
- `tools/call` - Executes tools with parameters
- `resources/list` - Lists available resources
- `prompts/list` - Lists available prompts

### Search Integration

Search functionality integrates with Fess through SearchHelper and SearchRenderData. Query syntax is similar to Lucene (AND default, OR explicit, phrase search with quotes, exclusion with -).

### Configuration

- System property `mcp.content.max.length` controls content truncation (default: 10000 characters)

## Key Dependencies

- Fess search engine framework (provided scope)
- OpenSearch for search operations
- LastaFlute web framework
- Jakarta EE APIs (Servlet, Annotation)
