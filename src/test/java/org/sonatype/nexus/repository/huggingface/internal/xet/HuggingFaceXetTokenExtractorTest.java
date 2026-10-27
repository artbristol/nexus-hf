/*
 * Hugging Face proxy format for Nexus Repository Core.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 */
package org.sonatype.nexus.repository.huggingface.internal.xet;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.sonatype.nexus.repository.huggingface.HuggingFacePaths.RepoRef;
import org.sonatype.nexus.repository.huggingface.HuggingFacePaths.RepoType;
import org.sonatype.nexus.repository.huggingface.internal.xet.HuggingFaceXetTokens.Claims;
import org.sonatype.nexus.security.realm.RealmManager;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.Test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A token that the extractor hands to the realm logs the request in as its user. That must only happen where the
 * token is meant to be used: a token taken from a request log, or one of another repository, must not log anyone in
 * anywhere else, the REST API in particular.
 */
public class HuggingFaceXetTokenExtractorTest
{
  private static final String HASH = "f8accece953fd366d4ce30597b97acc1ccedc3c785187a5ef6ecb4a8e1755122";

  private static final String RECONSTRUCTION = "/repository/hf-proxy/.xet/v2/reconstructions/" + HASH;

  private static final String RANGE = "/.xet/xorbs/" + HASH + "/486722-904005";

  private static final String XORB = "/repository/hf-proxy" + RANGE;

  private final HuggingFaceXetTokens tokens = new HuggingFaceXetTokens(
      Clock.fixed(Instant.parse("2026-10-27T12:00:00Z"), ZoneOffset.UTC),
      "0123456789abcdef0123456789abcdef".getBytes(UTF_8));

  private final RealmManager realmManager = mock(RealmManager.class);

  private final HuggingFaceXetTokenExtractor extractor = new HuggingFaceXetTokenExtractor(tokens, () -> realmManager);

  private final Claims claims =
      tokens.claims("hf-proxy", new RepoRef(RepoType.MODEL, "a/b", "main"), "alice", "NexusAuthenticatingRealm");

  private final String storageToken = tokens.issue(claims);

  private final String rangeToken = tokens.issue(tokens.forPath(claims, RANGE));

  {
    when(realmManager.isRealmEnabled(HuggingFaceXetTokenRealm.NAME)).thenReturn(true);
  }

  @Test
  public void storageTokensAreForReconstructions() {
    assertEquals(storageToken, extractor.extract(request("", RECONSTRUCTION, storageToken, null)));
    assertEquals(storageToken, extractor.extract(request("/nexus", "/nexus" + RECONSTRUCTION, storageToken, null)));
  }

  @Test
  public void rangeTokensAreForTheirRange() {
    assertEquals(rangeToken, extractor.extract(request("", XORB, null, rangeToken)));
    assertNull(extractor.extract(request("", "/repository/hf-proxy/.xet/xorbs/" + HASH + "/0-99", null, rangeToken)));
  }

  @Test
  public void tokensAreNothingElsewhere() {
    for (String path : new String[]{
        "/service/rest/v1/repositories", "/service/rest/v1/security/users", "/repository/hf-proxy/api/models/a/b",
        "/repository/hf-proxy/a/b/resolve/main/config.json", "/repository/hf-proxy/.xet/other",
        "/repository/other/.xet/v2/reconstructions/" + HASH, "/repository/other" + RANGE}) {
      assertNull(path, extractor.extract(request("", path, storageToken, storageToken)));
      assertNull(path, extractor.extract(request("", path, rangeToken, rangeToken)));
    }
  }

  @Test
  public void eachTokenOnlyWhereItIsSent() {
    assertNull("range token as storage token", extractor.extract(request("", RECONSTRUCTION, rangeToken, null)));
    assertNull("storage token in a URL", extractor.extract(request("", XORB, null, storageToken)));
    assertNull("token in the URL of a reconstruction",
        extractor.extract(request("", RECONSTRUCTION, null, storageToken)));
    assertNull("bearer token for a range", extractor.extract(request("", XORB, rangeToken, null)));
  }

  @Test
  public void nothingWhileTheRealmIsInactive() {
    when(realmManager.isRealmEnabled(HuggingFaceXetTokenRealm.NAME)).thenReturn(false);
    assertNull(extractor.extract(request("", RECONSTRUCTION, storageToken, null)));
    assertNull(extractor.extract(request("", XORB, null, rangeToken)));
  }

  @Test
  public void invalidTokensAreNotLoginAttempts() {
    String forged = storageToken.substring(0, storageToken.lastIndexOf('.') + 1) + "AAAA";
    assertNull(extractor.extract(request("", RECONSTRUCTION, forged, null)));
    assertNull(extractor.extract(request("", RECONSTRUCTION, "hf_some_hub_token", null)));
    HuggingFaceXetTokens later = new HuggingFaceXetTokens(
        Clock.fixed(Instant.parse("2026-10-27T13:00:00Z"), ZoneOffset.UTC),
        "0123456789abcdef0123456789abcdef".getBytes(UTF_8));
    assertNull("expired", new HuggingFaceXetTokenExtractor(later, () -> realmManager)
        .extract(request("", RECONSTRUCTION, storageToken, null)));
  }

  private static HttpServletRequest request(
      final String contextPath,
      final String uri,
      final String bearer,
      final String parameter)
  {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getContextPath()).thenReturn(contextPath);
    when(request.getRequestURI()).thenReturn(uri);
    when(request.getHeader("Authorization")).thenReturn(bearer == null ? null : "Bearer " + bearer);
    when(request.getParameter(HuggingFaceXetTokenExtractor.TOKEN_PARAMETER)).thenReturn(parameter);
    return request;
  }
}
