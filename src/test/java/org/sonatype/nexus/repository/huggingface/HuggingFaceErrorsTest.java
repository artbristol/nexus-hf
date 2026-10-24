/*
 * Hugging Face proxy format for Nexus Repository Core.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 */
package org.sonatype.nexus.repository.huggingface;

import org.sonatype.nexus.repository.huggingface.HuggingFaceErrors.HubError;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class HuggingFaceErrorsTest
{
  @Test
  public void roundTrips() {
    assertEquals(new HubError("EntryNotFound", "Entry not found"),
        HuggingFaceErrors.decode(HuggingFaceErrors.encode("EntryNotFound", "Entry not found")).orElseThrow());
    assertEquals(new HubError(null, "Invalid username or password."),
        HuggingFaceErrors.decode(HuggingFaceErrors.encode(null, "Invalid username or password.")).orElseThrow());
    assertEquals(new HubError("GatedRepo", null),
        HuggingFaceErrors.decode(HuggingFaceErrors.encode("GatedRepo", null)).orElseThrow());
  }

  @Test
  public void ignoresOtherStatusMessages() {
    assertFalse(HuggingFaceErrors.decode(null).isPresent());
    assertFalse(HuggingFaceErrors.decode("Remote Auto Blocked until 2026-10-24T19:12:17.892Z").isPresent());
    assertFalse(HuggingFaceErrors.decode("Not Found").isPresent());
  }
}
