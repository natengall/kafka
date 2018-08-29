/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.kafka.connect.runtime.rest.resources;

import com.fasterxml.jackson.core.type.TypeReference;
import org.apache.kafka.connect.runtime.Herder;
import org.apache.kafka.connect.runtime.WorkerConfig;
import org.apache.kafka.connect.runtime.distributed.RebalanceNeededException;
import org.apache.kafka.connect.runtime.distributed.RequestTargetException;
import org.apache.kafka.connect.runtime.rest.RestClient;
import org.apache.kafka.connect.runtime.rest.entities.ConnectorInfo;
import org.apache.kafka.connect.runtime.rest.entities.ConnectorStateInfo;
import org.apache.kafka.connect.runtime.rest.errors.ConnectRestException;
import org.apache.kafka.connect.util.FutureCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletContext;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriBuilder;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Path("/summary")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SummaryResource {
    private static final Logger log = LoggerFactory.getLogger(SummaryResource.class);

    // TODO: This should not be so long. However, due to potentially long rebalances that may have to wait a full
    // session timeout to complete, during which we cannot serve some requests. Ideally we could reduce this, but
    // we need to consider all possible scenarios this could fail. It might be ok to fail with a timeout in rare cases,
    // but currently a worker simply leaving the group can take this long as well.
    private static final long REQUEST_TIMEOUT_MS = 90 * 1000;

    private final Herder herder;
    private final WorkerConfig config;
    @javax.ws.rs.core.Context
    private ServletContext context;

    public SummaryResource(Herder herder, WorkerConfig config) {
        this.herder = herder;
        this.config = config;
    }

    @GET
    @Path("/connectors")
    public Map<String,Map<String, String>> getConnectors(final @QueryParam("forward") Boolean forward) throws Throwable {
        FutureCallback<Map<String,Map<String,String>>> cb = new FutureCallback<>();
        herder.connectorsSummary(cb);
        return completeOrForwardRequest(cb, "/summary/connectors", "GET", null, new TypeReference<Map<String,Map<String, String>>>() {
        }, forward);
    }

    @GET
    @Path("/statuses")
    public Map<String,ConnectorStateInfo> getStatuses(final @QueryParam("forward") Boolean forward) throws Throwable {
        FutureCallback<Collection<String>> cb = new FutureCallback<>();
        herder.connectors(cb);
        completeOrForwardRequest(cb, "/summary/statuses", "GET", null, new TypeReference<Collection<String>>() {}, forward);
        Map<String,ConnectorStateInfo> summary = new HashMap<String,ConnectorStateInfo>();
        for (String connector : cb.get()) {
            summary.put(connector, herder.connectorStatus(connector));
        }
        return summary;
    }

    // Wait for a FutureCallback to complete. If it succeeds, return the parsed response. If it fails, try to forward the
    // request to the leader.
    private <T, U> T completeOrForwardRequest(FutureCallback<T> cb,
                                              String path,
                                              String method,
                                              Object body,
                                              TypeReference<U> resultType,
                                              Translator<T, U> translator,
                                              Boolean forward) throws Throwable {
        try {
            return cb.get(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();

            if (cause instanceof RequestTargetException) {
                if (forward == null || forward) {
                    // the only time we allow recursive forwarding is when no forward flag has
                    // been set, which should only be seen by the first worker to handle a user request.
                    // this gives two total hops to resolve the request before giving up.
                    boolean recursiveForward = forward == null;
                    RequestTargetException targetException = (RequestTargetException) cause;
                    String forwardUrl = UriBuilder.fromUri(targetException.forwardUrl())
                            .path(path)
                            .queryParam("forward", recursiveForward)
                            .build()
                            .toString();
                    log.debug("Forwarding request {} {} {}", forwardUrl, method, body);
                    return translator.translate(RestClient.httpRequest(forwardUrl, method, body, resultType, config));
                } else {
                    // we should find the right target for the query within two hops, so if
                    // we don't, it probably means that a rebalance has taken place.
                    throw new ConnectRestException(Response.Status.CONFLICT.getStatusCode(),
                            "Cannot complete request because of a conflicting operation (e.g. worker rebalance)");
                }
            } else if (cause instanceof RebalanceNeededException) {
                throw new ConnectRestException(Response.Status.CONFLICT.getStatusCode(),
                        "Cannot complete request momentarily due to stale configuration (typically caused by a concurrent config change)");
            }

            throw cause;
        } catch (TimeoutException e) {
            // This timeout is for the operation itself. None of the timeout error codes are relevant, so internal server
            // error is the best option
            throw new ConnectRestException(Response.Status.INTERNAL_SERVER_ERROR.getStatusCode(), "Request timed out");
        } catch (InterruptedException e) {
            throw new ConnectRestException(Response.Status.INTERNAL_SERVER_ERROR.getStatusCode(), "Request interrupted");
        }
    }

    private <T> T completeOrForwardRequest(FutureCallback<T> cb, String path, String method, Object body,
                                           TypeReference<T> resultType, Boolean forward) throws Throwable {
        return completeOrForwardRequest(cb, path, method, body, resultType, new IdentityTranslator<T>(), forward);
    }

    private <T> T completeOrForwardRequest(FutureCallback<T> cb, String path, String method,
                                           Object body, Boolean forward) throws Throwable {
        return completeOrForwardRequest(cb, path, method, body, null, new IdentityTranslator<T>(), forward);
    }

    private interface Translator<T, U> {
        T translate(RestClient.HttpResponse<U> response);
    }

    private static class IdentityTranslator<T> implements Translator<T, T> {
        @Override
        public T translate(RestClient.HttpResponse<T> response) {
            return response.body();
        }
    }

    private static class CreatedConnectorInfoTranslator implements Translator<Herder.Created<ConnectorInfo>, ConnectorInfo> {
        @Override
        public Herder.Created<ConnectorInfo> translate(RestClient.HttpResponse<ConnectorInfo> response) {
            boolean created = response.status() == 201;
            return new Herder.Created<>(created, response.body());
        }
    }
}
