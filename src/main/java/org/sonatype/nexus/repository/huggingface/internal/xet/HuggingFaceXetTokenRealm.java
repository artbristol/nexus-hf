/*
 * Hugging Face proxy format for Nexus Repository Core.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 */
package org.sonatype.nexus.repository.huggingface.internal.xet;

import java.util.Optional;

import org.sonatype.nexus.common.Description;
import org.sonatype.nexus.security.UserPrincipalsHelper;
import org.sonatype.nexus.security.authc.NexusApiKeyAuthenticationToken;
import org.sonatype.nexus.security.user.UserNotFoundException;

import org.apache.shiro.authc.AuthenticationInfo;
import org.apache.shiro.authc.AuthenticationToken;
import org.apache.shiro.authc.SimpleAuthenticationInfo;
import org.apache.shiro.realm.AuthenticatingRealm;
import org.apache.shiro.subject.PrincipalCollection;
import org.apache.shiro.subject.SimplePrincipalCollection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import static java.util.Objects.requireNonNull;

/**
 * Authenticates the requests of XET clients with the tokens this Nexus issued to them.
 *
 * XET clients ({@code hf_xet}) send their storage requests with {@code Authorization: Bearer <XET token>}, and fetch
 * storage data from presigned URLs without any credentials, so Basic authentication cannot reach them. This realm
 * accepts the tokens issued by {@link HuggingFaceXetTokens} in their place, as the user they were issued to; the
 * {@link HuggingFaceXetTokenExtractor} finds them, and only in XET storage requests to the repository they were issued
 * for. Users keep their own permissions.
 */
@Component
@Qualifier(HuggingFaceXetTokenRealm.NAME)
@Description(HuggingFaceXetTokenRealm.DESCRIPTION)
public class HuggingFaceXetTokenRealm
    extends AuthenticatingRealm
{
  public static final String NAME = "HuggingFaceXetToken";

  public static final String DESCRIPTION = "Hugging Face XET Token Realm";

  private static final Logger log = LoggerFactory.getLogger(HuggingFaceXetTokenRealm.class);

  private final HuggingFaceXetTokens tokens;

  private final UserPrincipalsHelper principalsHelper;

  @Autowired
  public HuggingFaceXetTokenRealm(final HuggingFaceXetTokens tokens, final UserPrincipalsHelper principalsHelper) {
    this.tokens = requireNonNull(tokens);
    this.principalsHelper = requireNonNull(principalsHelper);
    setName(NAME);
    // a token is only checked once per request, and checking it is cheap
    setAuthenticationCachingEnabled(false);
  }

  @Override
  public boolean supports(final AuthenticationToken token) {
    return token instanceof NexusApiKeyAuthenticationToken && NAME.equals(token.getPrincipal());
  }

  @Override
  protected AuthenticationInfo doGetAuthenticationInfo(final AuthenticationToken token) {
    char[] credentials = (char[]) token.getCredentials();
    Optional<HuggingFaceXetTokens.Claims> claims = tokens.verify(new String(credentials));
    if (claims.isEmpty()) {
      return null;
    }
    PrincipalCollection principals = new SimplePrincipalCollection(claims.get().userId(), claims.get().realm());
    try {
      if (!principalsHelper.getUserStatus(principals).isActive()) {
        log.debug("XET token of inactive user {}", claims.get().userId());
        return null;
      }
    }
    catch (UserNotFoundException e) {
      log.debug("XET token of unknown user {}", claims.get().userId());
      return null;
    }
    return new SimpleAuthenticationInfo(principals, credentials);
  }
}
