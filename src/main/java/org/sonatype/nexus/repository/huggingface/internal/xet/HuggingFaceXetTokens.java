/*
 * Hugging Face proxy format for Nexus Repository Core.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 */
package org.sonatype.nexus.repository.huggingface.internal.xet;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.Optional;

import javax.annotation.Nullable;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.sonatype.nexus.repository.huggingface.HuggingFacePaths.RepoRef;
import org.sonatype.nexus.repository.huggingface.HuggingFacePaths.RepoType;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import org.springframework.stereotype.Component;

import static java.util.Objects.requireNonNull;

/**
 * Issues and verifies the XET access tokens this repository hands to clients in place of the Hub's.
 *
 * A token names the Nexus repository, the Hub repository and revision it was issued for (the Hub's own tokens are
 * scoped to one repository, and the proxy needs one of those to talk to XET storage), and the Nexus user it was issued
 * to, whom it authenticates (see {@link HuggingFaceXetTokenRealm}). It is signed with a key that lives as long as this
 * Nexus process: tokens are short-lived, and clients fetch new ones when theirs expire.
 *
 * Storage tokens, which clients send as bearer tokens, are for reconstruction requests. The tokens in the xorb URLs of
 * a reconstruction, which end up in request logs, are each for that one xorb range ({@link Claims#path}).
 */
@Component
public class HuggingFaceXetTokens
{
  /**
   * Prefix of every token, which tells them apart from other bearer tokens.
   */
  public static final String PREFIX = "hfxet1.";

  /**
   * How long a token is valid; as long as the Hub's presigned storage URLs, which are issued alongside.
   */
  public static final long LIFETIME_SECONDS = 3600;

  private static final String HMAC = "HmacSHA256";

  private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

  private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

  private final ObjectMapper objectMapper = new ObjectMapper();

  private final SecretKeySpec key;

  private final Clock clock;

  public HuggingFaceXetTokens() {
    this(Clock.systemUTC(), randomKey());
  }

  @VisibleForTesting
  HuggingFaceXetTokens(final Clock clock, final byte[] key) {
    this.clock = requireNonNull(clock);
    this.key = new SecretKeySpec(key, HMAC);
  }

  /**
   * What a token says.
   *
   * @param repository name of the Nexus repository it is valid for
   * @param userId     Nexus user it authenticates
   * @param realm      realm of that user (e.g. {@code NexusAuthenticatingRealm}, {@code LdapRealm})
   * @param path       the one xorb range path it is for, or {@code null} for a storage token
   * @param expires    end of validity, in seconds since the epoch
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Claims(
      String repository,
      RepoType type,
      String repoId,
      String revision,
      String userId,
      String realm,
      @Nullable String path,
      long expires)
  {
    public Claims {
      requireNonNull(repository);
      requireNonNull(type);
      requireNonNull(repoId);
      requireNonNull(revision);
      requireNonNull(userId);
      requireNonNull(realm);
    }

    public RepoRef repo() {
      return new RepoRef(type, repoId, revision);
    }

    /**
     * Whether this is a storage token, not a token for one xorb range.
     */
    public boolean isStorageToken() {
      return path == null;
    }
  }

  /**
   * A storage token for the given user, valid from now on for {@link #LIFETIME_SECONDS}.
   */
  public Claims claims(final String repository, final RepoRef repo, final String userId, final String realm) {
    return new Claims(repository, repo.type(), repo.repoId(), repo.revision(), userId, realm, null,
        clock.instant().getEpochSecond() + LIFETIME_SECONDS);
  }

  /**
   * A token of the same user for one xorb range only, valid from now on for {@link #LIFETIME_SECONDS}.
   */
  public Claims forPath(final Claims storageToken, final String path) {
    return new Claims(storageToken.repository(), storageToken.type(), storageToken.repoId(), storageToken.revision(),
        storageToken.userId(), storageToken.realm(), requireNonNull(path),
        clock.instant().getEpochSecond() + LIFETIME_SECONDS);
  }

  public String issue(final Claims claims) {
    try {
      String payload = PREFIX + ENCODER.encodeToString(objectMapper.writeValueAsBytes(claims));
      return payload + "." + ENCODER.encodeToString(sign(payload));
    }
    catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * The claims of a token, if it was issued by this Nexus and has not expired.
   */
  public Optional<Claims> verify(@Nullable final String token) {
    if (token == null || !token.startsWith(PREFIX)) {
      return Optional.empty();
    }
    int dot = token.lastIndexOf('.');
    if (dot <= PREFIX.length()) {
      return Optional.empty();
    }
    String payload = token.substring(0, dot);
    try {
      byte[] signature = DECODER.decode(token.substring(dot + 1));
      if (!MessageDigest.isEqual(signature, sign(payload))) {
        return Optional.empty();
      }
      Claims claims = objectMapper.readValue(DECODER.decode(payload.substring(PREFIX.length())), Claims.class);
      return claims.expires() > clock.instant().getEpochSecond() ? Optional.of(claims) : Optional.empty();
    }
    catch (IllegalArgumentException | IOException e) {
      return Optional.empty();
    }
  }

  private byte[] sign(final String payload) {
    try {
      Mac mac = Mac.getInstance(HMAC);
      mac.init(key);
      return mac.doFinal(payload.getBytes(StandardCharsets.US_ASCII));
    }
    catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  private static byte[] randomKey() {
    byte[] key = new byte[32];
    new SecureRandom().nextBytes(key);
    return key;
  }
}
