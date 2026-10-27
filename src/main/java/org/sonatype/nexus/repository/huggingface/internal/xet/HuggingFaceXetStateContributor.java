/*
 * Hugging Face proxy format for Nexus Repository Core.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 */
package org.sonatype.nexus.repository.huggingface.internal.xet;

import java.util.Map;

import org.sonatype.nexus.rapture.StateContributor;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import static org.sonatype.nexus.common.app.FeatureFlags.HUGGING_FACE_XET_ENABLED;

/**
 * Tells the UI that XET is supported, which shows the "Enable XET" setting of the repository forms (part of
 * nexus-public, hidden by default). {@code nexus.format.huggingface.xet.enabled=false} turns XET off everywhere.
 */
@Component
public class HuggingFaceXetStateContributor
    implements StateContributor
{
  private final boolean enabled;

  @Autowired
  public HuggingFaceXetStateContributor(@Value("${" + HUGGING_FACE_XET_ENABLED + ":true}") final boolean enabled) {
    this.enabled = enabled;
  }

  @Override
  public Map<String, Object> getState() {
    return Map.of(HUGGING_FACE_XET_ENABLED, enabled);
  }
}
