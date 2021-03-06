/*
 * Copyright (c) 2015 Spotify AB.
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.spotify.helios.common.descriptors;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class HostSelectorTest {

  @Test
  public void testParseEquals() {
    assertEquals(new HostSelector("A", HostSelector.Operator.EQUALS, "B"),
                 HostSelector.parse("A=B"));
    assertEquals(new HostSelector("A", HostSelector.Operator.EQUALS, "B"),
                 HostSelector.parse("A = B"));
    assertEquals(new HostSelector("A", HostSelector.Operator.EQUALS, "B"),
                 HostSelector.parse("A =B"));
    assertEquals(new HostSelector("A", HostSelector.Operator.EQUALS, "B"),
                 HostSelector.parse("A= B"));
    assertEquals(new HostSelector("A", HostSelector.Operator.EQUALS, "B"),
                 HostSelector.parse("A\t\t=  B"));
  }

  @Test
  public void testParseNotEquals() {
    assertEquals(new HostSelector("A", HostSelector.Operator.NOT_EQUALS, "B"),
                 HostSelector.parse("A!=B"));
    assertEquals(new HostSelector("A", HostSelector.Operator.NOT_EQUALS, "B"),
                 HostSelector.parse("A != B"));
    assertEquals(new HostSelector("A", HostSelector.Operator.NOT_EQUALS, "B"),
                 HostSelector.parse("A !=B"));
    assertEquals(new HostSelector("A", HostSelector.Operator.NOT_EQUALS, "B"),
                 HostSelector.parse("A!= B"));
    assertEquals(new HostSelector("A", HostSelector.Operator.NOT_EQUALS, "B"),
                 HostSelector.parse("A\t\t!=  B"));
  }

  @Test
  public void testParseAllowedCharacters() {
    assertEquals(new HostSelector("foo", HostSelector.Operator.EQUALS, "123"),
                 HostSelector.parse("foo=123"));
    assertEquals(new HostSelector("_abc", HostSelector.Operator.NOT_EQUALS, "d-e"),
                 HostSelector.parse("_abc!=d-e"));
  }

  @Test
  public void testParseDisallowedCharacters() {
    assertNull(HostSelector.parse("foo = @123"));
    assertNull(HostSelector.parse("f/oo = 123"));
    // Verify equal not allowed in label and operand
    assertNull(HostSelector.parse("f=oo = 123"));
    assertNull(HostSelector.parse("foo = 12=3"));
    // Verify spaces not allowed in label and operand
    assertNull(HostSelector.parse("fo o = 123"));
    assertNull(HostSelector.parse("foo = 1 23"));
    // Verify ! not allowed in label and operand
    assertNull(HostSelector.parse("foo=!123"));
    assertNull(HostSelector.parse("!foo=bar"));
    // Verify fails on unknown operators
    assertNull(HostSelector.parse("foo or 123"));
    assertNull(HostSelector.parse("foo==123"));
    assertNull(HostSelector.parse("foo&&123"));
    // Verify fails on empty label or operand
    assertNull(HostSelector.parse("=123"));
    assertNull(HostSelector.parse(" =123"));
    assertNull(HostSelector.parse(" = 123"));
    assertNull(HostSelector.parse("foo="));
    assertNull(HostSelector.parse("foo= "));
    assertNull(HostSelector.parse("foo = "));
  }

  @Test
  public void testEqualsMatch() {
    final HostSelector hostSelector = HostSelector.parse("A=B");
    assertTrue(hostSelector.matches("B"));
    assertFalse(hostSelector.matches("Bb"));
    assertFalse(hostSelector.matches("b"));
    assertFalse(hostSelector.matches("A"));
  }

  @Test
  public void testNotEqualsMatch() {
    final HostSelector hostSelector = HostSelector.parse("A!=B");
    assertFalse(hostSelector.matches("B"));
    assertTrue(hostSelector.matches("Bb"));
    assertTrue(hostSelector.matches("b"));
    assertTrue(hostSelector.matches("A"));
  }

  @Test
  public void testToPrettyString() {
    assertEquals("A != B", HostSelector.parse("A!=B").toPrettyString());
    assertEquals("A = B", HostSelector.parse("A=B").toPrettyString());
  }
}
