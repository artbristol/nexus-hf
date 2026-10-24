/*
 * Hugging Face proxy format for Nexus Repository Core.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 */
package org.sonatype.nexus.repository.huggingface.internal;

import org.sonatype.nexus.repository.httpclient.AutoBlockConfiguration;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import static org.sonatype.nexus.repository.http.HttpStatus.PROXY_AUTHENTICATION_REQUIRED;

/**
 * The Hub answers 401 for repositories that do not exist or that are gated or private, so a 401 says nothing about
 * the availability of the remote; auto-blocking on it would take the whole proxy down after a single typo.
 */
@Component
@Qualifier(HuggingFaceFormat.NAME)
public class HuggingFaceAutoBlockConfiguration
    implements AutoBlockConfiguration
{
  @Override
  public boolean shouldBlock(final int statusCode) {
    return statusCode == PROXY_AUTHENTICATION_REQUIRED || statusCode >= 500;
  }
}
