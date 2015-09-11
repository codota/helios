/*
 * Copyright (c) 2014 Spotify AB.
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.spotify.helios.client;

import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.util.concurrent.AsyncFunction;
import com.google.common.util.concurrent.FutureFallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.type.TypeFactory;
import com.spotify.crtauth.CrtAuthClient;
import com.spotify.crtauth.exceptions.InvalidInputException;
import com.spotify.crtauth.exceptions.KeyNotFoundException;
import com.spotify.crtauth.exceptions.SignerException;
import com.spotify.crtauth.signer.SingleKeySigner;
import com.spotify.crtauth.utils.TraditionalKeyParser;
import com.spotify.helios.common.HeliosException;
import com.spotify.helios.common.Json;
import com.spotify.helios.common.Resolver;
import com.spotify.helios.common.Version;
import com.spotify.helios.common.VersionCompatibility;
import com.spotify.helios.common.VersionCompatibility.Status;
import com.spotify.helios.common.descriptors.Deployment;
import com.spotify.helios.common.descriptors.DeploymentGroup;
import com.spotify.helios.common.descriptors.HostStatus;
import com.spotify.helios.common.descriptors.Job;
import com.spotify.helios.common.descriptors.JobId;
import com.spotify.helios.common.descriptors.JobStatus;
import com.spotify.helios.common.descriptors.RolloutOptions;
import com.spotify.helios.common.protocol.CreateDeploymentGroupResponse;
import com.spotify.helios.common.protocol.CreateJobResponse;
import com.spotify.helios.common.protocol.DeploymentGroupStatusResponse;
import com.spotify.helios.common.protocol.HostDeregisterResponse;
import com.spotify.helios.common.protocol.JobDeleteResponse;
import com.spotify.helios.common.protocol.JobDeployResponse;
import com.spotify.helios.common.protocol.JobUndeployResponse;
import com.spotify.helios.common.protocol.RemoveDeploymentGroupResponse;
import com.spotify.helios.common.protocol.RollingUpdateRequest;
import com.spotify.helios.common.protocol.RollingUpdateResponse;
import com.spotify.helios.common.protocol.SetGoalResponse;
import com.spotify.helios.common.protocol.TaskStatusEvents;
import com.spotify.helios.common.protocol.VersionResponse;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLConnection;
import java.net.UnknownHostException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.RSAPrivateKeySpec;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPInputStream;

import javax.net.ssl.HttpsURLConnection;
import javax.ws.rs.core.HttpHeaders;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.util.concurrent.Futures.immediateFuture;
import static com.google.common.util.concurrent.Futures.transform;
import static com.google.common.util.concurrent.Futures.withFallback;
import static com.google.common.util.concurrent.MoreExecutors.getExitingExecutorService;
import static com.spotify.helios.common.VersionCompatibility.HELIOS_SERVER_VERSION_HEADER;
import static com.spotify.helios.common.VersionCompatibility.HELIOS_VERSION_STATUS_HEADER;
import static java.lang.String.format;
import static java.lang.System.currentTimeMillis;
import static java.net.HttpURLConnection.HTTP_BAD_METHOD;
import static java.net.HttpURLConnection.HTTP_BAD_REQUEST;
import static java.net.HttpURLConnection.HTTP_FORBIDDEN;
import static java.net.HttpURLConnection.HTTP_NOT_FOUND;
import static java.net.HttpURLConnection.HTTP_OK;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static java.util.concurrent.Executors.newFixedThreadPool;
import static java.util.concurrent.TimeUnit.SECONDS;

public class HeliosClient implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(HeliosClient.class);
  private static final long RETRY_TIMEOUT_MILLIS = SECONDS.toMillis(60);
  private static final long HTTP_TIMEOUT_MILLIS = SECONDS.toMillis(10);
  private static final List<String> VALID_PROTOCOLS = ImmutableList.of("http", "https");
  private static final String VALID_PROTOCOLS_STR =
      String.format("[%s]", Joiner.on("|").join(VALID_PROTOCOLS));
  private static final String CRT_AUTH_SCHEME = "X-CHAP";

  private final AtomicBoolean versionWarningLogged = new AtomicBoolean();

  private final String user;
  private final Supplier<List<URI>> endpointSupplier;

  private final ListeningExecutorService executorService;

  HeliosClient(final String user,
               final Supplier<List<URI>> endpointSupplier,
               final ListeningExecutorService executorService) {
    this.user = checkNotNull(user);
    this.endpointSupplier = checkNotNull(endpointSupplier);
    this.executorService = checkNotNull(executorService);
  }

  HeliosClient(final String user, final List<URI> endpoints,
               final ListeningExecutorService executorService) {
    this(user, Suppliers.ofInstance(endpoints), executorService);
  }

  HeliosClient(final String user, final Supplier<List<URI>> endpointSupplier) {
    this(user, endpointSupplier, MoreExecutors.listeningDecorator(getExitingExecutorService(
        (ThreadPoolExecutor) newFixedThreadPool(4), 0, SECONDS)));
  }

  HeliosClient(final String user, final List<URI> endpoints) {
    this(user, Suppliers.ofInstance(endpoints));
  }

  @Override
  public void close() {
    executorService.shutdownNow();
  }

  private URI uri(final String path) {
    return uri(path, Collections.<String, String>emptyMap());
  }

  private URI uri(final String path, final Map<String, String> query) {
    // TODO(dano): use a uri builder and clean this mess up
    checkArgument(path.startsWith("/"));
    final Map<String, String> queryWithUser = Maps.newHashMap(query);
    queryWithUser.put("user", user);
    final String queryPart = Joiner.on('&').withKeyValueSeparator("=").join(queryWithUser);
    try {
      return new URI("http", "helios", path, queryPart, null);
    } catch (URISyntaxException e) {
      throw Throwables.propagate(e);
    }
  }

  private String path(final String resource, final Object... params) {
    final String path;
    if (params.length == 0) {
      path = resource;
    } else {
      final List<String> encodedParams = Lists.newArrayList();
      for (final Object param : params) {
        final URI u;
        try {
          final String p = param.toString().replace("/", "%2F");
          // URI does path encoding right, but using it is painful
          u = new URI("http", "ignore", "/" + p, "");
        } catch (URISyntaxException e) {
          throw Throwables.propagate(e);
        }
        encodedParams.add(u.getRawPath().substring(1));
      }
      path = format(resource, encodedParams.toArray());
    }
    return path;
  }

  private ListenableFuture<Response> request(final URI uri, final String method) {
    return request(uri, method, null);
  }

  private ListenableFuture<Response> request(final URI uri,
                                             final String method,
                                             final Object entity) {
    return request(uri, method, entity, Collections.<String, List<String>>emptyMap());
  }

  private ListenableFuture<Response> request(final URI uri,
                                             final String method,
                                             final Object entity,
                                             final Map<String, List<String>> otherHeaders) {
    final Map<String, List<String>> headers = Maps.newHashMap();
    final byte[] entityBytes;
    headers.put(VersionCompatibility.HELIOS_VERSION_HEADER,
                Collections.singletonList(Version.POM_VERSION));
    if (entity != null) {
      headers.put("Content-Type", singletonList("application/json"));
      headers.put("Charset", singletonList("utf-8"));
      entityBytes = Json.asBytesUnchecked(entity);
    } else {
      entityBytes = new byte[]{};
    }

    if (!otherHeaders.isEmpty()) {
      headers.putAll(otherHeaders);
    }

    return executorService.submit(new Callable<Response>() {
      @Override
      public Response call() throws Exception {
        final HttpURLConnection connection = connect(uri, method, entityBytes, headers);
        final int status = connection.getResponseCode();
        final InputStream rawStream;
        if (status / 100 != 2) {
          rawStream = connection.getErrorStream();
        } else {
          rawStream = connection.getInputStream();
        }
        final Map<String, List<String>> headers = connection.getHeaderFields();
        final boolean gzip = isGzipCompressed(connection);
        final InputStream stream = gzip ? new GZIPInputStream(rawStream) : rawStream;
        final ByteArrayOutputStream payload = new ByteArrayOutputStream();
        if (stream != null) {
          int n;
          byte[] buffer = new byte[4096];
          while ((n = stream.read(buffer, 0, buffer.length)) != -1) {
            payload.write(buffer, 0, n);
          }
        }
        URI realUri = connection.getURL().toURI();
        if (log.isTraceEnabled()) {
          log.trace("rep: {} {} {} {} {} {} gzip:{}",
                    method, realUri, status, headers, payload.size(), decode(payload), gzip);
        } else {
          log.debug("rep: {} {} {} {} {} gzip:{}",
                    method, realUri, status, headers, payload.size(), gzip);
        }
        checkprotocolVersionStatus(connection);
        return new Response(method, uri, status, headers, payload.toByteArray());
      }

      private boolean isGzipCompressed(final HttpURLConnection connection) {
        final List<String> encodings = connection.getHeaderFields().get("Content-Encoding");
        if (encodings == null) {
          return false;
        }
        for (String encoding : encodings) {
          if ("gzip".equals(encoding)) {
            return true;
          }
        }
        return false;
      }
    });
  }

  private void checkprotocolVersionStatus(final HttpURLConnection connection) {
    final Status versionStatus = getVersionStatus(connection);
    if (versionStatus == null) {
      log.debug("Server didn't return a version header!");
      return; // shouldn't happen really
    }

    final String serverVersion = connection.getHeaderField(HELIOS_SERVER_VERSION_HEADER);
    if ((versionStatus == VersionCompatibility.Status.MAYBE) &&
        (versionWarningLogged.compareAndSet(false, true))) {
      log.warn("Your Helios client version [{}] is ahead of the server [{}].  This will"
               + " probably work ok but there is the potential for weird things.  If in doubt,"
               + " contact the Helios team if you think the cluster you're connecting to is out"
               + " of date and should be upgraded.", Version.POM_VERSION, serverVersion);
    }
  }

  private Status getVersionStatus(final HttpURLConnection connection) {
    final String status = connection.getHeaderField(HELIOS_VERSION_STATUS_HEADER);
    if (status != null) {
      return VersionCompatibility.Status.valueOf(status);
    }
    return null;
  }

  private String decode(final ByteArrayOutputStream payload) {
    final byte[] bytes = payload.toByteArray();
    try {
      return Json.asPrettyString(Json.read(bytes, new TypeReference<Map<String, Object>>() {}));
    } catch (IOException e) {
      return new String(bytes, UTF_8);
    }
  }

  /**
   * Sets up a connection, retrying on connect failure.
   */
  private HttpURLConnection connect(final URI uri, final String method, final byte[] entity,
                                    final Map<String, List<String>> headers)
      throws URISyntaxException, IOException, TimeoutException, InterruptedException,
             HeliosException {
    final long deadline = currentTimeMillis() + RETRY_TIMEOUT_MILLIS;
    final int offset = ThreadLocalRandom.current().nextInt();
    while (currentTimeMillis() < deadline) {
      final List<URI> endpoints = endpointSupplier.get();
      if (endpoints.isEmpty()) {
        throw new RuntimeException("failed to resolve master");
      }
      log.debug("endpoint uris are {}", endpoints);
      for (int i = 0; i < endpoints.size() && currentTimeMillis() < deadline; i++) {
        final URI endpoint = endpoints.get(positive(offset + i) % endpoints.size());
        final String fullpath = endpoint.getPath() + uri.getPath();

        final String scheme = endpoint.getScheme();
        final String host = endpoint.getHost();
        final int port = endpoint.getPort();
        if (!VALID_PROTOCOLS.contains(scheme) || host == null || port == -1) {
          throw new HeliosException(String.format(
              "Master endpoints must be of the form \"%s://heliosmaster.domain.net:<port>\"",
              VALID_PROTOCOLS_STR));
        }

        final URI realUri = new URI(scheme, host + ":" + port, fullpath, uri.getQuery(), null);
        try {
          log.debug("connecting to {}", realUri);
          return connect0(realUri, method, entity, headers);
        } catch (ConnectException | SocketTimeoutException | UnknownHostException e) {
          // UnknownHostException happens if we can't resolve hostname into IP address.
          // UnknownHostException's getMessage method returns just the hostname which is a useless
          // message, so log the exception class name to provide more info.
          log.debug(e.getClass().getSimpleName() + " - " + e.getMessage());
          // Connecting failed, sleep a bit to avoid hammering and then try another endpoint
          Thread.sleep(200);
        }
      }
      log.warn("Failed to connect, retrying in 5 seconds.");
      Thread.sleep(5000);
    }
    throw new TimeoutException("Timed out connecting to master");
  }

  private HttpURLConnection connect0(final URI uri, final String method, final byte[] entity,
                                     final Map<String, List<String>> headers)
      throws IOException {
    if (log.isTraceEnabled()) {
      log.trace("req: {} {} {} {} {} {}", method, uri,
                headers.size(),
                Joiner.on(',').withKeyValueSeparator("=").join(headers),
                entity.length, Json.asPrettyStringUnchecked(entity));
    } else {
      log.debug("req: {} {} {} {}", method, uri, headers.size(), entity.length);
    }

    final URLConnection urlConnection = uri.toURL().openConnection();
    final HttpURLConnection connection = (HttpURLConnection) urlConnection;
    connection.setRequestProperty("Accept-Encoding", "gzip");
    connection.setInstanceFollowRedirects(false);
    connection.setConnectTimeout((int) HTTP_TIMEOUT_MILLIS);
    connection.setReadTimeout((int) HTTP_TIMEOUT_MILLIS);
    for (Map.Entry<String, List<String>> header : headers.entrySet()) {
      for (final String value : header.getValue()) {
        connection.addRequestProperty(header.getKey(), value);
      }
    }
    if (entity.length > 0) {
      connection.setDoOutput(true);
      connection.getOutputStream().write(entity);
    }
    if (urlConnection instanceof HttpsURLConnection) {
      setRequestMethod(connection, method, true);
    } else {
      setRequestMethod(connection, method, false);
    }
    connection.getResponseCode();
    return connection;
  }

  private int positive(final int value) {
    return value < 0 ? value + Integer.MAX_VALUE : value;
  }

  private void setRequestMethod(final HttpURLConnection connection,
                                final String method,
                                final boolean isHttps) {
    // Nasty workaround for ancient HttpURLConnection only supporting few methods
    final Class<?> httpURLConnectionClass = connection.getClass();
    try {
      final Field methodField =
          isHttps ?
          httpURLConnectionClass.getSuperclass().getSuperclass().getDeclaredField("method") :
          httpURLConnectionClass.getSuperclass().getDeclaredField("method");
      methodField.setAccessible(true);
      methodField.set(connection, method);
    } catch (NoSuchFieldException | IllegalAccessException e) {
      throw Throwables.propagate(e);
    }
  }

  private <T> ListenableFuture<T> get(final URI uri, final TypeReference<T> typeReference) {
    return get(uri, Json.type(typeReference));
  }

  private <T> ListenableFuture<T> get(final URI uri, final Class<T> clazz) {
    return get(uri, Json.type(clazz));
  }

  private <T> ListenableFuture<T> get(final URI uri, final JavaType javaType) {
    return transform(request(uri, "GET"), new ConvertResponseToPojo<T>(javaType));
  }

  private ListenableFuture<Integer> put(final URI uri) {
    return status(request(uri, "PUT"));
  }

  public ListenableFuture<JobDeployResponse> deploy(final Deployment job, final String host) {
    return deploy(job, host, "");
  }

  public ListenableFuture<JobDeployResponse> deploy(final Deployment job, final String host,
                                                    final String token) {
    final Set<Integer> deserializeReturnCodes = ImmutableSet.of(HTTP_OK, HTTP_NOT_FOUND,
                                                                HTTP_BAD_METHOD,
                                                                HTTP_BAD_REQUEST,
                                                                HTTP_FORBIDDEN);
    return transform(request(uri(path("/hosts/%s/jobs/%s", host, job.getJobId()),
                                 ImmutableMap.of("token", token)),
                             "PUT", job),
                     ConvertResponseToPojo.create(JobDeployResponse.class, deserializeReturnCodes));
  }

  public ListenableFuture<SetGoalResponse> setGoal(final Deployment job, final String host) {
    return setGoal(job, host, "");
  }

  public ListenableFuture<SetGoalResponse> setGoal(final Deployment job, final String host,
                                                   final String token) {
    return transform(request(uri(path("/hosts/%s/jobs/%s", host, job.getJobId()),
                                 ImmutableMap.of("token", token)),
                             "PATCH", job),
                     ConvertResponseToPojo.create(SetGoalResponse.class,
                                                  ImmutableSet.of(HTTP_OK, HTTP_NOT_FOUND,
                                                                  HTTP_FORBIDDEN)));
  }

  private ListenableFuture<Integer> status(final ListenableFuture<Response> req) {
    return transform(req,
                     new Function<Response, Integer>() {
                       @Override
                       public Integer apply(final Response reply) {
                         return reply.status;
                       }
                     });
  }

  public ListenableFuture<Deployment> deployment(final String host, final JobId job) {
    return get(uri(path("/hosts/%s/jobs/%s", host, job)), Deployment.class);
  }

  public ListenableFuture<HostStatus> hostStatus(final String host) {
    return hostStatus(host, Collections.<String, String>emptyMap());
  }

  public ListenableFuture<HostStatus>
  hostStatus(final String host, final Map<String, String> queryParams) {
    return get(uri(path("/hosts/%s/status", host), queryParams), HostStatus.class);
  }

  public ListenableFuture<Map<String, HostStatus>> hostStatuses(final List<String> hosts) {
    return hostStatuses(hosts, Collections.<String, String>emptyMap());
  }

  public ListenableFuture<Map<String, HostStatus>>
  hostStatuses(final List<String> hosts, final Map<String, String> queryParams) {
    final ConvertResponseToPojo<Map<String, HostStatus>> converter = ConvertResponseToPojo.create(
        TypeFactory.defaultInstance().constructMapType(Map.class, String.class, HostStatus.class),
        ImmutableSet.of(HTTP_OK));

    return transform(request(uri("/hosts/statuses", queryParams), "POST", hosts), converter);
  }

  public ListenableFuture<Integer> registerHost(final String host, final String id) {
    return put(uri(path("/hosts/%s", host), ImmutableMap.of("id", id)));
  }

  public ListenableFuture<JobDeleteResponse> deleteJob(final JobId id) {
    return deleteJob(id, "");
  }

  public ListenableFuture<JobDeleteResponse> deleteJob(final JobId id, final String token) {
    return transform(request(uri(path("/jobs/%s", id),
                                 ImmutableMap.of("token", token)),
                             "DELETE"),
                     ConvertResponseToPojo.create(JobDeleteResponse.class,
                                                  ImmutableSet.of(HTTP_OK, HTTP_NOT_FOUND,
                                                                  HTTP_BAD_REQUEST,
                                                                  HTTP_FORBIDDEN)));
  }

  public ListenableFuture<JobUndeployResponse> undeploy(final JobId jobId, final String host) {
    return undeploy(jobId, host, "");
  }

  public ListenableFuture<JobUndeployResponse> undeploy(final JobId jobId, final String host,
                                                        final String token) {
    return transform(request(uri(path("/hosts/%s/jobs/%s", host, jobId),
                                 ImmutableMap.of("token", token)),
                             "DELETE"),
                     ConvertResponseToPojo.create(JobUndeployResponse.class,
                                                  ImmutableSet.of(HTTP_OK, HTTP_NOT_FOUND,
                                                                  HTTP_BAD_REQUEST,
                                                                  HTTP_FORBIDDEN)));
  }

  public ListenableFuture<HostDeregisterResponse> deregisterHost(final String host) {
    return transform(request(uri(path("/hosts/%s", host)), "DELETE"),
                     ConvertResponseToPojo.create(HostDeregisterResponse.class,
                                                  ImmutableSet.of(HTTP_OK, HTTP_NOT_FOUND)));
  }

  public ListenableFuture<List<String>> listHosts() {
    return get(uri("/hosts/"), new TypeReference<List<String>>() {
    });
  }

  public ListenableFuture<List<String>> listMasters() {
    return get(uri("/masters/"), new TypeReference<List<String>>() {
    });
  }

  public ListenableFuture<List<String>> listMastersAuthed()
      throws ExecutionException, InterruptedException, InvalidInputException, KeyNotFoundException,
             SignerException {
    final Response r = request(uri("/masters/authed"), "GET").get();
    if (r.getStatus() == 401) {
      final List<String> authHeaders = r.getHeaders().get(HttpHeaders.WWW_AUTHENTICATE);
      if (authHeaders.size() < 1) {
        log.info("something went wrong");
      }
      if (!authHeaders.get(0).equals(CRT_AUTH_SCHEME)) {
        log.info("This client doesn't support auth scheme '%s'.", authHeaders.get(0));
      }

      final String authRequest = CrtAuthClient.createRequest("test");
      final Response r2 = request(uri("/_auth"), "GET", null, ImmutableMap.of(
          "X-CHAP", singletonList("request:" + authRequest))).get();

      if (r2.getStatus() != 200) {
        log.info("crt auth request failed with status code %d", r2.getStatus());
      }

      Map<String, List<String>> headers = r2.getHeaders();
      final List<String> xChapHeader = headers.get("X-CHAP");
      if (xChapHeader == null || xChapHeader.size() < 1) {
        log.info("crt auth request failed to get X-CHAP header in reply.");
      }

      assert xChapHeader != null;
      final String[] challengeParts = xChapHeader.get(0).split(":");
      if (challengeParts.length != 2) {
        log.info("crt auth request failed to get valid challenge: '%s'.", xChapHeader.get(0));
      }

      final String challenge = challengeParts[1];
      log.debug("Got CRT auth challenge %s", challenge);

      final CrtAuthClient crtAuthClient = makeCrtAuthClient();
      final String response = crtAuthClient.createResponse(challenge);
      final Response r3 = request(uri("/_auth"), "GET", null, ImmutableMap.of(
          "X-CHAP", singletonList("response:" + response))).get();

      if (r3.getStatus() != 200) {
        log.info("crt auth response failed with status code %d", r3.getStatus());
      }

      final Map<String, List<String>> headers2 = r3.getHeaders();
      final List<String> xChapHeader2 = headers2.get("X-CHAP");
      if (xChapHeader2 == null || xChapHeader2.size() < 1) {
        log.info("crt auth response failed to get X-CHAP header in reply.");
      }

      assert xChapHeader2 != null;
      final String[] tokenParts = xChapHeader2.get(0).split(":");
      if (tokenParts.length != 2) {
        log.info("crt auth response failed to get valid token: '%s'.", xChapHeader2.get(0));
      }

      final String token = tokenParts[1];
      log.debug("Got CRT auth token %s", token);

      final Response r4 = request(uri("/masters/authed"), "GET", null, ImmutableMap.of(
          "Authorization", singletonList("chap:" + token))).get();
      if (r4.getStatus() != 200) {
        log.info("authed master response failed with status code %d", r4.getStatus());
      }

      List<String> ret = ImmutableList.of(new String(r4.getPayload()));
      return Futures.immediateFuture(ret);
    }

    return Futures.immediateFuture(Collections.<String>emptyList());
  }

  private static CrtAuthClient makeCrtAuthClient() {
    final String privateKeyStr =
        "-----BEGIN RSA PRIVATE KEY-----\n" +
        "MIIEogIBAAKCAQEAytMDYYBpRWXwaEQUvjPMBqMjjlbp2GI3mqEVyhSn4cdvPGSK\n" +
        "PO1jHzeouSp1Ex9wP5mJVZyuG4XIUunVBYrGl3FEbxYGOOqVEhri02cU3vWpyCEf\n" +
        "4k/lfvDEQx1330RjgixEFJdJXmE4bdHXO68WluNnfN8gu7rgiEm4FqjgDbzJGWKm\n" +
        "Y2nozjhlaZAKcSxhvCvEbzQQTPE2KhNw0B0skVVOkvR1i21hfovFtoeAi19kLqHX\n" +
        "9HNXXKpX7QpR43SMnnf80CoQKPhQ3CazftmQydJpGC1ZSQ2bi5Pyv70jsA98F4W8\n" +
        "t3HRwrMZ0X8tmJZ7d9VRdGlbZYVuCCfuZjM5swIDAQABAoIBADtnoHbfQHYGDGrN\n" +
        "ffHTg+9xuslG5YjuA3EzuwkMEbvMSOU8YUzFDqInEDDjoZSvQZYvJw0/LbN79Jds\n" +
        "S2srIU1b7HpIzhu/gVfjLgpTB8bh1w95vDfxxLrwU9uAdwqaojaPNoV9ZgzRltB7\n" +
        "hHnDp28cPcRSKekyK+9fAB8K6Uy8N00hojBDwtwXM8C4PpQKod38Vd0Adp9dEdX6\n" +
        "Ro9suYb+d+qFalYbKIbjKWkll+ZiiGJjF1HSQCTwlzS2haPXUlbk57HnN+8ar+a3\n" +
        "ITTc2gbNuTqBRD1V/gCaD9F0npVI3mQ34eUADNVVGS0xw0pN4j++Da8KXP+pyn/G\n" +
        "DU/n8SECgYEA/KN4BTrg/LB7cGrzkMQmW26NA++htjiWHK3WTsQBKBDFyReJBn67\n" +
        "o9kMTHBP35352RfuJ3xEEJ0/ddqGEY/SzNk3HMTlxBbR5Xq8ye102dxfEO3eijJ/\n" +
        "F4VRSf9sFgdRoLvE62qLudytK4Ku9nnKoIqrMxFweTpwxzf2jjIKDbECgYEAzYXe\n" +
        "QxT1A/bfs5Qd6xoCVOAb4T/ALqFo95iJu4EtFt7nvt7avqL+Vsdxu5uBkTeEUHzh\n" +
        "1q47LFoFdGm+MesIIiPSSrbfZJ6ht9kw8EbF8Py85X4LBXey67JlzzUq+ewFEP91\n" +
        "do7uGQAY+BRwXtzzPqaVBVa94YOxdq/AGutrIqMCgYBr+cnQImwKU7tOPse+tbbX\n" +
        "GRa3+fEZmnG97CZOH8OGxjRiT+bGmd/ElX2GJfJdVn10ZZ/pzFii6TI4Qp9OXjPw\n" +
        "TV4as6Sn/EDVXXHWs+BfRKp059VXJ2HeQaKOh9ZAS/x9QANXwn/ZfhGdKQtyWHdb\n" +
        "yiiFeQyjI3EUFD0SZRya4QKBgA1QvQOvmeg12Gx0DjQrLTd+hY/kZ3kd8AUKlvHU\n" +
        "/qzaqD0PhzCOstfAeDflbVGRPTtRu/gCtca71lqidzYYuiAsHfXFP1fvhx64LZmD\n" +
        "nFNurHZZ4jDqfmcS2dHA6hXjGrjtNBkITZjFDtkTyev7eK74b/M2mXrA44CDBnk4\n" +
        "A2rtAoGAMv92fqI+B5taxlZhTLAIaGVFbzoASHTRl3eQJbc4zc38U3Zbiy4deMEH\n" +
        "3QTXq7nxWpE4YwHbgXAeJUGfUpE+nEZGMolj1Q0ueKuSstQg5p1nwhQIxej8EJW+\n" +
        "7siqmOTZDKzieik7KVzaJ/U02Q186smezKIuAOYtT8VCf9UksJ4=\n" +
        "-----END RSA PRIVATE KEY-----";

    PrivateKey privateKey;
    try {
      RSAPrivateKeySpec privateKeySpec = TraditionalKeyParser.parsePemPrivateKey(privateKeyStr);
      KeyFactory keyFactory = KeyFactory.getInstance("RSA");
      privateKey = keyFactory.generatePrivate(privateKeySpec);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }

    SingleKeySigner singleKeySigner = new SingleKeySigner(privateKey);
    return new CrtAuthClient(singleKeySigner, "localhost");
  }

  public ListenableFuture<VersionResponse> version() {
    // Create a fallback in case we fail to connect to the master. Return null if this happens.
    // The transform below will handle this and return an appropriate error message to the caller.
    final ListenableFuture<Response> futureWithFallback = withFallback(
        request(uri("/version/"), "GET"),
        new FutureFallback<Response>() {
          @Override
          public ListenableFuture<Response> create(@NotNull Throwable t) throws Exception {
            return immediateFuture(null);
          }
        }
    );

    return transform(
        futureWithFallback,
        new AsyncFunction<Response, VersionResponse>() {
          @Override
          public ListenableFuture<VersionResponse> apply(@NotNull Response reply) throws Exception {
            final String masterVersion =
                reply == null ? "Unable to connect to master" :
                reply.status == HTTP_OK ? Json.read(reply.payload, String.class) :
                "Master replied with error code " + reply.status;

            return immediateFuture(new VersionResponse(Version.POM_VERSION, masterVersion));
          }
        });
  }

  public ListenableFuture<CreateJobResponse> createJob(final Job descriptor) {
    return transform(request(uri("/jobs/"), "POST", descriptor),
                     ConvertResponseToPojo.create(CreateJobResponse.class,
                                                  ImmutableSet.of(HTTP_OK, HTTP_BAD_REQUEST)));
  }

  public ListenableFuture<Map<JobId, Job>> jobs(final String query) {
    return get(uri("/jobs", ImmutableMap.of("q", query)), new TypeReference<Map<JobId, Job>>() {
    });
  }

  public ListenableFuture<Map<JobId, Job>> jobs() {
    return get(uri("/jobs"), new TypeReference<Map<JobId, Job>>() {});
  }

  public ListenableFuture<TaskStatusEvents> jobHistory(final JobId jobId) {
    return transform(
        request(uri(path("/history/jobs/%s", jobId.toString())), "GET"),
        ConvertResponseToPojo.create(TaskStatusEvents.class,
                                     ImmutableSet.of(HTTP_OK, HTTP_NOT_FOUND)));
  }

  public ListenableFuture<JobStatus> jobStatus(final JobId jobId) {
    return get(uri(path("/jobs/%s/status", jobId)), JobStatus.class);
  }

  public ListenableFuture<Map<JobId, JobStatus>> jobStatuses(final Set<JobId> jobs) {
    final ConvertResponseToPojo<Map<JobId, JobStatus>> converter = ConvertResponseToPojo.create(
        TypeFactory.defaultInstance().constructMapType(Map.class, JobId.class, JobStatus.class),
        ImmutableSet.of(HTTP_OK));
    
    return transform(request(uri("/jobs/statuses"), "POST", jobs), converter);
  }

  public ListenableFuture<DeploymentGroup> deploymentGroup(final String name) {
    return get(uri("/deployment-group/" + name), new TypeReference<DeploymentGroup>() {
    });
  }

  public ListenableFuture<List<String>> listDeploymentGroups() {
    return get(uri("/deployment-group/"), new TypeReference<List<String>>() {
    });
  }

  public ListenableFuture<DeploymentGroupStatusResponse> deploymentGroupStatus(final String name) {
    return get(uri(path("/deployment-group/%s/status", name)),
               new TypeReference<DeploymentGroupStatusResponse>() {});
  }

  public ListenableFuture<CreateDeploymentGroupResponse>
  createDeploymentGroup(final DeploymentGroup descriptor) {
    return transform(request(uri("/deployment-group/"), "POST", descriptor),
                     ConvertResponseToPojo.create(CreateDeploymentGroupResponse.class,
                                                  ImmutableSet.of(HTTP_OK, HTTP_BAD_REQUEST)));
  }

  public ListenableFuture<RemoveDeploymentGroupResponse> removeDeploymentGroup(final String name) {
    return transform(request(uri("/deployment-group/" + name), "DELETE"),
                     ConvertResponseToPojo.create(RemoveDeploymentGroupResponse.class,
                                                  ImmutableSet.of(HTTP_OK, HTTP_BAD_REQUEST)));
  }

  public ListenableFuture<RollingUpdateResponse> rollingUpdate(
      final String deploymentGroupName, final JobId job, final RolloutOptions options) {
    return transform(
        request(uri(path("/deployment-group/%s/rolling-update", deploymentGroupName)),
                "POST", new RollingUpdateRequest(job, options)),
        ConvertResponseToPojo.create(RollingUpdateResponse.class,
                                     ImmutableSet.of(HTTP_OK, HTTP_BAD_REQUEST)));
  }

  public ListenableFuture<Integer> stopDeploymentGroup(final String deploymentGroupName) {
    return status(request(
        uri(path("/deployment-group/%s/stop", deploymentGroupName)), "POST"));
  }

  private static final class ConvertResponseToPojo<T> implements AsyncFunction<Response, T> {

    private final JavaType javaType;
    private final Set<Integer> decodeableStatusCodes;

    private ConvertResponseToPojo(final JavaType javaType) {
      this(javaType, ImmutableSet.of(HTTP_OK));
    }

    public ConvertResponseToPojo(final JavaType type, final Set<Integer> decodeableStatusCodes) {
      this.javaType = type;
      this.decodeableStatusCodes = decodeableStatusCodes;
    }

    public static <T> ConvertResponseToPojo<T> create(final JavaType type,
                                                      final Set<Integer> decodeableStatusCodes) {
      return new ConvertResponseToPojo<>(type, decodeableStatusCodes);
    }

    public static <T> ConvertResponseToPojo<T> create(final Class<T> clazz,
                                                      final Set<Integer> decodeableStatusCodes) {
      return new ConvertResponseToPojo<>(Json.type(clazz), decodeableStatusCodes);
    }

    @Override
    public ListenableFuture<T> apply(@NotNull final Response reply)
        throws HeliosException {
      if (reply.status == HTTP_NOT_FOUND && !decodeableStatusCodes.contains(HTTP_NOT_FOUND)) {
        return immediateFuture(null);
      }

      if (!decodeableStatusCodes.contains(reply.status)) {
        throw new HeliosException("request failed: " + reply);
      }

      if (reply.payload.length == 0) {
        throw new HeliosException("bad reply: " + reply);
      }

      final T result;
      try {
        result = Json.read(reply.payload, javaType);
      } catch (IOException e) {
        throw new HeliosException("bad reply: " + reply, e);
      }

      return immediateFuture(result);
    }
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  public static class Builder {

    private String user;
    private Supplier<List<URI>> endpointSupplier;

    public Builder setUser(final String user) {
      this.user = user;
      return this;
    }

    public Builder setDomain(final String domain) {
      return setEndpointSupplier(Resolver.supplier("helios", domain));
    }

    public Builder setEndpoints(final List<URI> endpoints) {
      return setEndpointSupplier(Suppliers.ofInstance(endpoints));
    }

    public Builder setEndpoints(final URI... endpoints) {
      return setEndpointSupplier(Suppliers.ofInstance(asList(endpoints)));
    }

    public Builder setEndpoints(final String... endpoints) {
      return setEndpointStrings(asList(endpoints));
    }

    public Builder setEndpointStrings(final List<String> endpoints) {
      final List<URI> uris = Lists.newArrayList();
      for (String endpoint : endpoints) {
        uris.add(URI.create(endpoint));
      }
      return setEndpoints(uris);
    }

    public Builder setEndpointSupplier(final Supplier<List<URI>> endpointSupplier) {
      this.endpointSupplier = endpointSupplier;
      return this;
    }

    public HeliosClient build() {
      return new HeliosClient(user, endpointSupplier);
    }
  }

  /**
   * Create a new helios client as a specific user, connecting to a helios master cluster in a
   * specific domain.
   *
   * @param domain The target domain.
   * @param user   The user to identify as.
   * @return A helios client.
   */
  public static HeliosClient create(final String domain, final String user) {
    return HeliosClient.newBuilder()
        .setDomain(domain)
        .setUser(user)
        .build();
  }

  private static class Response {

    private final String method;
    private final URI uri;
    private final int status;
    private final Map<String, List<String>> headers;
    private final byte[] payload;

    public Response(final String method, final URI uri, final int status,
                    Map<String, List<String>> headers, final byte[] payload) {
      this.method = method;
      this.uri = uri;
      this.status = status;
      this.payload = payload;
      this.headers = headers;
    }

    public int getStatus() {
      return status;
    }

    public Map<String, List<String>> getHeaders() {
      return headers;
    }

    public byte[] getPayload() {
      return payload;
    }

    @Override
    public String toString() {
      return "Response{" +
             "method='" + method + '\'' +
             ", uri=" + uri +
             ", status=" + status +
             ", payload=" + decode(payload) +
             '}';
    }

    private String decode(final byte[] payload) {
      if (payload == null) {
        return "";
      }
      final int length = Math.min(payload.length, 1024);
      return new String(payload, 0, length, UTF_8);
    }
  }
}
