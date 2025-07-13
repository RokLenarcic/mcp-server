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

Stream transport uses only one session per server instance. If you need to access the session outside of handlers, you'll need to manually manage the session creation:

### Standard Approach (No External Access)

```clojure
(server/start-server-on-streams session-template System/in System/out {})
```

### Manual Approach (With External Access)

```clojure
(require '[org.clojars.roklenarcic.mcp-server.server.streams :as streams])

(let [session (streams/create-session session-template System/out)]
  ;; Save session for later use
  (reset! my-session-atom session)
  ;; Start the server
  (streams/run session System/in {}))
```

This approach gives you direct access to the live session, allowing you to:
- Add or remove tools dynamically
- Monitor session state
- Implement custom session management logic


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

```clojure
(defn create-user-session [template-session user-id]
  (let [live-session (streams/create-session template-session output-stream)
        user-tools (load-user-tools user-id)]
    ;; Add user-specific tools
    (doseq [tool user-tools]
      (server/add-tool live-session tool))
    ;; Add user context
    (swap! live-session assoc :user-id user-id)
    live-session))
```

This pattern allows you to create personalized MCP servers for different users while sharing common infrastructure.