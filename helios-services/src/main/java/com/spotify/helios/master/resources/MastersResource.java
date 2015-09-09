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

package com.spotify.helios.master.resources;

import com.google.common.io.CharStreams;

import com.codahale.metrics.annotation.ExceptionMetered;
import com.codahale.metrics.annotation.Timed;
import com.spotify.crtauth.CrtAuthServer;
import com.spotify.crtauth.exceptions.InvalidInputException;
import com.spotify.crtauth.keyprovider.InMemoryKeyProvider;
import com.spotify.crtauth.utils.TraditionalKeyParser;
import com.spotify.helios.master.MasterModel;
import com.spotify.helios.master.auth.User;

import java.io.FileReader;
import java.io.IOException;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.util.List;

import javax.inject.Inject;
import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Response;

import io.dropwizard.auth.Auth;

import static javax.ws.rs.core.MediaType.APPLICATION_JSON;

@Path("/masters")
public class MastersResource {

  private final MasterModel model;
  private CrtAuthServer crtAuthServer;
  private static final String PUBLIC_KEY;
  static {
    try {
      PUBLIC_KEY = CharStreams.toString(new FileReader("/Users/dxia/src/helios/id_rsa.pub"));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }


  public MastersResource(final MasterModel model) {
    this.model = model;
    this.crtAuthServer = makeCrtAuthServer();
  }

  private static CrtAuthServer makeCrtAuthServer() {
    InMemoryKeyProvider keyProvider = new InMemoryKeyProvider();

    try {
      RSAPublicKeySpec publicKeySpec = TraditionalKeyParser.parsePemPublicKey(PUBLIC_KEY);
      KeyFactory keyFactory = KeyFactory.getInstance("RSA");

      RSAPublicKey publicKey = (RSAPublicKey) keyFactory.generatePublic(publicKeySpec);
      keyProvider.putKey("test", publicKey);

    } catch (Exception e) {
      throw new RuntimeException(e);
    }

    return new CrtAuthServer.Builder()
        .setServerName("localhost")
        .setKeyProvider(keyProvider)
        .setSecret(new byte[] {(byte)0xde, (byte)0xad, (byte)0xbe, (byte)0xef})
        .build();

  }

  @SuppressWarnings("UnusedDeclaration")
  @Inject
  public void setCrtAuthServer(CrtAuthServer crtAuthServer) {
    this.crtAuthServer = crtAuthServer;
  }

  /**
   * Returns a list of names of running Helios masters.
   * @return The list of names.
   */
  @GET
  @Produces(APPLICATION_JSON)
  @Timed
  @ExceptionMetered
  public List<String> list() {
    return model.getRunningMasters();
  }

  @Path("/_auth")
  @GET
  public Response handleAuthentication(@HeaderParam("X-CHAP") String xChap) {
    final String[] xChapParts = xChap.split(":");

    if (xChapParts.length < 2) {
      return Response.status(400).entity("X-CHAP header must be of the form <type>:<foo>").build();
    }

    try {
      if (xChapParts[0].equals("request")) {
        String challenge = crtAuthServer.createChallenge(xChapParts[1]);
        return Response.ok().header("X-CHAP", "challenge:" + challenge).build();
      } else if (xChapParts[0].equals("response")) {
        String token = crtAuthServer.createToken(xChapParts[1]);
        return Response.ok().header("X-CHAP", "token:" + token).build();
      } else {
        return Response.status(400).entity("Unknown action " + xChapParts[0]).build();
      }
    } catch (InvalidInputException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Returns a list of names of running Helios masters.
   * @return The list of names.
   */
  @GET
  @Path("/authed")
  @Produces(APPLICATION_JSON)
  @Timed
  @ExceptionMetered
  public List<String> list(@Auth User user) {
    return model.getRunningMasters();
  }
}
