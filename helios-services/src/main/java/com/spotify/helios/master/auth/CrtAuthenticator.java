/*
 * Copyright (c) 2014 Spotify AB.
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

package com.spotify.helios.master.auth;

import com.google.common.base.Optional;

import com.spotify.crtauth.CrtAuthServer;

import io.dropwizard.auth.AuthenticationException;
import io.dropwizard.auth.Authenticator;

public class CrtAuthenticator implements Authenticator<String, User> {

  private final CrtAuthServer crtAuthServer;

  public CrtAuthenticator(final CrtAuthServer crtAuthServer) {
    this.crtAuthServer = crtAuthServer;
  }

  @Override
  public Optional<User> authenticate(final String s) throws AuthenticationException {
    final String username;
    try {
      username = crtAuthServer.validateToken(s.split(":")[1]);
    } catch (Exception e) {
      return Optional.absent();
    }
    return Optional.of(new User(username));
  }
}
