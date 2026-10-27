/*
 * Hugging Face proxy format for Nexus Repository Core.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 */
package org.sonatype.nexus.repository.huggingface.rest;

import org.sonatype.nexus.content.huggingface.internal.recipe.HuggingFaceXetFacet;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.huggingface.internal.HuggingFaceFormat;
import org.sonatype.nexus.repository.rest.api.SimpleApiRepositoryAdapter;
import org.sonatype.nexus.repository.rest.api.model.AbstractApiRepository;
import org.sonatype.nexus.repository.routing.RoutingRuleStore;
import org.sonatype.nexus.repository.types.ProxyType;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Adds the {@code xet} section to Hugging Face proxy repositories returned by the REST API, which the repository forms
 * of the UI read.
 */
@Component
@Qualifier(HuggingFaceFormat.NAME)
public class HuggingFaceApiRepositoryAdapter
    extends SimpleApiRepositoryAdapter
{
  @Autowired
  public HuggingFaceApiRepositoryAdapter(final RoutingRuleStore routingRuleStore) {
    super(routingRuleStore);
  }

  @Override
  public AbstractApiRepository adapt(final Repository repository) {
    if (!ProxyType.NAME.equals(repository.getType().toString())) {
      return super.adapt(repository);
    }
    Boolean xetEnabled = repository.getConfiguration().attributes(HuggingFaceXetFacet.CONFIG_KEY)
        .get("enabled", Boolean.class);
    return new HuggingFaceProxyApiRepository(
        repository.getName(),
        repository.getUrl(),
        repository.getConfiguration().isOnline(),
        getStorageAttributes(repository),
        getCleanupPolicyAttributes(repository),
        getProxyAttributes(repository),
        getNegativeCacheAttributes(repository),
        getHttpClientAttributes(repository),
        getRoutingRuleName(repository),
        getReplicationAttributes(repository),
        new HuggingFaceXetAttributes(Boolean.TRUE.equals(xetEnabled)));
  }
}
