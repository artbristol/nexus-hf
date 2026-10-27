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
import java.util.Base64;
import java.util.Optional;

import org.sonatype.nexus.repository.huggingface.HuggingFacePaths.RepoRef;
import org.sonatype.nexus.repository.huggingface.HuggingFacePaths.RepoType;
import org.sonatype.nexus.repository.huggingface.internal.xet.HuggingFaceXetTokens.Claims;

import org.junit.Test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HuggingFaceXetTokensTest
{
  private static final Instant NOW = Instant.parse("2026-10-26T12:00:00Z");

  private static final byte[] KEY = "0123456789abcdef0123456789abcdef".getBytes(UTF_8);

  private static final RepoRef REPO = new RepoRef(RepoType.MODEL, "owner/name", "refs/pr/1");

  private final HuggingFaceXetTokens tokens = at(NOW, KEY);

  @Test
  public void verifiesItsOwnTokens() {
    Claims claims = tokens.claims("hf-proxy", REPO, "alice", "NexusAuthenticatingRealm");
    assertEquals(NOW.getEpochSecond() + HuggingFaceXetTokens.LIFETIME_SECONDS, claims.expires());
    assertEquals(Optional.of(claims), tokens.verify(tokens.issue(claims)));
    assertEquals(REPO, claims.repo());
  }

  @Test
  public void tokensFitInHeadersAndUrls() {
    String token = tokens.issue(tokens.claims("hf-proxy", REPO, "alice", "NexusAuthenticatingRealm"));
    assertTrue(token.startsWith(HuggingFaceXetTokens.PREFIX));
    assertTrue(token, token.matches("^[A-Za-z0-9._-]+$"));
  }

  @Test
  public void rejectsExpiredTokens() {
    String token = tokens.issue(tokens.claims("hf-proxy", REPO, "alice", "NexusAuthenticatingRealm"));
    Instant later = NOW.plusSeconds(HuggingFaceXetTokens.LIFETIME_SECONDS);
    assertTrue(at(later.minusSeconds(1), KEY).verify(token).isPresent());
    assertFalse(at(later, KEY).verify(token).isPresent());
  }

  @Test
  public void rejectsTokensOfAnotherKey() {
    String token = at(NOW, "another key, also 32 bytes long!".getBytes(UTF_8))
        .issue(tokens.claims("hf-proxy", REPO, "alice", "NexusAuthenticatingRealm"));
    assertFalse(tokens.verify(token).isPresent());
  }

  @Test
  public void rejectsAlteredClaims() {
    String token = tokens.issue(tokens.claims("hf-proxy", REPO, "alice", "NexusAuthenticatingRealm"));
    String payload = token.substring(HuggingFaceXetTokens.PREFIX.length(), token.lastIndexOf('.'));
    String json = new String(Base64.getUrlDecoder().decode(payload), UTF_8);
    String forged = HuggingFaceXetTokens.PREFIX
        + Base64.getUrlEncoder().withoutPadding().encodeToString(json.replace("alice", "admin").getBytes(UTF_8))
        + token.substring(token.lastIndexOf('.'));
    assertFalse(tokens.verify(forged).isPresent());
  }

  @Test
  public void rejectsGarbage() {
    for (String garbage : new String[]{null, "", "hfxet1.", "hfxet1..", "hfxet1.e30.", "Bearer x", "hf_abc",
        "hfxet1.%%%.%%%"}) {
      assertFalse(String.valueOf(garbage), tokens.verify(garbage).isPresent());
    }
  }

  private static HuggingFaceXetTokens at(final Instant instant, final byte[] key) {
    return new HuggingFaceXetTokens(Clock.fixed(instant, ZoneOffset.UTC), key);
  }
}
