/*
 * Hugging Face proxy format for Nexus Repository Core.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 */
package org.sonatype.nexus.content.huggingface.internal.recipe;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.List;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.annotation.Nullable;

import org.sonatype.nexus.common.template.EscapeHelper;
import org.sonatype.nexus.content.huggingface.HuggingFaceContentFacet;
import org.sonatype.nexus.repository.Facet;
import org.sonatype.nexus.repository.FacetSupport;
import org.sonatype.nexus.repository.config.Configuration;
import org.sonatype.nexus.repository.http.HttpResponses;
import org.sonatype.nexus.repository.http.HttpStatus;
import org.sonatype.nexus.repository.httpclient.HttpClientFacet;
import org.sonatype.nexus.repository.httpclient.RemoteBlockedIOException;
import org.sonatype.nexus.repository.huggingface.HuggingFaceErrors;
import org.sonatype.nexus.repository.huggingface.HuggingFacePaths;
import org.sonatype.nexus.repository.huggingface.HuggingFacePaths.Reconstruction;
import org.sonatype.nexus.repository.huggingface.HuggingFacePaths.RepoRef;
import org.sonatype.nexus.repository.huggingface.HuggingFacePaths.XorbRange;
import org.sonatype.nexus.repository.huggingface.internal.xet.HuggingFaceXetTokenExtractor;
import org.sonatype.nexus.repository.huggingface.internal.xet.HuggingFaceXetTokenRealm;
import org.sonatype.nexus.repository.huggingface.internal.xet.HuggingFaceXetTokens;
import org.sonatype.nexus.repository.huggingface.internal.xet.HuggingFaceXetTokens.Claims;
import org.sonatype.nexus.repository.huggingface.internal.xet.MultipartByteRanges;
import org.sonatype.nexus.repository.huggingface.internal.xet.XetReconstruction;
import org.sonatype.nexus.repository.huggingface.internal.xet.XetReconstruction.Fetch;
import org.sonatype.nexus.repository.huggingface.internal.xet.XetReconstruction.Part;
import org.sonatype.nexus.repository.huggingface.internal.xet.XetReconstruction.Slice;
import org.sonatype.nexus.repository.huggingface.internal.xet.XorbChunks;
import org.sonatype.nexus.repository.proxy.ProxyFacet;
import org.sonatype.nexus.repository.view.Content;
import org.sonatype.nexus.repository.view.Context;
import org.sonatype.nexus.repository.view.Parameters;
import org.sonatype.nexus.repository.view.Response;
import org.sonatype.nexus.repository.view.Status;
import org.sonatype.nexus.repository.view.payloads.BytesPayload;
import org.sonatype.nexus.security.SecurityHelper;
import org.sonatype.nexus.security.anonymous.AnonymousHelper;
import org.sonatype.nexus.security.realm.RealmManager;
import org.sonatype.nexus.validation.ssrf.AntiSsrfService;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.net.HttpHeaders;
import jakarta.inject.Provider;
import jakarta.validation.ValidationException;
import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.utils.HttpClientUtils;
import org.apache.shiro.subject.PrincipalCollection;
import org.apache.shiro.subject.Subject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import static java.util.Objects.requireNonNull;
import static org.sonatype.nexus.common.app.FeatureFlags.HUGGING_FACE_XET_ENABLED;
import static org.sonatype.nexus.content.huggingface.HuggingFaceContentFacet.XET_KIND;
import static org.sonatype.nexus.repository.huggingface.HuggingFaceErrors.X_ERROR_CODE;
import static org.sonatype.nexus.repository.huggingface.HuggingFaceErrors.X_ERROR_MESSAGE;
import static org.sonatype.nexus.repository.huggingface.HuggingFacePaths.XET_PREFIX;

