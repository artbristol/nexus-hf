/*
 * Hugging Face proxy format for Nexus Repository Core.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 */
package org.sonatype.nexus.content.huggingface.internal.browse;

import org.sonatype.nexus.repository.content.browse.AssetPathBrowseNodeGenerator;
import org.sonatype.nexus.repository.huggingface.internal.HuggingFaceFormat;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Browse tree follows asset paths, e.g. {@code owner/name/resolve/<commit>/<file>}.
 */
@Component
@Qualifier(HuggingFaceFormat.NAME)
public class HuggingFaceBrowseNodeGenerator
    extends AssetPathBrowseNodeGenerator
{
}
