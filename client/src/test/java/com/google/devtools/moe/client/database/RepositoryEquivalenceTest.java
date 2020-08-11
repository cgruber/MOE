/*
 * Copyright (c) 2011 Google, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.devtools.moe.client.database;

import com.google.devtools.moe.client.repositories.Revision;

import junit.framework.TestCase;

/**
 * Tests for {@link RepositoryEquivalence}
 */
public class RepositoryEquivalenceTest extends TestCase {

  public void testHasRevision() throws Exception {
    RepositoryEquivalence e =
        new RepositoryEquivalence(
            new Revision(1, "name1"), new Revision(2, "name2"));
    assertTrue(e.hasRevision(new Revision(2, "name2")));
    assertFalse(e.hasRevision(new Revision(1, "name3")));
  }

  public void testGetOtherRevision() throws Exception {
    Revision r1 = new Revision(1, "name1");
    Revision r2 = new Revision(2, "name2");
    RepositoryEquivalence e = new RepositoryEquivalence(r1, r2);
    assertEquals(e.get(r1), r2);
    assertEquals(e.get(r2), r1);
    assertNull(e.get(new Revision(1, "name3")));
  }
}
