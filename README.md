MCP WebApp Plugin for Fess
[![Java CI with Maven](https://github.com/codelibs/fess-webapp-mcp/actions/workflows/maven.yml/badge.svg)](https://github.com/codelibs/fess-webapp-mcp/actions/workflows/maven.yml)
==========================

## Overview

This plugin transforms Fess (Enterprise Search Server) into a Model Context Protocol (MCP) server, enabling JSON-RPC 2.0 based interactions with Fess's search capabilities. The MCP API provides a standardized interface for executing search operations, retrieving index statistics, and accessing system information.

## Note

**This project is a work in progress. Features, APIs, and documentation may evolve as development continues. Contributions and feedback are welcome.**

## Download

See [Maven Repository](https://repo1.maven.org/maven2/org/codelibs/fess/fess-webapp-mcp/).

## Installation

1. Download the plugin JAR from the Maven Repository
2. Place it in your Fess plugin directory
3. Restart Fess

For detailed instructions, see the [Plugin Administration Guide](https://fess.codelibs.org/14.19/admin/plugin-guide.html).

## Features

- **JSON-RPC 2.0 Compliance**: Fully compliant with JSON-RPC 2.0 specification
- **MCP Protocol Support**: Implements MCP protocol version 2024-11-05
- **Search Tools**: Execute full-text search queries with advanced filtering
- **Index Statistics**: Retrieve index and system information
- **Resources**: Access to Fess index statistics and configuration
- **Prompts**: Pre-defined search templates for common use cases
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
  "params": {}
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
      "prompts": {}
    },
    "serverInfo": {
      "name": "fess-mcp-server",
      "version": "1.0.0"
    }
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
        "inputSchema": {
          "type": "object",
          "properties": {}
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
        "text": "{\"q\":\"elasticsearch\",\"query_id\":\"...\",\"exec_time\":42,\"page_size\":10,\"record_count\":25,\"data\":[...]}"
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

### 5. prompts/list

List available prompts.

**Request:**
```json
{
  "jsonrpc": "2.0",
  "id": 6,
  "method": "prompts/list",
  "params": {}
}
```

## Search Tool Parameters

The `search` tool supports the following parameters:

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `q` | string | Yes | Query string for full-text search |
| `start` | integer | No | Start position for pagination (default: 0) |
| `offset` | integer | No | Alias for start position |
| `num` | integer | No | Number of results to return (default: 10) |
| `sort` | string | No | Sort order (e.g., "score.desc", "last_modified.desc") |
| `fields.label` | array | No | Specific labels to filter by |
| `lang` | string | No | Language filter |
| `preference` | string | No | Search preference |

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
    "params": {}
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