/**
 * Serves the XET protocol: this repository stands in for the Hub's XET storage (CAS), so that clients with
 * {@code hf_xet} download XET files through it, and what they download is cached.
 *
 * <ol>
 * <li>The XET token endpoint of the Hub API is answered here, with a token of this Nexus (see
 * {@link HuggingFaceXetTokens}) and the URL of this repository as XET storage.</li>
 * <li>The reconstruction of a whole file (which byte ranges of which xorbs make it up) is fetched from XET storage
 * with a Hub token for the repository the client's token names, and stored; the windows of the file clients ask for
 * are sliced from it ({@link XetReconstruction}). Their presigned xorb URLs are remembered for a while, and replaced
 * by URLs of this repository, one per byte range. A file whose ranges are all cached needs no upstream request.</li>
 * <li>A xorb range is served from the cache, or fetched with the remembered presigned URL and checked
 * ({@link XorbChunks}) and stored first. Concurrent requests share one upstream request.</li>
 * </ol>
 *
 * XET is offered per repository ({@code xet.enabled}), only while {@link HuggingFaceXetTokenRealm} is active (clients
 * authenticate their storage requests with the token only), and only to authenticated users.
 */
@Facet.Exposed
@Component
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class HuggingFaceXetFacet
    extends FacetSupport
{
  public static final String CONFIG_KEY = "xet";

  public static final String X_XET_CAS_URL = "X-Xet-Cas-Url";

  public static final String X_XET_ACCESS_TOKEN = "X-Xet-Access-Token";

  public static final String X_XET_TOKEN_EXPIRATION = "X-Xet-Token-Expiration";

  private static final String JSON_CONTENT_TYPE = "application/json";

  private static final String XORB_CONTENT_TYPE = "application/octet-stream";

  /**
   * How long the presigned URLs of a reconstruction are used; the Hub's are valid for an hour.
   */
  private static final long PRESIGNED_URL_MINUTES = 50;

  /**
   * Upstream tokens are renewed this long before they expire.
   */
  private static final long TOKEN_MARGIN_SECONDS = 60;

  private static final int TOO_MANY_REQUESTS = 429;

  private static final Pattern RANGE = Pattern.compile("^bytes=(\\d+)-(\\d*)$");

  private static final Pattern CONTENT_RANGE = Pattern.compile("^bytes\\s+(\\d+)-(\\d+)/(?:\\d+|\\*)$");

  private final ObjectMapper objectMapper = new ObjectMapper();

  private final EscapeHelper escapeHelper = new EscapeHelper();

  private final HuggingFaceXetTokens tokens;

  private final Provider<RealmManager> realmManager;

  private final SecurityHelper securityHelper;

  private final AntiSsrfService antiSsrfService;

  private final boolean globallyEnabled;

  private volatile boolean enabled;

  /**
   * Hub tokens for XET storage, by Hub repository and revision.
   */
  private final ConcurrentMap<RepoRef, UpstreamToken> upstreamTokens = new ConcurrentHashMap<>();

  /**
   * Reconstructions of whole files, recently used.
   */
  private final Cache<String, XetReconstruction> reconstructions = CacheBuilder.newBuilder()
      .expireAfterAccess(10, TimeUnit.MINUTES)
      .maximumSize(1_000)
      .build();

  /**
   * Presigned URLs of the xorb ranges of recent reconstructions.
   */
  private final Cache<XorbRange, Fetch> fetches = CacheBuilder.newBuilder()
      .expireAfterWrite(PRESIGNED_URL_MINUTES, TimeUnit.MINUTES)
      .maximumSize(200_000)
      .build();

  /**
   * Upstream requests in progress, by presigned URL.
   */
  private final ConcurrentMap<String, CompletableFuture<Void>> downloads = new ConcurrentHashMap<>();

  @Autowired
  public HuggingFaceXetFacet(
      final HuggingFaceXetTokens tokens,
      final Provider<RealmManager> realmManager,
      final SecurityHelper securityHelper,
      final AntiSsrfService antiSsrfService,
      @Value("${" + HUGGING_FACE_XET_ENABLED + ":true}") final boolean globallyEnabled)
  {
    this.tokens = requireNonNull(tokens);
    this.realmManager = requireNonNull(realmManager);
    this.securityHelper = requireNonNull(securityHelper);
    this.antiSsrfService = requireNonNull(antiSsrfService);
    this.globallyEnabled = globallyEnabled;
  }

  @Override
  protected void doConfigure(final Configuration configuration) throws Exception {
    super.doConfigure(configuration);
    enabled = Boolean.TRUE.equals(configuration.attributes(CONFIG_KEY).get("enabled", Boolean.class));
  }

  record UpstreamToken(String casUrl, String accessToken, long expires)
  {
  }

  /**
   * An upstream answer passed on to the client as is.
   */
  static final class UpstreamStatusException
      extends IOException
  {
    final Response response;

    UpstreamStatusException(final Response response) {
      super("Upstream answered " + response.getStatus().getCode());
      this.response = response;
    }
  }

  // Whether XET is offered

  /**
   * Whether XET is offered in the current request: XET clients can only authenticate with the tokens of
   * {@link HuggingFaceXetTokenRealm}, so the realm must be active, and tokens are issued to users, not to anonymous.
   */
  public boolean isOffered() {
    if (!globallyEnabled || !enabled || !realmManager.get().isRealmEnabled(HuggingFaceXetTokenRealm.NAME)) {
      return false;
    }
    Subject subject = securityHelper.subject();
    return subject.getPrincipals() != null && !subject.getPrincipals().isEmpty() && !AnonymousHelper.isAnonymous(subject);
  }

  // Token

  /**
   * Answers the XET read token endpoint with a token of this Nexus, after checking with the Hub that the repository
   * and revision exist and are readable. If the Hub cannot be reached, the token is issued anyway: what is cached can
   * still be served.
   */
  public Response token(final RepoRef repo) throws IOException {
    if (!isOffered()) {
      return HttpResponses.notFound();
    }
    try {
      upstreamToken(repo);
    }
    catch (UpstreamStatusException e) {
      return e.response;
    }
    catch (IOException e) {
      log.debug("No XET token from upstream for {}: {}", repo, e.toString());
    }
    PrincipalCollection principals = securityHelper.subject().getPrincipals();
    Claims claims = tokens.claims(getRepository().getName(), repo, principals.getPrimaryPrincipal().toString(),
        principals.getRealmNames().iterator().next());
    String token = tokens.issue(claims);
    String casUrl = getRepository().getUrl() + XET_PREFIX;

    ObjectNode body = objectMapper.createObjectNode();
    body.put("accessToken", token);
    body.put("exp", claims.expires());
    body.put("casUrl", casUrl);
    Response.Builder response = new Response.Builder()
        .status(Status.success(HttpStatus.OK))
        .payload(new BytesPayload(objectMapper.writeValueAsBytes(body), JSON_CONTENT_TYPE));
    response.header(X_XET_CAS_URL, casUrl);
    response.header(X_XET_ACCESS_TOKEN, token);
    response.header(X_XET_TOKEN_EXPIRATION, Long.toString(claims.expires()));
    response.header(HttpHeaders.CACHE_CONTROL, "no-store");
    return response.build();
  }

  // Reconstruction

  /**
   * Answers a reconstruction request, for the window of the file its {@code Range} names, from the stored
   * reconstruction of the whole file. That is fetched from XET storage when there is none yet, and again when the
   * window needs xorb ranges that are neither cached nor have a presigned URL that is still valid.
   */
  public Response reconstruction(final Context context, final Reconstruction request) throws IOException {
    Optional<Claims> claims = verifiedToken(bearerToken(context));
    if (claims.isEmpty()) {
      return HttpResponses.unauthorized("Invalid or expired XET token");
    }
    if (!globallyEnabled || !enabled) {
      return HttpResponses.notFound();
    }
    long[] window = window(context.getRequest().getHeaders().get(HttpHeaders.RANGE));
    if (window == null) {
      return HttpResponses.badRequest("Invalid Range");
    }
    Optional<Slice> slice;
    long size;
    try {
      XetReconstruction whole = storedReconstruction(request.fileHash());
      boolean fresh = whole == null;
      if (fresh) {
        whole = fetchReconstruction(claims.get().repo(), request.fileHash());
      }
      slice = whole.slice(window[0], window[1]);
      if (slice.isPresent() && !fresh && !available(slice.get())) {
        whole = fetchReconstruction(claims.get().repo(), request.fileHash());
        slice = whole.slice(window[0], window[1]);
      }
      size = whole.size();
    }
    catch (UpstreamStatusException e) {
      return e.response;
    }
    if (slice.isEmpty()) {
      // how the client learns that it has reached the end of the file
      return HttpResponses.rangeNotSatisfiable(size);
    }
    String token = URLEncoder.encode(tokens.issue(tokens.claims(claims.get().repository(), claims.get().repo(),
        claims.get().userId(), claims.get().realm())), StandardCharsets.UTF_8);
    String base = getRepository().getUrl();
    ObjectNode body = slice.get().toJson(request.version(), (hash, part) -> base + xorbRange(hash, part).path() + "?"
        + HuggingFaceXetTokenExtractor.TOKEN_PARAMETER + "=" + token);
    return HttpResponses.ok(new BytesPayload(objectMapper.writeValueAsBytes(body), JSON_CONTENT_TYPE));
  }

  /**
   * First and last byte of a {@code Range} header ({@code bytes=first-[last]}), the whole file without one, or
   * {@code null} if invalid.
   */
  @Nullable
  private static long[] window(@Nullable final String range) {
    if (range == null) {
      return new long[]{0, Long.MAX_VALUE};
    }
    Matcher matcher = RANGE.matcher(range.trim());
    if (!matcher.matches()) {
      return null;
    }
    try {
      long first = Long.parseLong(matcher.group(1));
      long last = matcher.group(2).isEmpty() ? Long.MAX_VALUE : Long.parseLong(matcher.group(2));
      return last < first ? null : new long[]{first, last};
    }
    catch (NumberFormatException e) {
      return null;
    }
  }

  /**
   * Whether every xorb range of a window is cached, or can be fetched with a presigned URL that is still valid.
   */
  private boolean available(final Slice slice) throws IOException {
    for (Entry<String, List<Part>> xorb : slice.xorbs().entrySet()) {
      for (Part part : xorb.getValue()) {
        XorbRange range = xorbRange(xorb.getKey(), part);
        if (fetches.getIfPresent(range) == null && content().get(range.path()).isEmpty()) {
          return false;
        }
      }
    }
    return true;
  }

  @Nullable
  private XetReconstruction storedReconstruction(final String fileHash) throws IOException {
    XetReconstruction reconstruction = reconstructions.getIfPresent(fileHash);
    if (reconstruction != null) {
      return reconstruction;
    }
    Optional<Content> stored = content().get(reconstructionPath(fileHash));
    if (stored.isEmpty()) {
      return null;
    }
    try (InputStream in = stored.get().openInputStream()) {
      reconstruction = XetReconstruction.parse(objectMapper.readTree(in));
    }
    reconstructions.put(fileHash, reconstruction);
    return reconstruction;
  }

  /**
   * Fetches the reconstruction of a whole file from XET storage (version 2 of the API, else version 1, as clients
   * do), stores it, and remembers its presigned URLs.
   */
  private XetReconstruction fetchReconstruction(final RepoRef repo, final String fileHash) throws IOException {
    JsonNode body = null;
    boolean v2 = true;
    boolean renewed = false;
    while (body == null) {
      UpstreamToken token = upstreamToken(repo);
      URI uri = URI.create(token.casUrl() + (v2 ? "/v2/reconstructions/" : "/v1/reconstructions/") + fileHash);
      validateRemoteHost(uri);
      HttpGet get = new HttpGet(uri);
      get.setHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token.accessToken());
      HttpResponse response = httpClient().execute(get);
      try {
        int status = response.getStatusLine().getStatusCode();
        if (status == HttpStatus.OK) {
          try (InputStream in = response.getEntity().getContent()) {
            body = objectMapper.readTree(in);
          }
        }
        else if (status == HttpStatus.UNAUTHORIZED && !renewed) {
          log.debug("XET storage refused the token for {}; renewing it", repo);
          upstreamTokens.remove(repo, token);
          renewed = true;
        }
        else if ((status == HttpStatus.NOT_FOUND || status == HttpStatus.NOT_IMPLEMENTED) && v2) {
          v2 = false;
        }
        else if (status == HttpStatus.NOT_FOUND || status == HttpStatus.FORBIDDEN || status == TOO_MANY_REQUESTS) {
          throw new UpstreamStatusException(passOn(response));
        }
        else {
          throw new IOException("XET storage answered " + status + " for " + uri);
        }
      }
      finally {
        HttpClientUtils.closeQuietly(response);
      }
    }
    XetReconstruction reconstruction = XetReconstruction.parse(body);
    for (Fetch fetch : reconstruction.fetches()) {
      for (Part part : fetch.parts()) {
        fetches.put(xorbRange(fetch.xorbHash(), part), fetch);
      }
    }
    content().putMetadata(reconstructionPath(fileHash), XET_KIND,
        new BytesPayload(objectMapper.writeValueAsBytes(reconstruction.toStoredJson()), JSON_CONTENT_TYPE));
    reconstructions.put(fileHash, reconstruction);
    return reconstruction;
  }

  private static String reconstructionPath(final String fileHash) {
    return XET_PREFIX + "/reconstructions/" + fileHash + ".json";
  }

  // Xorb ranges

  /**
   * Serves a xorb range. A range that is neither cached nor part of a recent reconstruction is answered with 403,
   * which makes the client fetch the reconstruction again, as it does when a presigned URL has expired.
   */
  public Response xorb(final Context context, final XorbRange range) throws IOException {
    Parameters parameters = context.getRequest().getParameters();
    if (verifiedToken(parameters.get(HuggingFaceXetTokenExtractor.TOKEN_PARAMETER)).isEmpty()) {
      return HttpResponses.forbidden("Invalid or expired XET token");
    }
    if (!globallyEnabled || !enabled) {
      return HttpResponses.notFound();
    }
    Optional<Content> cached = content().get(range.path());
    if (cached.isPresent()) {
      return HttpResponses.ok(cached.get());
    }
    Fetch fetch = fetches.getIfPresent(range);
    if (fetch == null) {
      return HttpResponses.forbidden("Unknown xorb range; fetch the reconstruction again");
    }
    try {
      download(fetch);
    }
    catch (UpstreamStatusException e) {
      return e.response;
    }
    catch (IOException e) {
      log.warn("Fetching {} into {} failed: {}", range.path(), getRepository().getName(), e.toString());
      return HttpResponses.badGateway();
    }
    return content().get(range.path())
        .map(HttpResponses::ok)
        .orElseGet(() -> HttpResponses.badGateway("Xorb range missing from upstream response"));
  }

  /**
   * Fetches and stores all ranges of a presigned URL, or waits for the request already doing so.
   */
  private void download(final Fetch fetch) throws IOException {
    CompletableFuture<Void> mine = new CompletableFuture<>();
    CompletableFuture<Void> existing = downloads.putIfAbsent(fetch.url(), mine);
    if (existing != null) {
      try {
        existing.get();
        return;
      }
      catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IOException("Interrupted while waiting for upstream", e);
      }
      catch (ExecutionException e) {
        throw e.getCause() instanceof IOException io ? io : new IOException(e.getCause());
      }
    }
    try {
      doDownload(fetch);
      mine.complete(null);
    }
    catch (IOException | RuntimeException e) {
      mine.completeExceptionally(e);
      throw e;
    }
    finally {
      downloads.remove(fetch.url(), mine);
    }
  }

  private void doDownload(final Fetch fetch) throws IOException {
    URI uri = URI.create(fetch.url());
    validateRemoteHost(uri);
    HttpGet get = new HttpGet(uri);
    // presigned for exactly this header value
    get.setHeader(HttpHeaders.RANGE, "bytes=" + fetch.parts().stream()
        .map(part -> part.first() + "-" + part.last())
        .collect(Collectors.joining(",")));
    HttpResponse response = httpClient().execute(get);
    boolean complete = false;
    try {
      int status = response.getStatusLine().getStatusCode();
      if (status == HttpStatus.FORBIDDEN) {
        // expired: the client fetches the reconstruction again, with new URLs
        throw new UpstreamStatusException(HttpResponses.forbidden("Presigned URL refused upstream"));
      }
      if (status != HttpStatus.PARTIAL_CONTENT) {
        throw new IOException("Upstream answered " + status + " for xorb " + fetch.xorbHash());
      }
      String contentType = headerValue(response, HttpHeaders.CONTENT_TYPE);
      try (InputStream in = new BufferedInputStream(response.getEntity().getContent(), 64 * 1024)) {
        String boundary = MultipartByteRanges.boundary(contentType);
        if (boundary != null) {
          MultipartByteRanges.read(in, boundary, (first, last, data) -> store(fetch, first, last, data));
        }
        else {
          Matcher matcher = CONTENT_RANGE.matcher(String.valueOf(headerValue(response, HttpHeaders.CONTENT_RANGE)));
          if (!matcher.matches()) {
            throw new IOException("Upstream response for xorb " + fetch.xorbHash() + " without Content-Range");
          }
          store(fetch, Long.parseLong(matcher.group(1)), Long.parseLong(matcher.group(2)), in);
        }
      }
      complete = true;
    }
    finally {
      if (!complete) {
        get.abort();
      }
      HttpClientUtils.closeQuietly(response);
    }
  }

  private void store(final Fetch fetch, final long first, final long last, final InputStream data)
      throws IOException
  {
    Part part = fetch.parts().stream()
        .filter(p -> p.first() == first && p.last() == last)
        .findFirst()
        .orElseThrow(() -> new IOException("Upstream sent unrequested range " + first + "-" + last + " of xorb "
            + fetch.xorbHash()));
    XorbRange range = xorbRange(fetch.xorbHash(), part);
    Path file = Files.createTempFile("huggingface-xorb-", ".part");
    try {
      Files.copy(data, file, StandardCopyOption.REPLACE_EXISTING);
      try (InputStream in = new BufferedInputStream(Files.newInputStream(file), 64 * 1024)) {
        XorbChunks.check(in, range.length(), part.chunks());
      }
      content().putXetData(range.path(), file, XORB_CONTENT_TYPE);
      log.debug("Stored {} in {}", range.path(), getRepository().getName());
    }
    finally {
      Files.deleteIfExists(file);
    }
  }

  // Upstream

  /**
   * A Hub token for XET storage, for one repository and revision; the Hub checks access, and answers like for a file
   * when the repository or revision does not exist.
   */
  private UpstreamToken upstreamToken(final RepoRef repo) throws IOException {
    UpstreamToken current = upstreamTokens.get(repo);
    if (current != null && current.expires() - TOKEN_MARGIN_SECONDS > Instant.now().getEpochSecond()) {
      return current;
    }
    URI uri = remoteUrl().resolve(
        HuggingFacePaths.encodeSlashedRevision(escapeHelper.uriSegments(repo.xetTokenPath().substring(1))));
    validateRemoteHost(uri);
    HttpGet get = new HttpGet(uri);
    String hubToken = facet(HttpClientFacet.class).getBearerToken();
    if (hubToken != null) {
      get.setHeader(HttpHeaders.AUTHORIZATION, "Bearer " + hubToken);
    }
    HttpResponse response = httpClient().execute(get);
    try {
      int status = response.getStatusLine().getStatusCode();
      if (status == HttpStatus.OK) {
        String casUrl = headerValue(response, X_XET_CAS_URL);
        String accessToken = headerValue(response, X_XET_ACCESS_TOKEN);
        String expiration = headerValue(response, X_XET_TOKEN_EXPIRATION);
        if (casUrl == null || accessToken == null || expiration == null) {
          JsonNode body;
          try (InputStream in = response.getEntity().getContent()) {
            body = objectMapper.readTree(in);
          }
          casUrl = body.path("casUrl").asText(null);
          accessToken = body.path("accessToken").asText(null);
          expiration = body.path("exp").asText(null);
        }
        if (casUrl == null || accessToken == null || expiration == null) {
          throw new IOException("Upstream XET token response for " + repo + " is incomplete");
        }
        UpstreamToken token = new UpstreamToken(stripTrailingSlash(casUrl), accessToken, Long.parseLong(expiration));
        upstreamTokens.put(repo, token);
        return token;
      }
      if (status == HttpStatus.NOT_FOUND || status == HttpStatus.UNAUTHORIZED || status == HttpStatus.FORBIDDEN
          || status == TOO_MANY_REQUESTS)
      {
        throw new UpstreamStatusException(passOn(response));
      }
      throw new IOException("Upstream answered " + status + " for the XET token of " + repo);
    }
    catch (NumberFormatException e) {
      throw new IOException("Upstream XET token response for " + repo + " has an invalid expiration", e);
    }
    finally {
      HttpClientUtils.closeQuietly(response);
    }
  }

  /**
   * An upstream error response for the client, with the Hub's error description, as for files.
   */
  private static Response passOn(final HttpResponse response) {
    int status = response.getStatusLine().getStatusCode();
    String code = headerValue(response, X_ERROR_CODE);
    if (code == null && status == HttpStatus.UNAUTHORIZED) {
      code = "RepoNotFound";
    }
    Response.Builder builder = new Response.Builder()
        .status(Status.failure(status, HuggingFaceErrors.encode(code, headerValue(response, X_ERROR_MESSAGE))));
    if (code != null) {
      builder.header(X_ERROR_CODE, code);
    }
    String message = headerValue(response, X_ERROR_MESSAGE);
    if (message != null) {
      builder.header(X_ERROR_MESSAGE, message);
    }
    String retryAfter = headerValue(response, HttpHeaders.RETRY_AFTER);
    if (retryAfter != null) {
      builder.header(HttpHeaders.RETRY_AFTER, retryAfter);
    }
    return builder.build();
  }

  private void validateRemoteHost(final URI uri) throws RemoteBlockedIOException {
    if (uri.getHost() == null) {
      throw new RemoteBlockedIOException("No host in " + uri);
    }
    try {
      antiSsrfService.validateHost(uri.getHost());
    }
    catch (ValidationException e) {
      throw new RemoteBlockedIOException("Access to remote " + uri + " blocked: " + e.getMessage());
    }
  }

  // helpers

  /**
   * A token of this repository, issued by this Nexus and not expired.
   */
  private Optional<Claims> verifiedToken(@Nullable final String token) {
    return tokens.verify(token).filter(claims -> claims.repository().equals(getRepository().getName()));
  }

  @Nullable
  private static String bearerToken(final Context context) {
    String header = context.getRequest().getHeaders().get(HttpHeaders.AUTHORIZATION);
    return header != null && header.startsWith("Bearer ") ? header.substring("Bearer ".length()).trim() : null;
  }

  private static XorbRange xorbRange(final String hash, final Part part) {
    return new XorbRange(hash, part.first(), part.last());
  }

  private static String stripTrailingSlash(final String url) {
    return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
  }

  @Nullable
  private static String headerValue(final HttpResponse response, final String name) {
    Header header = response.getFirstHeader(name);
    return header == null ? null : header.getValue();
  }

  private HttpClient httpClient() {
    return facet(HttpClientFacet.class).getHttpClient();
  }

  private URI remoteUrl() {
    return facet(ProxyFacet.class).getRemoteUrl();
  }

  private HuggingFaceContentFacet content() {
    return getRepository().facet(HuggingFaceContentFacet.class);
  }
}
