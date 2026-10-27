/*
 * Hugging Face proxy format for Nexus Repository Core.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 */
package org.sonatype.nexus.repository.huggingface.internal.xet;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import org.sonatype.nexus.repository.huggingface.HuggingFacePaths;
import org.sonatype.nexus.repository.huggingface.internal.xet.HuggingFaceXetTokens.Claims;
import org.sonatype.nexus.security.authc.apikey.ApiKeyExtractor;
import org.sonatype.nexus.security.realm.RealmManager;

import jakarta.inject.Provider;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import static com.google.common.net.HttpHeaders.AUTHORIZATION;
import static java.util.Objects.requireNonNull;

/**
 * Finds the XET tokens of {@link HuggingFaceXetTokenRealm} in requests: storage tokens as bearer token of
 * reconstruction requests, and xorb range tokens as {@code token} query parameter of the xorb URLs this repository
 * hands out, each for its own range only. Only in requests to the repository a token was issued for, and only while
 * the realm is active; anything else is not a login attempt.
 */
@Component
@Qualifier(HuggingFaceXetTokenRealm.NAME)
public class HuggingFaceXetTokenExtractor
    implements ApiKeyExtractor
{
  public static final String TOKEN_PARAMETER = "token";

  private static final String BEARER = "Bearer ";

  /**
   * {@code /repository/<name>/.xet/...}, as the servlet sees it (still URL-encoded): the repository name and the path
   * within the repository.
   */
  private static final Pattern XET_REQUEST = Pattern.compile("^/repository/([^/]+)(/\\.xet/.+)$");

  private final HuggingFaceXetTokens tokens;

  private final Provider<RealmManager> realmManager;

  @Autowired
  public HuggingFaceXetTokenExtractor(final HuggingFaceXetTokens tokens, final Provider<RealmManager> realmManager) {
    this.tokens = requireNonNull(tokens);
    this.realmManager = requireNonNull(realmManager);
  }

  @Nullable
  @Override
  public String extract(final HttpServletRequest request) {
    String path = request.getRequestURI();
    if (path == null || !path.contains("/.xet/")) {
      return null;
    }
    String contextPath = request.getContextPath();
    if (contextPath != null && path.startsWith(contextPath)) {
      path = path.substring(contextPath.length());
    }
    Matcher matcher = XET_REQUEST.matcher(path);
    if (!matcher.matches()) {
      return null;
    }
    String repositoryPath = URLDecoder.decode(matcher.group(2), StandardCharsets.UTF_8);
    boolean reconstruction = HuggingFacePaths.parseXetReconstruction(repositoryPath).isPresent();
    boolean xorb = HuggingFacePaths.parseXetXorb(repositoryPath).isPresent();
    String token = reconstruction ? bearerToken(request) : xorb ? request.getParameter(TOKEN_PARAMETER) : null;
    if (token == null || !token.startsWith(HuggingFaceXetTokens.PREFIX)
        || !realmManager.get().isRealmEnabled(HuggingFaceXetTokenRealm.NAME)) {
      return null;
    }
    String repository = URLDecoder.decode(matcher.group(1), StandardCharsets.UTF_8);
    Optional<Claims> claims = tokens.verify(token)
        .filter(c -> c.repository().equals(repository))
        .filter(c -> reconstruction ? c.isStorageToken() : repositoryPath.equals(c.path()));
    // a token that is not valid here is not a login attempt: the request goes on as anonymous, and fails as such
    return claims.isPresent() ? token : null;
  }

  @Nullable
  private static String bearerToken(final HttpServletRequest request) {
    String header = request.getHeader(AUTHORIZATION);
    return header != null && header.startsWith(BEARER) ? header.substring(BEARER.length()).trim() : null;
  }
}
