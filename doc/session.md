# Session

## Primordial and live session

A call to `server/make-session` produces a session that is not connected to anything, and it serves as a template to
transports for a session that is live. 

Stream transport only has a single session (so it copies the primordial session once), HTTP transport
will copy it every time a new session is required.

Changing the map in primordial session will thus affect all sessions derived from it after the modification, but they don't stay linked.

Changing session's map after that will only affect that particular session. You should consider this when adding and removing
tools, prompts, change handlers etc. after the transport has started.

If you wish to have tool available for everyone, add it to primordial session before starting the transport, or add it to all sessions after it has started.

This flexibility allows that you actually have different tools, prompts, etc... for one particular client, if you so wish.

## Stream transport

Stream transport uses only 1 session per instance.

If you want to have access to it outside of the handlers, you'll need to take apart:
```clojure
(server/start-server-on-streams session-template System/in System/out {})
```
and use
```clojure
(require '[org.clojars.roklenarcic.mcp-server.server.streams :as streams])

;; here you have session, save it for later
(let [session (streams/create-session session-template output-stream)]
  (streams/run session input-stream opts))
```

