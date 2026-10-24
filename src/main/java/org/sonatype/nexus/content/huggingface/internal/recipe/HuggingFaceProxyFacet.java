/*
 * Hugging Face proxy format for Nexus Repository Core.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 */
package org.sonatype.nexus.content.huggingface.internal.recipe;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.util.Iterator;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import org.sonatype.nexus.common.io.InputStreamSupplier;
import org.sonatype.nexus.content.huggingface.HuggingFaceContentFacet;
import org.sonatype.nexus.repository.cache.CacheController;
import org.sonatype.nexus.repository.cache.CacheInfo;
import org.sonatype.nexus.repository.content.facet.ContentProxyFacetSupport;
import org.sonatype.nexus.repository.http.HttpMethods;
import org.sonatype.nexus.repository.httpclient.HttpClientFacet;
import org.sonatype.nexus.repository.httpclient.RemoteBlockedIOException;
import org.sonatype.nexus.repository.proxy.BypassHttpErrorException;
import org.sonatype.nexus.repository.huggingface.HuggingFaceFileInfo;
import org.sonatype.nexus.repository.huggingface.HuggingFaceErrors;
import org.sonatype.nexus.repository.huggingface.HuggingFacePaths;
import org.sonatype.nexus.repository.huggingface.HuggingFacePaths.FileRef;
import org.sonatype.nexus.repository.view.Content;
import org.sonatype.nexus.repository.view.Context;
import org.sonatype.nexus.repository.view.Payload;
import org.sonatype.nexus.repository.view.Request;
import org.sonatype.nexus.repository.view.payloads.BytesPayload;
import org.sonatype.nexus.repository.view.payloads.StreamPayload;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import com.google.common.net.HttpHeaders;
import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.HttpClient;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.Configurable;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpHead;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.client.utils.HttpClientUtils;
import org.sonatype.nexus.validation.ssrf.AntiSsrfService;

import jakarta.validation.ValidationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import static org.sonatype.nexus.content.huggingface.HuggingFaceContentFacet.API_KIND;
import static org.sonatype.nexus.content.huggingface.HuggingFaceContentFacet.FILE_METADATA_KIND;
import static org.sonatype.nexus.repository.huggingface.HuggingFaceErrors.X_ERROR_CODE;
import static org.sonatype.nexus.repository.huggingface.HuggingFaceErrors.X_ERROR_MESSAGE;
import static org.sonatype.nexus.repository.huggingface.HuggingFaceFileInfo.X_LINKED_ETAG;
import static org.sonatype.nexus.repository.huggingface.HuggingFaceFileInfo.X_LINKED_SIZE;
import static org.sonatype.nexus.repository.huggingface.HuggingFaceFileInfo.X_REPO_COMMIT;

/**
 * Hugging Face proxy facet.
 *
 * A file request is served in two steps, each going through the regular proxy machinery (cache, cooperation,
 * staleness): first the file metadata is resolved with an upstream {@code HEAD} that does not follow the CDN redirect,
 * then the content is fetched from, and stored at, the commit-pinned path. A client {@code HEAD} only needs the first
 * step. Hub API responses are proxied with their XET hints removed, so clients keep downloading through this
 * repository instead of going to XET storage directly.
 */
