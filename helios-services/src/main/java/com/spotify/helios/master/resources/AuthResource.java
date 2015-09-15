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

import com.codahale.metrics.annotation.ExceptionMetered;
import com.codahale.metrics.annotation.Timed;
import com.spotify.helios.authentication.Authorizer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Response;

import static javax.ws.rs.core.MediaType.APPLICATION_JSON;

@Path("/_auth")
public class AuthResource {

  private static final Logger log = LoggerFactory.getLogger(AuthResource.class);

  private Authorizer authorizer;

  public AuthResource(final Authorizer authorizer) {
    this.authorizer = authorizer;
  }

  /**
   * TBA
   * @return A Response containing CRT auth challenge or token
   */
  @GET
  @Produces(APPLICATION_JSON)
  @Timed
  @ExceptionMetered
  public Response handleAuthentication(@Context HttpHeaders headers) {
//                                       @HeaderParam("X-CHAP") String xChap) {
    final String xChap = headers.getRequestHeaders().getFirst("X-CHAP");
    final String[] xChapParts = xChap.split(":");

    if (xChapParts.length < 2) {
      return Response.status(400).entity(
          "X-CHAP header must be of the form <type>:<foo>").build();
    }

    switch (xChapParts[0]) {
      case "request":
        try {
          // TODO (dxia) Throw exception if this exceeds a certain amount of time.
          String challenge = authorizer.createChallenge(xChapParts[1]);
          return Response.ok().header("X-CHAP", "challenge:" + challenge).build();
        } catch (IllegalArgumentException ignored) {
          log.info("Failed to deserialize CRT auth request string '{}'. "
                   + "Client is probably not following the CRT auth protocol or version.", xChap);
          return Response.status(400).entity("You did something wrong.").build();
        }
      case "response":
        String token = authorizer.createToken(xChapParts[1]);
        return Response.ok().header("X-CHAP", "token:" + token).build();
      default:
        return Response.status(400).entity("Unknown action " + xChapParts[0]).build();
    }
  }
}
