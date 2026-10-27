/*
 * Hugging Face proxy format for Nexus Repository Core.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 */
package org.sonatype.nexus.repository.huggingface.rest;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The {@code xet} section of a Hugging Face proxy repository, as the repository forms of the UI send it.
 */
public class HuggingFaceXetAttributes
{
  @Schema(description = "Whether clients may download XET files with the XET protocol, through this repository",
      example = "true")
  private final Boolean enabled;

  @JsonCreator
  public HuggingFaceXetAttributes(@JsonProperty("enabled") final Boolean enabled) {
    this.enabled = enabled;
  }

  public Boolean getEnabled() {
    return enabled;
  }
}
