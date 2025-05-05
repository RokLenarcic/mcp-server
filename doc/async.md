# Sync/Async

The default mode of operation of the tools and transports is sync. There's several ways to change that.

### Return CompletableFuture

Your tool handlers can return a `CompletableFuture`. How those futures are generated is up to you:

- Promesa
- java Executors
- core.async + CompletableFuture as promise

The downside here is that you'll have to have code there that will work with Executors and 
the JSON RPC handlers that are not exposed to you (e.g. Ping, client response handlers, initialization, ...)
will not use your async method.

### Wrap Dispatch table handlers

You can wrap all dispatch table handlers with code that will make them async using your preferred method. (You can also select which ones to wrap.)

```clojure
(server/add-async-to-dispatch (server/make-dispatch))

(server/add-async-to-dispatch (server/make-dispatch) my-executor)
```

This will wrap all handlers with virtual thread executor (if available, cached pool executor otherwise).

<details>
<summary>Implementation</summary>
Uses `(rpc/with-middleware dispatch [[rpc/wrap-executor executor]])` to wrap all dispatch handlers.
</details>
