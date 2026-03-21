MCP WebApp Plugin for Fess
[![Java CI with Maven](https://github.com/codelibs/fess-webapp-mcp/actions/workflows/maven.yml/badge.svg)](https://github.com/codelibs/fess-webapp-mcp/actions/workflows/maven.yml)
==========================

## Overview

This plugin transforms Fess (Enterprise Search Server) into a Model Context Protocol (MCP) server, enabling JSON-RPC 2.0 based interactions with Fess's search capabilities. The MCP API provides a standardized interface for executing search operations, retrieving index statistics, and accessing system information.

## Note

**This project is a work in progress. Features, APIs, and documentation may evolve as development continues. Contributions and feedback are welcome.**

## Download

See [Maven Repository](https://repo1.maven.org/maven2/org/codelibs/fess/fess-webapp-mcp/).

## Requirements

- Fess 15.x or later
- Java 21 or later

## Installation

1. Download the plugin JAR from the Maven Repository
2. Place it in your Fess plugin directory
3. Restart Fess

For detailed instructions, see the [Plugin Administration Guide](https://fess.codelibs.org/14.19/admin/plugin-guide.html).

## Features

- **JSON-RPC 2.0 Compliance**: Fully compliant with JSON-RPC 2.0 specification including batch requests
- **MCP Protocol Support**: Implements MCP protocol version 2024-11-05
- **Search Tools**: Execute full-text search queries with advanced filtering
- **Suggest Tool**: Autocomplete/suggestion queries via Fess suggest engine
- **Get Document Tool**: Retrieve individual documents by ID
- **Index Statistics**: Retrieve index and system information
- **Resources**: Access to Fess index statistics and configuration
- **Resource Templates**: Parameterized URI templates (RFC 6570) for dynamic resource access
- **Prompts**: Pre-defined search templates for common use cases
- **Completion**: Argument autocomplete using Fess suggest for prompt arguments
- **Logging Control**: Dynamically set server log level via `logging/setLevel`
- **Ping**: Liveness check endpoint
- **Extensible Architecture**: Easy to add new tools and capabilities

## API Endpoint

The MCP API is available at:

```
POST http://<fess-server>:<port>/mcp
```

All requests must be sent as JSON-RPC 2.0 formatted POST requests.

## Available Methods

### 1. initialize

Initialize the MCP session and retrieve server capabilities.

**Request:**
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "initialize",
  "params": {
    "protocolVersion": "2024-11-05",
    "capabilities": {},
    "clientInfo": {
      "name": "example-client",
      "version": "1.0.0"
    }
  }
}
```

**Response:**
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "protocolVersion": "2024-11-05",
    "capabilities": {
      "tools": {},
      "resources": {},
      "prompts": {},
      "logging": {},
      "completions": {}
    },
    "serverInfo": {
      "name": "fess-mcp-server",
      "version": "1.0.0"
    },
    "instructions": "This MCP server provides access to Fess search capabilities. Use the search tool to query documents, suggest tool for autocomplete, and get_document to retrieve specific documents by ID."
  }
}
```

### 2. tools/list

List all available tools.

**Request:**
```json
{
  "jsonrpc": "2.0",
  "id": 2,
  "method": "tools/list",
  "params": {}
}
```

**Response:**
```json
{
  "jsonrpc": "2.0",
  "id": 2,
  "result": {
    "tools": [
      {
        "name": "search",
        "description": "Search documents via Fess",
        "annotations": {
          "readOnlyHint": true,
          "idempotentHint": true
        },
        "inputSchema": {
          "type": "object",
          "properties": {
            "q": {
              "type": "string",
              "description": "query string"
            },
            "num": {
              "type": "integer",
              "description": "number of results"
            },
            "start": {
              "type": "integer",
              "description": "start position"
            },
            "sort": {
              "type": "string",
              "description": "sort order"
            }
          },
          "required": ["q"]
        }
      },
      {
        "name": "get_index_stats",
        "description": "Get index statistics and information",
        "annotations": {
          "readOnlyHint": true,
          "idempotentHint": true
        },
        "inputSchema": {
          "type": "object",
          "properties": {}
        }
      },
      {
        "name": "suggest",
        "description": "Get search suggestions/autocomplete for a query prefix",
        "annotations": {
          "readOnlyHint": true,
          "idempotentHint": true
        },
        "inputSchema": {
          "type": "object",
          "properties": {
            "q": {
              "type": "string",
              "description": "query prefix for autocomplete"
            },
            "num": {
              "type": "integer",
              "description": "number of suggestions"
            }
          },
          "required": ["q"]
        }
      },
      {
        "name": "get_document",
        "description": "Retrieve a Fess document by its document ID",
        "annotations": {
          "readOnlyHint": true,
          "idempotentHint": true
        },
        "inputSchema": {
          "type": "object",
          "properties": {
            "doc_id": {
              "type": "string",
              "description": "document ID to retrieve"
            }
          },
          "required": ["doc_id"]
        }
      }
    ]
  }
}
```

### 3. tools/call

Execute a specific tool.

**Request (Search):**
```json
{
  "jsonrpc": "2.0",
  "id": 3,
  "method": "tools/call",
  "params": {
    "name": "search",
    "arguments": {
      "q": "elasticsearch",
      "num": 10,
      "start": 0
    }
  }
}
```

**Response:**
```json
{
  "jsonrpc": "2.0",
  "id": 3,
  "result": {
    "content": [
      {
        "type": "text",
        "text": "**Title**: Introduction to Elasticsearch\n**URL**: https://example.com/elasticsearch-intro\n**Score**: 1.234\n\nElasticsearch is a distributed, RESTful search and analytics engine..."
      },
      {
        "type": "text",
        "text": "**Title**: Elasticsearch Tutorial\n**URL**: https://example.com/es-tutorial\n**Score**: 1.123\n\nLearn how to use Elasticsearch for full-text search..."
      }
    ]
  }
}
```

**Request (Index Stats):**
```json
{
  "jsonrpc": "2.0",
  "id": 4,
  "method": "tools/call",
  "params": {
    "name": "get_index_stats",
    "arguments": {}
  }
}
```

**Request (Suggest):**
```json
{
  "jsonrpc": "2.0",
  "id": 5,
  "method": "tools/call",
  "params": {
    "name": "suggest",
    "arguments": {"q": "machine", "num": 5}
  }
}
```

**Request (Get Document):**
```json
{
  "jsonrpc": "2.0",
  "id": 6,
  "method": "tools/call",
  "params": {
    "name": "get_document",
    "arguments": {"doc_id": "abc123"}
  }
}
```

### 4. resources/list

List available resources.

**Request:**
```json
{
  "jsonrpc": "2.0",
  "id": 5,
  "method": "resources/list",
  "params": {}
}
```

**Response:**
```json
{
  "jsonrpc": "2.0",
  "id": 5,
  "result": {
    "resources": [
      {
        "uri": "fess://index/stats",
        "name": "Index Statistics",
        "description": "Fess index statistics and configuration information",
        "mimeType": "application/json"
      }
    ]
  }
}
```

### 5. resources/read

Read a specific resource by URI.

**Request:**
```json
{
  "jsonrpc": "2.0",
  "id": 6,
  "method": "resources/read",
  "params": {
    "uri": "fess://index/stats"
  }
}
```

**Response:**
```json
{
  "jsonrpc": "2.0",
  "id": 6,
  "result": {
    "contents": [
      {
        "uri": "fess://index/stats",
        "mimeType": "application/json",
        "text": "{\"index\":{\"index_name\":\"fess.search\",\"document_count\":1234},\"config\":{\"max_page_size\":100},\"system\":{\"memory\":{\"total_bytes\":1073741824,\"free_bytes\":536870912,\"used_bytes\":536870912,\"max_bytes\":2147483648}}}"
      }
    ]
  }
}
```

### 6. prompts/list

List available prompts.

**Request:**
```json
{
  "jsonrpc": "2.0",
  "id": 7,
  "method": "prompts/list",
  "params": {}
}
```

**Response:**
```json
{
  "jsonrpc": "2.0",
  "id": 7,
  "result": {
    "prompts": [
      {
        "name": "basic_search",
        "description": "Perform a basic search with a query string",
        "arguments": [
          {"name": "query", "description": "The search query", "required": true}
        ]
      },
      {
        "name": "advanced_search",
        "description": "Perform an advanced search with filters and sorting",
        "arguments": [
          {"name": "query", "description": "The search query", "required": true},
          {"name": "sort", "description": "Sort order", "required": false},
          {"name": "num", "description": "Number of results to return", "required": false}
        ]
      }
    ]
  }
}
```

### 7. prompts/get

Get a prompt with arguments substituted.

**Request:**
```json
{
  "jsonrpc": "2.0",
  "id": 8,
  "method": "prompts/get",
  "params": {
    "name": "basic_search",
    "arguments": {
      "query": "machine learning"
    }
  }
}
```

**Response:**
```json
{
  "jsonrpc": "2.0",
  "id": 8,
  "result": {
    "messages": [
      {
        "role": "user",
        "content": {
          "type": "text",
          "text": "Please search for: machine learning"
        }
      }
    ]
  }
}
```

### 8. ping

Liveness check.

**Request:**
```json
{"jsonrpc": "2.0", "id": 9, "method": "ping", "params": {}}
```

**Response:**
```json
{"jsonrpc": "2.0", "id": 9, "result": {}}
```

### 9. resources/templates/list

List parameterized resource templates (RFC 6570 URI templates).

**Request:**
```json
{"jsonrpc": "2.0", "id": 10, "method": "resources/templates/list", "params": {}}
```

**Response:**
```json
{
  "jsonrpc": "2.0",
  "id": 10,
  "result": {
    "resourceTemplates": [
      {
        "uriTemplate": "fess://document/{doc_id}",
        "name": "Document by ID",
        "description": "Retrieve a Fess document by its document ID",
        "mimeType": "application/json"
      }
    ]
  }
}
```

### 10. completion/complete

Request argument autocomplete using Fess suggest.

**Request:**
```json
{
  "jsonrpc": "2.0",
  "id": 11,
  "method": "completion/complete",
  "params": {
    "ref": {"type": "ref/prompt", "name": "basic_search"},
    "argument": {"name": "query", "value": "mach"}
  }
}
```

**Response:**
```json
{
  "jsonrpc": "2.0",
  "id": 11,
  "result": {
    "completion": {
      "values": ["machine learning", "machine translation"],
      "total": 2,
      "hasMore": false
    }
  }
}
```

### 11. logging/setLevel

Set the minimum log level for server-emitted log messages.

**Request:**
```json
{"jsonrpc": "2.0", "id": 12, "method": "logging/setLevel", "params": {"level": "debug"}}
```

**Response:**
```json
{"jsonrpc": "2.0", "id": 12, "result": {}}
```

Valid levels: `debug`, `info`, `notice`, `warning`, `error`, `critical`, `alert`, `emergency`

## Search Tool Parameters

The `search` tool supports the following parameters:

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `q` | string | Yes | Query string for full-text search |
| `start` | integer | No | Start position for pagination (default: 0) |
| `offset` | integer | No | Alias for start position |
| `num` | integer | No | Number of results to return (default: 3) |
| `sort` | string | No | Sort order (e.g., "score.desc", "last_modified.desc") |
| `fields.label` | array | No | Specific labels to filter by |
| `lang` | string | No | Language filter |
| `preference` | string | No | Search preference |

## Suggest Tool Parameters

The `suggest` tool supports the following parameters:

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `q` | string | Yes | Query prefix for autocomplete |
| `num` | integer | No | Number of suggestions (default: 10) |

## Get Document Tool Parameters

The `get_document` tool supports the following parameters:

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `doc_id` | string | Yes | Document ID to retrieve |

### Query Syntax

The search tool supports Lucene-like query syntax:

| Syntax | Description | Example |
|--------|-------------|---------|
| `term1 term2` | AND search (default) | `machine learning` |
| `term1 OR term2` | OR search | `cat OR dog` |
| `"phrase"` | Phrase search | `"machine learning"` |
| `-term` | Exclude term | `python -java` |

## Usage Examples

### Using curl

```bash
# Initialize
curl -X POST http://localhost:8080/mcp \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "initialize",
    "params": {
      "protocolVersion": "2024-11-05",
      "capabilities": {},
      "clientInfo": {
        "name": "curl-test",
        "version": "1.0.0"
      }
    }
  }'

# Search documents
curl -X POST http://localhost:8080/mcp \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 2,
    "method": "tools/call",
    "params": {
      "name": "search",
      "arguments": {
        "q": "machine learning",
        "num": 5
      }
    }
  }'

# Get index statistics
curl -X POST http://localhost:8080/mcp \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 3,
    "method": "tools/call",
    "params": {
      "name": "get_index_stats",
      "arguments": {}
    }
  }'
```

### Using Python

```python
import requests
import json

url = "http://localhost:8080/mcp"
headers = {"Content-Type": "application/json"}

# Search request
search_request = {
    "jsonrpc": "2.0",
    "id": 1,
    "method": "tools/call",
    "params": {
        "name": "search",
        "arguments": {
            "q": "elasticsearch",
            "num": 10
        }
    }
}

response = requests.post(url, headers=headers, data=json.dumps(search_request))
result = response.json()
print(json.dumps(result, indent=2))
```

### Using with Claude Desktop

To use this MCP server with Claude Desktop, add the following configuration to your Claude Desktop config file:

**macOS:** `~/Library/Application Support/Claude/claude_desktop_config.json`
**Windows:** `%APPDATA%\Claude\claude_desktop_config.json`

```json
{
  "mcpServers": {
    "fess": {
      "command": "npx",
      "args": ["-y", "mcp-remote", "http://localhost:8080/mcp"]
    }
  }
}
```

After adding the configuration, restart Claude Desktop to connect to the Fess MCP server.

## Batch Requests

The API supports JSON-RPC 2.0 batch requests. Send an array of requests to receive an array of responses:

```bash
curl -X POST http://localhost:8080/mcp \
  -H "Content-Type: application/json" \
  -d '[
    {"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {"protocolVersion": "2024-11-05", "capabilities": {}, "clientInfo": {"name": "curl-test", "version": "1.0.0"}}},
    {"jsonrpc": "2.0", "id": 2, "method": "tools/list", "params": {}}
  ]'
```

## Error Handling

The API returns standard JSON-RPC 2.0 error responses:

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "error": {
    "code": -32601,
    "message": "Unknown method: invalid_method"
  }
}
```

### Error Codes

| Code | Message | Description |
|------|---------|-------------|
| -32700 | Parse error | Invalid JSON was received |
| -32600 | Invalid Request | The JSON sent is not a valid Request object |
| -32601 | Method not found | The method does not exist |
| -32602 | Invalid params | Invalid method parameter(s) |
| -32603 | Internal error | Internal JSON-RPC error |
| -32002 | Resource not found | The requested resource does not exist |

## Configuration

The following system properties can be configured in Fess:

| Property | Default | Description |
|----------|---------|-------------|
| `mcp.content.max.length` | 10000 | Maximum length of search result content in characters |
| `mcp.highlight.fragment.size` | 500 | Size of highlight fragments in characters |
| `mcp.highlight.num.of.fragments` | 3 | Number of highlight fragments per result |
| `mcp.default.page.size` | 3 | Default number of search results |

## Development

### Building from Source

```bash
mvn clean package
```

### Running Tests

```bash
mvn test
```

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

## License

Apache License 2.0

## Support

For issues and questions, please use the [GitHub Issues](https://github.com/codelibs/fess-webapp-mcp/issues).

