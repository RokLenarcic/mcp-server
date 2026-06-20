# Session Management

Sessions are the core abstraction in the MCP server - they represent client connections and contain all the state needed to handle requests.

## Template vs Live Sessions

Understanding the difference between template and live sessions is crucial for proper server configuration.

### Template Sessions

A template session is created by calling `server/make-session`. This session:
- Is not connected to any client
- Serves as a blueprint for creating live sessions
- Contains default configuration that gets copied to new connections

### Live Sessions

Live sessions are created when clients connect:
- **Stream transport**: Creates one live session by copying the template
- **HTTP transport**: Creates a new live session for each connection by copying the template

### Important Implications

When you modify a template session, the changes only affect future live sessions created from that template. Existing live sessions remain unchanged.

When you modify a live session, the changes only affect that specific client connection.

**Planning Your Modifications:**
- Add tools/prompts to the template session **before** starting the transport if you want them available to all clients
- Add tools/prompts to individual live sessions **after** transport starts if you want client-specific functionality

This design allows you to provide different tools, prompts, and resources for different clients if needed.

## Adding Dependencies

The best time to add your application dependencies is during template session creation:

```clojure
(server/make-session server-info
                     (json/serde {})
                     ;; Add your dependencies here
                     {:db-pool db-pool
                      :email-sender email-sender
                      :duct-system system
                      :config config})
```

Your handlers can access these dependencies through the exchange object:

```clojure
(defn my-tool-handler [exchange arguments]
  (let [session (core/get-session exchange)
        db-pool (:db-pool @session)]
    ;; Use db-pool for database operations
    ...))
```

## Stream Transport Session Access

Stream transport uses only one session per server instance. Because
`streams/run` mutates the atom you pass in, you already hold a reference to
the live session — there is no separate "get session" step.

### Standard Approach

```clojure
(server/start-server-on-streams session-template System/in System/out {})
```

If you only need per-request access to the session, use the `exchange` object
inside your handlers — no need to hold a reference externally.

### Holding the Atom for Ongoing Control

Because `streams/run` operates on the atom you supply, retaining that atom
gives you full ongoing control over the running session — add or remove tools,
update resources, inspect state — at any time while `run` is blocking:

```clojure
(require '[org.clojars.roklenarcic.mcp-server.server.streams :as streams])

(def my-session (atom @session-template))

;; Start the server in a separate thread; my-session is the live session.
(future (streams/run my-session System/in System/out {}))

;; Later, from any thread:
(server/add-tool my-session new-tool)
(server/remove-tool my-session "old-tool")
```

If you want to preserve `session-template` unchanged for future connections,
pass a fresh copy: `(atom @session-template)`.

### HTTP Transport — Accessing Live Sessions

For the HTTP transport, every connected client has its own session atom.
The `Sessions` protocol (returned as `:sessions` from `http/ring-handler`)
provides access to all of them:

```clojure
(require '[org.clojars.roklenarcic.mcp-server.server.http :as http])

(let [{:keys [handler sessions]} (http/ring-handler session-template opts)]
  ;; Broadcast a notification to every connected client:
  (doseq [ss (http/all-sessions sessions)]
    (server/add-tool (:session ss) new-tool)))
```


## Root Changes

Monitor when the client's filesystem roots change:

```clojure
(server/set-roots-changed-callback session 
  (fn [exchange] 
    (println "Client roots changed")
    ;; Update available resources based on new roots
    ))
```
## Session Isolation

Each session operates independently:

```clojure
;; This affects only session-1
(server/add-tool session-1 weather-tool)

;; This affects only session-2  
(server/add-tool session-2 calendar-tool)

;; session-1 has weather-tool but not calendar-tool
;; session-2 has calendar-tool but not weather-tool
```

This isolation enables:
- **User-specific tools**: Different tools for different users
- **Permission-based access**: Tools based on authentication level
- **A/B testing**: Different feature sets for different clients
- **Gradual rollouts**: New features for subset of clients

## Best Practices

1. **Initialize early**: Add core dependencies during template session creation
2. **Plan your scope**: Decide whether features should be global (template) or per-client (live session)
3. **Use lifecycle hooks**: Monitor important session changes
4. **Leverage isolation**: Use session isolation for user-specific features
5. **Handle cleanup**: If you add watchers or resources, clean them up appropriately

## Example: User-Specific Tool Loading

For the streams transport, create a fresh copy of the template per connection,
add user-specific tools before starting, then hold the atom for ongoing access:

```clojure
(defn start-user-session [template-session user-id input-stream output-stream]
  (let [session (atom @template-session)
        user-tools (load-user-tools user-id)]
    ;; Customise before the connection starts.
    (doseq [tool user-tools]
      (server/add-tool session tool))
    (swap! session assoc :user-id user-id)
    ;; Blocks until EOF/interrupt; session atom remains live throughout.
    (future (streams/run session input-stream output-stream {}))
    session))
```

For the HTTP transport, customise sessions from within the `initialize` handler
using the exchange object, or iterate `http/all-sessions` externally.

This pattern allows you to create personalised MCP servers for different users
while sharing common infrastructure.