@Component
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class HuggingFaceProxyFacet
    extends ContentProxyFacetSupport
{
  private static final int MAX_HUB_REDIRECTS = 5;

  /**
   * How often a file download whose connection failed is resumed before giving up.
   */
  private static final int MAX_RESUMES = 3;

  /**
   * Content attribute: path and query of the next page of a listing, relative to the repository.
   */
  static final String NEXT_PAGE = "huggingface.nextPage";

  private static final String XET_HASH_FIELD = "xetHash";

  private static final Pattern NEXT_LINK_PATTERN = Pattern.compile("<([^>]+)>\\s*;\\s*rel=\"?next\"?");

  private static final String JSON_CONTENT_TYPE = "application/json";

  private static final String REPO_NOT_FOUND = "RepoNotFound";

  private static final int TOO_MANY_REQUESTS = 429;

  private static final String META_PREFIX = "/.meta";

  private static final String META_SUFFIX = ".json";

  private final ObjectMapper objectMapper = new ObjectMapper();

  private AntiSsrfService antiSsrfService;

  /**
   * Downloads in progress, by asset path.
   */
  private final ConcurrentMap<String, CompletableFuture<StreamingDownload>> downloads = new ConcurrentHashMap<>();

  private ExecutorService downloadExecutor;

  enum Kind
  {
    FILE_METADATA,
    FILE_CONTENT,
    API
  }

  /**
   * What the current proxy round-trip is about; set on the context before delegating to {@link #get(Context)} of the
   * superclass, which calls back into {@link #getCachedContent}, {@link #fetch} and {@link #store}.
   *
   * @param assetPath  path of the cached asset
   * @param remotePath path relative to the remote URL, without query string
   * @param query      raw query string to forward, if any
   * @param immutable  whether the upstream resource can never change (addressed by commit hash)
   */
  record Target(
      Kind kind,
      String assetPath,
      String remotePath,
      @Nullable String query,
      boolean immutable,
      @Nullable FileRef file)
  {
    static Target fileMetadata(final FileRef file) {
      // the suffix matters: strict content validation infers the expected type from the extension
      String assetPath = META_PREFIX + file.path() + META_SUFFIX;
      return new Target(Kind.FILE_METADATA, assetPath, file.path(), null, file.isCommit(), file);
    }

    static Target fileContent(final FileRef file) {
      return new Target(Kind.FILE_CONTENT, file.path(), file.path(), null, true, file);
    }

    static Target api(final String path, @Nullable final String query) {
      String assetPath = query == null ? path : path + "?" + query;
      return new Target(Kind.API, assetPath, path, query, HuggingFacePaths.isImmutableApi(path), null);
    }
  }

  @Nullable
  @Override
  public Content get(final Context context) throws IOException {
    try {
      return doGetHub(context);
    }
    catch (IOException | RuntimeException e) {
      // ProxyHandler answers these with a bare 502/500 without logging them
      log.debug("Failed to serve {} from {}", context.getRequest().getPath(), getRepository().getName(), e);
      throw e;
    }
  }

  @Nullable
  private Content doGetHub(final Context context) throws IOException {
    String path = context.getRequest().getPath();
    Optional<FileRef> file = HuggingFacePaths.parseFile(path);
    if (file.isPresent()) {
      return getFile(context, file.get());
    }
    if (HuggingFacePaths.isApi(path)) {
      setTarget(context, Target.api(path, rawQuery(context)));
      return super.get(context);
    }
    return null;
  }

  @Nullable
  private Content getFile(final Context context, final FileRef file) throws IOException {
    setTarget(context, Target.fileMetadata(file));
    Content metadata = super.get(context);
    if (metadata == null) {
      return null;
    }
    HuggingFaceFileInfo info = readFileInfo(metadata);
    // read by store() to verify the content
    context.getAttributes().set(HuggingFaceFileInfo.class, info);
    if (!file.isCommit()) {
      rememberForCommit(file.at(info.commit()), info);
    }

    if (HttpMethods.HEAD.equals(context.getRequest().getAction())) {
      return withFileInfo(new Content(new HeadPayload(info.size())), info);
    }

    FileRef pinned = file.at(info.commit());
    setTarget(context, Target.fileContent(pinned));
    if (info.size() != null && getCachedContent(context) == null) {
      Content streamed = streamFile(context, pinned, info);
      if (streamed != null) {
        return withFileInfo(streamed, info);
      }
    }
    Content content = super.get(context);
    return content == null ? null : withFileInfo(content, info);
  }

  // Streaming of files that are not cached yet

  /**
   * Serves a file that is not cached yet while it downloads, rather than after: model files are often many GB, and
   * {@code huggingface_hub} gives up after about a minute without data. Concurrent requests for the same file share
   * one upstream download, which completes and is cached even if the clients go away.
   *
   * @return {@code null} if the file cannot be streamed, to fall back to the regular proxy path
   * @see StreamingDownload
   */
  @Nullable
  private Content streamFile(final Context context, final FileRef file, final HuggingFaceFileInfo info)
      throws IOException
  {
    StreamingDownload active = joinOrStartDownload(context, file, info);
    if (active == null) {
      return null;
    }
    InputStreamSupplier readers = () -> {
      InputStream in = active.openReader();
      if (in != null) {
        return in;
      }
      // finished between the lookup and now
      Optional<Content> cached = content().get(file.path());
      if (cached.isEmpty()) {
        throw new IOException("Download of " + file.path() + " failed");
      }
      return cached.get().openInputStream();
    };
    return new Content(new StreamPayload(readers, info.size(), active.contentType()));
  }

  /**
   * Joins the download of the file in progress, or starts one. The slot is taken before contacting upstream, so
   * concurrent requests never open a second upstream connection; they wait for the first one's response instead.
   *
   * @return {@code null} if the download could not be started
   */
  @Nullable
  private StreamingDownload joinOrStartDownload(
      final Context context,
      final FileRef file,
      final HuggingFaceFileInfo info) throws IOException
  {
    CompletableFuture<StreamingDownload> slot = new CompletableFuture<>();
    CompletableFuture<StreamingDownload> existing = downloads.putIfAbsent(file.path(), slot);
    if (existing != null) {
      try {
        return existing.get();
      }
      catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IOException("Interrupted while waiting for upstream", e);
      }
      catch (ExecutionException e) {
        // the request that started it got the upstream error
        return null;
      }
    }
    try {
      StreamingDownload download = startDownload(context, file, info, slot);
      slot.complete(download);
      if (download == null) {
        downloads.remove(file.path(), slot);
      }
      return download;
    }
    catch (IOException | RuntimeException e) {
      downloads.remove(file.path(), slot);
      slot.completeExceptionally(e);
      throw e;
    }
  }

  /**
   * Requests the file in the request thread, so upstream errors reach this client, and hands the body over to a
   * background download.
   */
  @Nullable
  private StreamingDownload startDownload(
      final Context context,
      final FileRef file,
      final HuggingFaceFileInfo info,
      final CompletableFuture<StreamingDownload> slot) throws IOException
  {
    HttpClient client = facet(HttpClientFacet.class).getHttpClient();
    URI uri = getRemoteUrl().resolve(getUrl(context));
    validateRemoteHost(uri);
    HttpGet request = new HttpGet(uri);
    HttpResponse response = execute(context, client, request);
    int status = response.getStatusLine().getStatusCode();
    if (status != HttpStatus.SC_OK) {
      try {
        if (isHubClientError(status)) {
          throw hubError(response);
        }
        mayThrowProxyServiceException(response);
        log.warn("Unexpected upstream status {} for {}", status, uri);
        return null;
      }
      finally {
        request.abort();
      }
    }

    StreamingDownload download;
    try {
      download = new StreamingDownload(
          Files.createTempFile("huggingface-", ".part"), info, headerValue(response, HttpHeaders.CONTENT_TYPE));
    }
    catch (IOException e) {
      request.abort();
      throw e;
    }
    CacheInfo cacheInfo = getCacheController(context).current();
    downloadExecutor.execute(() -> runDownload(file, download, client, request, response, cacheInfo, slot));
    return download;
  }

  /**
   * The upstream body of a file: the response already received, then, to resume, ranged requests for the rest.
   */
  private StreamingDownload.Upstream upstreamBody(final HttpClient client, final URI uri, final HttpResponse first) {
    AtomicBoolean firstUsed = new AtomicBoolean();
    return offset -> {
      if (!firstUsed.getAndSet(true)) {
        return first.getEntity().getContent();
      }
      log.debug("Resuming download of {} at byte {}", uri, offset);
      HttpGet resume = new HttpGet(uri);
      if (offset > 0) {
        resume.setHeader(HttpHeaders.RANGE, "bytes=" + offset + "-");
      }
      HttpResponse response = client.execute(resume);
      int status = response.getStatusLine().getStatusCode();
      if (status == HttpStatus.SC_PARTIAL_CONTENT) {
        return response.getEntity().getContent();
      }
      if (status == HttpStatus.SC_OK) {
        // range not supported, or not asked for: skip what we have
        InputStream in = response.getEntity().getContent();
        in.skipNBytes(offset);
        return in;
      }
      resume.abort();
      throw new IOException("Upstream answered " + status + " resuming " + uri + " at " + offset);
    };
  }

  private void runDownload(
      final FileRef file,
      final StreamingDownload download,
      final HttpClient client,
      final HttpGet request,
      final HttpResponse response,
      final CacheInfo cacheInfo,
      final CompletableFuture<StreamingDownload> slot)
  {
    boolean complete = false;
    try {
      download.download(upstreamBody(client, request.getURI(), response), MAX_RESUMES);
      complete = true;
      content().putFile(file, download.file(), download.contentType(), cacheInfo);
      log.debug("Downloaded and stored {} in {}", file.path(), getRepository().getName());
    }
    catch (Exception e) {
      download.fail(e instanceof IOException io ? io : new IOException(e));
      log.warn("Download of {} into {} failed: {}", file.path(), getRepository().getName(), e.toString());
    }
    finally {
      downloads.remove(file.path(), slot);
      try {
        download.delete();
      }
      catch (IOException e) {
        log.warn("Could not delete {}: {}", download.file(), e.toString());
      }
      if (!complete) {
        // closing would drain the rest of a possibly multi-GB body
        request.abort();
      }
      HttpClientUtils.closeQuietly(response);
    }
  }

  @Autowired
  @Override
  protected void configureAntiSsrfService(final AntiSsrfService antiSsrfService) {
    super.configureAntiSsrfService(antiSsrfService);
    this.antiSsrfService = antiSsrfService;
  }

  /**
   * The SSRF protection that the regular proxy path applies, for the requests this facet makes directly. (Redirect
   * targets are checked by the HTTP client.)
   */
  private void validateRemoteHost(final URI uri) throws RemoteBlockedIOException {
    if (uri.getHost() == null) {
      return;
    }
    try {
      antiSsrfService.validateHost(uri.getHost());
    }
    catch (ValidationException e) {
      throw new RemoteBlockedIOException("Access to remote " + uri + " blocked: " + e.getMessage());
    }
  }

  @Override
  protected void doStart() {
    super.doStart();
    String name = "huggingface-download-" + getRepository().getName();
    downloadExecutor = Executors.newCachedThreadPool(runnable -> {
      Thread thread = new Thread(runnable, name);
      thread.setDaemon(true);
      return thread;
    });
  }

  @Override
  protected void doStop() throws Exception {
    downloadExecutor.shutdownNow();
    super.doStop();
  }

  /**
   * What a branch or tag resolved to is also what the commit has, for good: storing it saves clients that ask by
   * commit hash (as {@code snapshot_download} does for every file) an upstream request, and lets them be served while
   * the Hub is unreachable.
   */
  private void rememberForCommit(final FileRef pinned, final HuggingFaceFileInfo info) throws IOException {
    String path = Target.fileMetadata(pinned).assetPath();
    if (content().get(path).isEmpty()) {
      Content metadata = new Content(new BytesPayload(objectMapper.writeValueAsBytes(info), JSON_CONTENT_TYPE));
      metadata.getAttributes().set(CacheInfo.class, cacheControllerHolder.getContentCacheController().current());
      content().putMetadata(path, FILE_METADATA_KIND, metadata);
    }
  }

  private static Content withFileInfo(final Content content, final HuggingFaceFileInfo info) {
    content.getAttributes().set(HuggingFaceFileInfo.class, info);
    if (info.etag() != null) {
      content.getAttributes().set(Content.CONTENT_ETAG, info.etag());
    }
    return content;
  }

  private HuggingFaceFileInfo readFileInfo(final Content metadata) throws IOException {
    try (InputStream in = metadata.openInputStream()) {
      return objectMapper.readValue(in, HuggingFaceFileInfo.class);
    }
    catch (IOException e) {
      // ProxyHandler turns this into a bare 502
      log.warn("Unreadable file metadata in {}: {}", getRepository().getName(), e.toString());
      throw e;
    }
  }

  // Callbacks from ProxyFacetSupport

  @Nullable
  @Override
  protected Content getCachedContent(final Context context) throws IOException {
    Content content = content().get(target(context).assetPath()).orElse(null);
    return content != null && target(context).kind() == Kind.API ? withNextPage(content) : content;
  }

  /**
   * Exposes the stored next-page link of an API response to {@link HuggingFaceHeadersHandler}.
   */
  private static Content withNextPage(final Content content) {
    String nextPage = HuggingFaceContentFacet.nextPage(content);
    if (nextPage != null) {
      content.getAttributes().set(NEXT_PAGE, nextPage);
    }
    return content;
  }

  @Override
  protected Content store(final Context context, final Content content) throws IOException {
    Target target = target(context);
    return switch (target.kind()) {
      case FILE_CONTENT -> content().putFile(target.file(), content, expectedSha256(context));
      case FILE_METADATA -> content().putMetadata(target.assetPath(), FILE_METADATA_KIND, content);
      case API -> withNextPage(content().putApiResponse(
          target.assetPath(), content, content.getAttributes().get(NEXT_PAGE, String.class)));
    };
  }

  /**
   * For LFS files the Hub etag is the SHA-256 of the content, which lets us verify what the CDN served.
   */
  @Nullable
  private static String expectedSha256(final Context context) {
    HuggingFaceFileInfo info = context.getAttributes().get(HuggingFaceFileInfo.class);
    return info != null && info.isSha256Etag() ? info.etag() : null;
  }

  @Override
  protected String getUrl(final Context context) {
    return HuggingFacePaths.encodeSlashedRevision(getEscapeHelper().uriSegments(target(context).remotePath().substring(1)));
  }

  @Nullable
  @Override
  protected Content fetch(final Context context, @Nullable final Content stale) throws IOException {
    return switch (target(context).kind()) {
      case FILE_METADATA -> fetchFileMetadata(context);
      case FILE_CONTENT -> super.fetch(context, stale);
      case API -> fetchApi(context, stale);
    };
  }

  @Override
  protected HttpRequestBase buildFetchHttpRequest(final URI uri, final Context context, final Content stale) {
    // the query string is appended after path encoding, which would otherwise escape the '?';
    // URLs of further pages of a listing already carry their own
    String query = target(context).query();
    URI fullUri = uri;
    if (query != null && uri.getRawQuery() == null) {
      try {
        fullUri = new URI(uri + "?" + query);
      }
      catch (URISyntaxException e) {
        log.warn("Dropping invalid query string from {}: {}", uri, e.getMessage());
      }
    }
    return super.buildFetchHttpRequest(fullUri, context, stale);
  }

  @Override
  protected CacheController getCacheController(final Context context) {
    Target target = target(context);
    return target.immutable()
        ? cacheControllerHolder.getContentCacheController()
        : cacheControllerHolder.getMetadataCacheController();
  }

  @Override
  protected String getRequestKey(final Context context) {
    Target target = target(context);
    return target.kind() + ":" + target.assetPath();
  }

  /**
   * A client {@code HEAD} is answered from the cached file metadata, which must therefore be stored.
   */
  @Override
  protected boolean shouldSkipStoreForHead() {
    return false;
  }

  /**
   * Hub client errors (missing repository, revision or file; gated repository) are passed on to the client, which
   * turns them into specific exceptions.
   */
  @Override
  protected Content build3xxResponseContent(
      final Context context,
      final URI uri,
      final Content stale,
      final HttpResponse response,
      final CacheInfo cacheInfo,
      final boolean isUnmodified) throws IOException
  {
    if (!isUnmodified && isHubClientError(response.getStatusLine().getStatusCode())) {
      throw hubError(response);
    }
    return super.build3xxResponseContent(context, uri, stale, response, cacheInfo, isUnmodified);
  }

  /**
   * Upstream statuses passed on to the client: missing or inaccessible resources, and rate limiting (with its
   * {@code Retry-After}), which clients back off from.
   */
  private static boolean isHubClientError(final int status) {
    return status == HttpStatus.SC_NOT_FOUND || status == HttpStatus.SC_UNAUTHORIZED
        || status == HttpStatus.SC_FORBIDDEN || status == TOO_MANY_REQUESTS;
  }

  private static BypassHttpErrorException hubError(final HttpResponse response) {
    int status = response.getStatusLine().getStatusCode();
    String code = headerValue(response, X_ERROR_CODE);
    if (code == null && status == HttpStatus.SC_UNAUTHORIZED) {
      // what the Hub means by a bare 401; huggingface_hub only infers it for https endpoints
      code = REPO_NOT_FOUND;
    }
    String reason = HuggingFaceErrors.encode(code, headerValue(response, X_ERROR_MESSAGE));
    ListMultimap<String, String> headers = ArrayListMultimap.create();
    String retryAfter = headerValue(response, HttpHeaders.RETRY_AFTER);
    if (retryAfter != null) {
      headers.put(HttpHeaders.RETRY_AFTER, retryAfter);
    }
    return new BypassHttpErrorException(status, reason, headers);
  }

  // File metadata

  /**
   * Resolves file metadata like {@code huggingface_hub} does: {@code HEAD} the resolve URL, following only redirects
   * within the Hub (e.g. to {@code /api/resolve-cache/...}) and not the one to the CDN, and read the Hub headers.
   */
  @Nullable
  private Content fetchFileMetadata(final Context context) throws IOException {
    HttpClient client = facet(HttpClientFacet.class).getHttpClient();
    RequestConfig requestConfig = withoutRedirects(client);
    URI uri = getRemoteUrl().resolve(getUrl(context));
    validateRemoteHost(uri);

    String commit = null;
    String linkedEtag = null;
    Long linkedSize = null;
    for (int hop = 0; hop <= MAX_HUB_REDIRECTS; hop++) {
      HttpHead head = new HttpHead(uri);
      head.setConfig(requestConfig);
      head.setHeader(HttpHeaders.ACCEPT_ENCODING, "identity");
      HttpResponse response = execute(context, client, head);
      try {
        int status = response.getStatusLine().getStatusCode();
        // the first response carrying a Hub header wins, as later hops may come from a cache layer
        if (commit == null) {
          commit = headerValue(response, X_REPO_COMMIT);
        }
        if (linkedEtag == null) {
          linkedEtag = normalizeEtag(headerValue(response, X_LINKED_ETAG));
        }
        if (linkedSize == null) {
          linkedSize = parseSize(headerValue(response, X_LINKED_SIZE));
        }

        if (status == HttpStatus.SC_OK) {
          return fileMetadataContent(context, uri, commit,
              linkedEtag != null ? linkedEtag : normalizeEtag(headerValue(response, HttpHeaders.ETAG)),
              linkedSize != null ? linkedSize : parseSize(headerValue(response, HttpHeaders.CONTENT_LENGTH)));
        }
        if (isRedirect(status)) {
          String location = headerValue(response, HttpHeaders.LOCATION);
          URI next = location == null ? null : uri.resolve(location);
          if (next != null && Objects.equals(next.getHost(), uri.getHost())) {
            uri = next;
            continue;
          }
          // redirect to the CDN: the X-Linked-* headers describe the file
          return fileMetadataContent(context, uri, commit, linkedEtag, linkedSize);
        }
        if (isHubClientError(status)) {
          log.debug("Upstream answered {} for {}", status, uri);
          throw hubError(response);
        }
        mayThrowProxyServiceException(response);
        throw new IOException("Unexpected upstream status " + status + " for " + uri);
      }
      finally {
        HttpClientUtils.closeQuietly(response);
      }
    }
    // a failure of the remote, not a missing file: answered with 502 and not negatively cached
    throw new IOException("Too many redirects resolving " + uri);
  }

  private Content fileMetadataContent(
      final Context context,
      final URI uri,
      @Nullable final String commit,
      @Nullable final String etag,
      @Nullable final Long size) throws IOException
  {
    if (commit == null) {
      // a failure of the remote, not a missing file: answered with 502 and not negatively cached
      throw new IOException(
          "Upstream response for " + uri + " has no " + X_REPO_COMMIT + " header; is the remote URL a Hugging Face Hub?");
    }
    HuggingFaceFileInfo info = new HuggingFaceFileInfo(commit, etag, size);
    Content content = new Content(new BytesPayload(objectMapper.writeValueAsBytes(info), JSON_CONTENT_TYPE));
    content.getAttributes().set(CacheInfo.class, getCacheController(context).current());
    return content;
  }

  private static RequestConfig withoutRedirects(final HttpClient client) {
    RequestConfig defaults = client instanceof Configurable configurable ? configurable.getConfig() : null;
    RequestConfig.Builder builder = defaults != null ? RequestConfig.copy(defaults) : RequestConfig.custom();
    return builder.setRedirectsEnabled(false).build();
  }

  private static boolean isRedirect(final int status) {
    return status == HttpStatus.SC_MOVED_PERMANENTLY || status == HttpStatus.SC_MOVED_TEMPORARILY
        || status == HttpStatus.SC_SEE_OTHER || status == HttpStatus.SC_TEMPORARY_REDIRECT || status == 308;
  }

  // API

  /**
   * Fetches one API response, without its XET hints. For one page of a paginated listing, the link to the next page is
   * kept (relative to the remote, so it can be served pointing at this repository) and stored with the response.
   */
  @Nullable
  private Content fetchApi(final Context context, @Nullable final Content stale) throws IOException {
    Content upstream = super.fetch(context, stale);
    if (upstream == null) {
      return null;
    }
    JsonNode body;
    try (InputStream in = upstream.openInputStream()) {
      body = objectMapper.readTree(in);
    }
    finally {
      upstream.close();
    }
    stripXetHashes(body);

    Content content = new Content(new BytesPayload(objectMapper.writeValueAsBytes(body), JSON_CONTENT_TYPE));
    content.getAttributes().set(Content.CONTENT_ETAG, upstream.getAttributes().get(Content.CONTENT_ETAG));
    content.getAttributes().set(Content.CONTENT_LAST_MODIFIED, upstream.getAttributes().get(Content.CONTENT_LAST_MODIFIED));
    content.getAttributes().set(CacheInfo.class, upstream.getAttributes().get(CacheInfo.class));
    String nextPage = relativeToRemote(nextPageUrl(context));
    if (nextPage != null) {
      content.getAttributes().set(NEXT_PAGE, nextPage);
    }
    return content;
  }

  /**
   * The part of an upstream URL after the remote URL, e.g. {@code api/models/x/tree/main?cursor=...}.
   */
  @Nullable
  private String relativeToRemote(@Nullable final String url) {
    if (url == null) {
      return null;
    }
    String remote = getRemoteUrl().toString();
    if (!url.startsWith(remote)) {
      log.warn("Not following pagination link {} outside of {}", url, remote);
      return null;
    }
    return url.substring(remote.length());
  }

  @Nullable
  private static String nextPageUrl(final Context context) {
    HttpResponse response = context.getAttributes().get(HTTP_RESPONSE, HttpResponse.class);
    if (response == null) {
      return null;
    }
    for (Header link : response.getHeaders(HttpHeaders.LINK)) {
      Matcher matcher = NEXT_LINK_PATTERN.matcher(link.getValue());
      if (matcher.find()) {
        return matcher.group(1);
      }
    }
    return null;
  }

  @VisibleForTesting
  static void stripXetHashes(final JsonNode node) {
    if (node instanceof ObjectNode object) {
      object.remove(XET_HASH_FIELD);
    }
    for (Iterator<JsonNode> children = node.elements(); children.hasNext(); ) {
      stripXetHashes(children.next());
    }
  }

  // helpers

  private static void setTarget(final Context context, final Target target) {
    context.getAttributes().set(Target.class, target);
  }

  /**
   * The target set by {@link #get(Context)}, or for callers entering elsewhere (e.g. {@code hasContentFor}) the one
   * the request path implies.
   */
  private static Target target(final Context context) {
    Target target = context.getAttributes().get(Target.class);
    if (target != null) {
      return target;
    }
    String path = context.getRequest().getPath();
    return HuggingFacePaths.parseFile(path)
        .map(Target::fileMetadata)
        .orElseGet(() -> Target.api(path, rawQuery(context)));
  }

  @Nullable
  private static String rawQuery(final Context context) {
    String query = context.getRequest().getAttributes().get(Request.RAW_QUERY_STRING_ATTR, String.class);
    return query == null || query.isEmpty() ? null : query;
  }

  @Nullable
  private static String headerValue(final HttpResponse response, final String name) {
    Header header = response.getFirstHeader(name);
    return header == null ? null : header.getValue();
  }

  @VisibleForTesting
  @Nullable
  static String normalizeEtag(@Nullable final String etag) {
    if (etag == null) {
      return null;
    }
    String value = etag.startsWith("W/") ? etag.substring(2) : etag;
    return value.replace("\"", "");
  }

  @Nullable
  private static Long parseSize(@Nullable final String value) {
    try {
      return value == null ? null : Long.valueOf(value);
    }
    catch (NumberFormatException e) {
      return null;
    }
  }

  private HuggingFaceContentFacet content() {
    return getRepository().facet(HuggingFaceContentFacet.class);
  }

  /**
   * Body-less payload answering a {@code HEAD} with the size of the file.
   */
  private static final class HeadPayload
      implements Payload
  {
    private final long size;

    HeadPayload(@Nullable final Long size) {
      this.size = size == null ? UNKNOWN_SIZE : size;
    }

    @Override
    public InputStream openInputStream() {
      return InputStream.nullInputStream();
    }

    @Override
    public long getSize() {
      return size;
    }

    @Nullable
    @Override
    public String getContentType() {
      return null;
    }
  }
}
