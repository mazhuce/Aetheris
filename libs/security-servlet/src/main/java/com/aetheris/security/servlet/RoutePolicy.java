package com.aetheris.security.servlet;

import java.util.Optional;
import java.util.Set;
import java.util.Map;
import java.util.HashMap;

/** Resolve only from actual server method/raw canonical path; never from unverified proof claims. */
@FunctionalInterface
public interface RoutePolicy {
    Optional<Route> resolve(String method, String rawPath);

    enum UserMode { FORBIDDEN, OPTIONAL, REQUIRED }
    record Route(String operation, Set<String> allowedCallers, UserMode userMode,
                 Map<String, Set<String>> userAudiencesByCaller) {
        public Route(String operation, Set<String> allowedCallers, UserMode userMode) {
            this(operation, allowedCallers, userMode, Map.of());
        }
        public Route {
            if(operation==null || !operation.matches("[a-z][a-z0-9._-]{0,95}") || allowedCallers==null
                    || allowedCallers.isEmpty() || allowedCallers.stream().anyMatch(c->c==null||!c.matches("[a-z][a-z0-9-]{0,63}")) || userMode==null)
                throw new IllegalArgumentException("Invalid fixed route policy");
            allowedCallers=Set.copyOf(allowedCallers);
            if(userAudiencesByCaller==null || (userMode==UserMode.FORBIDDEN && !userAudiencesByCaller.isEmpty()))
                throw new IllegalArgumentException("Invalid fixed USER relay policy");
            Map<String,Set<String>> copied=new HashMap<>();
            for(var entry:userAudiencesByCaller.entrySet()) {
                var values=entry.getValue();
                if(!allowedCallers.contains(entry.getKey()) || values==null || values.isEmpty() || values.size()>10
                        || values.stream().anyMatch(value->value==null || !value.matches("[a-z][a-z0-9-]{0,63}")))
                    throw new IllegalArgumentException("Invalid fixed USER relay policy");
                copied.put(entry.getKey(),Set.copyOf(values));
            }
            userAudiencesByCaller=Map.copyOf(copied);
        }
        public Set<String> userAudiences(String authenticatedCaller) {
            if(!allowedCallers.contains(authenticatedCaller))throw new IllegalArgumentException("Caller not allowed");
            return userAudiencesByCaller.getOrDefault(authenticatedCaller,Set.of(authenticatedCaller));
        }
    }
}
