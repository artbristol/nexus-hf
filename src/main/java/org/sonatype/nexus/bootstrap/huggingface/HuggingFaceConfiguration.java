/*
 * Hugging Face proxy format for Nexus Repository Core.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 */
package org.sonatype.nexus.bootstrap.huggingface;

import org.sonatype.nexus.content.huggingface.internal.store.HuggingFaceStoresProvider;

import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configures the content store bindings for the Hugging Face format.
 */
@Configuration
public class HuggingFaceConfiguration
{
  @Bean
  public static BeanDefinitionRegistryPostProcessor huggingFaceStoresProvider() {
    return new HuggingFaceStoresProvider();
  }
}
