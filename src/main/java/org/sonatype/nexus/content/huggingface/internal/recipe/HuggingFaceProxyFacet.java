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
import java.util.Iterator;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import org.sonatype.nexus.content.huggingface.HuggingFaceContentFacet;
import org.sonatype.nexus.repository.cache.CacheController;
import org.sonatype.nexus.repository.cache.CacheInfo;
import org.sonatype.nexus.repository.content.facet.ContentProxyFacetSupport;
import org.sonatype.nexus.repository.http.HttpMethods;
import org.sonatype.nexus.repository.httpclient.HttpClientFacet;
import org.sonatype.nexus.repository.huggingface.HuggingFaceFileInfo;
import org.sonatype.nexus.repository.huggingface.HuggingFacePaths;
import org.sonatype.nexus.repository.huggingface.HuggingFacePaths.FileRef;
import org.sonatype.nexus.repository.view.Content;
import org.sonatype.nexus.repository.view.Context;
import org.sonatype.nexus.repository.view.Payload;
import org.sonatype.nexus.repository.view.Request;
import org.sonatype.nexus.repository.view.payloads.BytesPayload;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.net.HttpHeaders;
import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.HttpClient;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.Configurable;
import org.apache.http.client.methods.HttpHead;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.client.utils.HttpClientUtils;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import static org.sonatype.nexus.content.huggingface.HuggingFaceContentFacet.API_KIND;
import static org.sonatype.nexus.content.huggingface.HuggingFaceContentFacet.FILE_METADATA_KIND;
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

  private static final int MAX_API_PAGES = 200;

  private static final String XET_HASH_FIELD = "xetHash";

  private static final Pattern NEXT_LINK_PATTERN = Pattern.compile("<([^>]+)>\\s*;\\s*rel=\"?next\"?");

  private static final String JSON_CONTENT_TYPE = "application/json";

  private static final String META_PREFIX = "/.meta";

  private static final String META_SUFFIX = ".json";

  private final ObjectMapper objectMapper = new ObjectMapper();

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

    if (HttpMethods.HEAD.equals(context.getRequest().getAction())) {
      return withFileInfo(new Content(new HeadPayload(info.size())), info);
    }

    setTarget(context, Target.fileContent(file.at(info.commit())));
    Content content = super.get(context);
    return content == null ? null : withFileInfo(content, info);
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
    return content().get(target(context).assetPath()).orElse(null);
  }

  @Override
  protected Content store(final Context context, final Content content) throws IOException {
    Target target = target(context);
    return switch (target.kind()) {
      case FILE_CONTENT -> content().putFile(target.file(), content, expectedSha256(context));
      case FILE_METADATA -> content().putMetadata(target.assetPath(), FILE_METADATA_KIND, content);
      case API -> content().putMetadata(target.assetPath(), API_KIND, content);
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
    return getEscapeHelper().uriSegments(target(context).remotePath().substring(1));
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
   * The Hub answers 401 for repositories that do not exist or that the configured token cannot access; that is a
   * missing resource rather than a failure of the remote.
   */
  @Override
  protected void mayThrowProxyServiceException(final HttpResponse httpResponse) {
    if (httpResponse.getStatusLine().getStatusCode() == HttpStatus.SC_UNAUTHORIZED) {
      log.debug("Upstream answered 401 (missing, gated or private repository) in {}", getRepository().getName());
      return;
    }
    super.mayThrowProxyServiceException(httpResponse);
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
        if (status == HttpStatus.SC_NOT_FOUND || status == HttpStatus.SC_UNAUTHORIZED
            || status == HttpStatus.SC_FORBIDDEN) {
          log.debug("Upstream answered {} for {}", status, uri);
          return null;
        }
        mayThrowProxyServiceException(response);
        log.warn("Unexpected upstream status {} for {}", status, uri);
        return null;
      }
      finally {
        HttpClientUtils.closeQuietly(response);
      }
    }
    log.warn("Too many redirects resolving {}", uri);
    return null;
  }

  @Nullable
  private Content fileMetadataContent(
      final Context context,
      final URI uri,
      @Nullable final String commit,
      @Nullable final String etag,
      @Nullable final Long size) throws IOException
  {
    if (commit == null) {
      log.warn("Upstream response for {} has no {} header; is the remote URL a Hugging Face Hub?", uri,
          X_REPO_COMMIT);
      return null;
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
   * Fetches an API response, following and concatenating all pages of a paginated listing so that no upstream
   * pagination link reaches the client, and strips XET hints.
   */
  @Nullable
  private Content fetchApi(final Context context, @Nullable final Content stale) throws IOException {
    Content first = super.fetch(context, stale);
    if (first == null) {
      return null;
    }
    JsonNode body;
    try (InputStream in = first.openInputStream()) {
      body = objectMapper.readTree(in);
    }
    finally {
      first.close();
    }

    String next = nextPageUrl(context);
    for (int page = 1; next != null && body.isArray(); page++) {
      if (page >= MAX_API_PAGES) {
        log.warn("Listing {} has more than {} pages; truncating", context.getRequest().getPath(), MAX_API_PAGES);
        break;
      }
      Content more = fetch(next, context, null);
      if (more == null) {
        break;
      }
      try (InputStream in = more.openInputStream()) {
        ((ArrayNode) body).addAll((ArrayNode) objectMapper.readTree(in));
      }
      finally {
        more.close();
      }
      next = nextPageUrl(context);
    }

    stripXetHashes(body);
    Content content = new Content(new BytesPayload(objectMapper.writeValueAsBytes(body), JSON_CONTENT_TYPE));
    content.getAttributes().set(Content.CONTENT_ETAG, first.getAttributes().get(Content.CONTENT_ETAG));
    content.getAttributes().set(Content.CONTENT_LAST_MODIFIED, first.getAttributes().get(Content.CONTENT_LAST_MODIFIED));
    content.getAttributes().set(CacheInfo.class, first.getAttributes().get(CacheInfo.class));
    return content;
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
