/*
 * Hugging Face proxy format for Nexus Repository Core.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 */
package org.sonatype.nexus.content.huggingface.internal.store;

import org.sonatype.nexus.content.huggingface.internal.browse.HuggingFaceBrowseNodeDAO;
import org.sonatype.nexus.repository.content.store.FormatStoreSupport;
import org.sonatype.nexus.repository.huggingface.internal.HuggingFaceFormat;

import org.springframework.beans.factory.annotation.Qualifier;

/**
 * Configures the content store bindings for the Hugging Face format.
 */
@Qualifier(HuggingFaceFormat.NAME)
public class HuggingFaceStoresProvider
    extends FormatStoreSupport<HuggingFaceContentRepositoryDAO, HuggingFaceComponentDAO, HuggingFaceAssetDAO, HuggingFaceAssetBlobDAO, HuggingFaceBrowseNodeDAO>
{
  // nothing to add...
}
