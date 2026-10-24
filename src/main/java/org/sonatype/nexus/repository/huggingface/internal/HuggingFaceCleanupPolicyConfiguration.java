/*
 * Hugging Face proxy format for Nexus Repository Core.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 */
package org.sonatype.nexus.repository.huggingface.internal;

import java.util.Map;

import org.sonatype.nexus.cleanup.config.CleanupPolicyConfiguration;

import com.google.common.collect.ImmutableMap;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import static org.sonatype.nexus.cleanup.config.CleanupPolicyConstants.IS_PRERELEASE_KEY;
import static org.sonatype.nexus.cleanup.config.CleanupPolicyConstants.LAST_BLOB_UPDATED_KEY;
import static org.sonatype.nexus.cleanup.config.CleanupPolicyConstants.LAST_DOWNLOADED_KEY;
import static org.sonatype.nexus.cleanup.config.CleanupPolicyConstants.REGEX_KEY;
import static org.sonatype.nexus.cleanup.config.CleanupPolicyConstants.RETAIN_KEY;
import static org.sonatype.nexus.cleanup.config.CleanupPolicyConstants.RETAIN_SORT_BY_KEY;

/**
 * Cleanup policy criteria available for Hugging Face repositories.
 */
@Component
@Qualifier(HuggingFaceFormat.NAME)
public class HuggingFaceCleanupPolicyConfiguration
    implements CleanupPolicyConfiguration
{
  @Override
  public Map<String, Boolean> getConfiguration() {
    return ImmutableMap.<String, Boolean>builder()
        .put(LAST_BLOB_UPDATED_KEY, true)
        .put(LAST_DOWNLOADED_KEY, true)
        .put(IS_PRERELEASE_KEY, false)
        .put(REGEX_KEY, true)
        .put(RETAIN_KEY, false)
        .put(RETAIN_SORT_BY_KEY, false)
        .build();
  }
}
