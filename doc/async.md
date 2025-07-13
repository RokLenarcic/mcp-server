# Synchronous and Asynchronous Execution

By default, the MCP server operates synchronously. This guide explains how to enable asynchronous execution when needed.

## Returning CompletableFuture Objects

The simplest way to make your handlers asynchronous is to return `CompletableFuture` objects from your tool handlers. You can generate these futures using any method you prefer:

- **Promesa**: A Clojure library for promises and futures
- **Java Executors**: Built-in Java concurrent execution
- **core.async**: Clojure's asynchronous programming library with CompletableFuture integration

### Example

```clojure
(defn async-tool-handler [exchange arguments]
  (CompletableFuture/supplyAsync
    (fn []
      ;; Your async work here
      "Result from async operation")))
```

### Limitations

When you return CompletableFuture objects, only your specific handlers become asynchronous. The internal JSON-RPC handlers (such as ping, client responses, and initialization) will still run synchronously because they're not exposed to your code.

## Making All Handlers Asynchronous

To make all dispatch table handlers asynchronous, you can wrap them during or after dispatch table creation. This approach affects both your handlers and the internal system handlers.

### Using Default Executor

```clojure
(server/add-async-to-dispatch (server/make-dispatch))
```

This wraps all handlers with a virtual thread executor (if available in your Java version) or falls back to a cached thread pool executor.

### Using Custom Executor

```clojure
(server/add-async-to-dispatch (server/make-dispatch) my-executor)
```

Replace `my-executor` with your preferred `ExecutorService` implementation.

### How It Works

<details>
<summary>Implementation Details</summary>

The `add-async-to-dispatch` function uses the middleware system to wrap all dispatch handlers:

```clojure
(rpc/with-middleware dispatch [[rpc/wrap-executor executor]])
```

This approach ensures consistent asynchronous behavior across all handlers in your MCP server.

</details>
