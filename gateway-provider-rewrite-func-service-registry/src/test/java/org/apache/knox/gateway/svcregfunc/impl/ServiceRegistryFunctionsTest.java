/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.knox.gateway.svcregfunc.impl;

import org.apache.http.auth.BasicUserPrincipal;
import org.apache.knox.gateway.filter.AbstractGatewayFilter;
import org.apache.knox.gateway.filter.rewrite.api.UrlRewriteServletContextListener;
import org.apache.knox.gateway.filter.rewrite.api.UrlRewriteServletFilter;
import org.apache.knox.gateway.services.ServiceType;
import org.apache.knox.gateway.services.GatewayServices;
import org.apache.knox.gateway.services.registry.ServiceRegistry;
import org.apache.knox.gateway.util.urltemplate.Parser;
import org.apache.knox.test.TestUtils;
import org.apache.knox.test.log.NoOpLogger;
import org.apache.knox.test.mock.MockInteraction;
import org.apache.knox.test.mock.MockServlet;
import org.easymock.EasyMock;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.servlet.ServletTester;
import org.eclipse.jetty.util.log.Log;
import org.hamcrest.core.Is;
import org.junit.Test;

import javax.security.auth.Subject;
import javax.servlet.DispatcherType;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.util.ArrayDeque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Queue;

import static org.hamcrest.MatcherAssert.assertThat;

public class ServiceRegistryFunctionsTest {
  private ServletTester server;
  private HttpTester.Request request;
  private HttpTester.Response response;
  private Queue<MockInteraction> interactions;
  private MockInteraction interaction;

  private static URL getTestResource( String name ) {
    name = ServiceRegistryFunctionsTest.class.getName().replaceAll( "\\.", "/" ) + "/" + name;
    return ClassLoader.getSystemResource( name );
  }

  private void testSetup(String username, Map<String,String> initParams ) throws Exception {
    ServiceRegistry mockServiceRegistry = EasyMock.createNiceMock( ServiceRegistry.class );
    EasyMock.expect( mockServiceRegistry.lookupServiceURL( "test-cluster", "NAMENODE" ) ).andReturn( "test-nn-scheme://test-nn-host:411" ).anyTimes();
    EasyMock.expect( mockServiceRegistry.lookupServiceURL( "test-cluster", "JOBTRACKER" ) ).andReturn( "test-jt-scheme://test-jt-host:511" ).anyTimes();

    GatewayServices mockGatewayServices = EasyMock.createNiceMock( GatewayServices.class );
    EasyMock.expect( mockGatewayServices.getService(ServiceType.SERVICE_REGISTRY_SERVICE) ).andReturn( mockServiceRegistry ).anyTimes();

    EasyMock.replay( mockServiceRegistry, mockGatewayServices );

    String descriptorUrl = getTestResource( "rewrite.xml" ).toExternalForm();

    Log.setLog( new NoOpLogger() );

    server = new ServletTester();
    server.setContextPath( "/" );
    server.getContext().addEventListener( new UrlRewriteServletContextListener() );
    server.getContext().setInitParameter(
        UrlRewriteServletContextListener.DESCRIPTOR_LOCATION_INIT_PARAM_NAME, descriptorUrl );
    server.getContext().setAttribute( GatewayServices.GATEWAY_CLUSTER_ATTRIBUTE, "test-cluster" );
    server.getContext().setAttribute( GatewayServices.GATEWAY_SERVICES_ATTRIBUTE, mockGatewayServices );

    FilterHolder setupFilter = server.addFilter( SetupFilter.class, "/*", EnumSet.of( DispatcherType.REQUEST ) );
    setupFilter.setFilter( new SetupFilter( username ) );
    FilterHolder rewriteFilter = server.addFilter( UrlRewriteServletFilter.class, "/*", EnumSet.of( DispatcherType.REQUEST ) );
    if( initParams != null ) {
      for( Map.Entry<String,String> entry : initParams.entrySet() ) {
        rewriteFilter.setInitParameter( entry.getKey(), entry.getValue() );
      }
    }
    rewriteFilter.setFilter( new UrlRewriteServletFilter() );

    interactions = new ArrayDeque<>();

    ServletHolder servlet = server.addServlet( MockServlet.class, "/" );
    servlet.setServlet( new MockServlet( "mock-servlet", interactions ) );

    server.start();

    interaction = new MockInteraction();
    request = HttpTester.newRequest();
    response = null;
  }

  @Test
  public void testServiceRegistryFunctionsOnXmlRequestBody() throws Exception {
    Map<String,String> initParams = new HashMap<>();
    initParams.put( "request.body", "oozie-conf" );
    testSetup( "test-user", initParams );

    String input = TestUtils.getResourceString( ServiceRegistryFunctionsTest.class, "test-input-body.xml", StandardCharsets.UTF_8 );
    String expect = TestUtils.getResourceString( ServiceRegistryFunctionsTest.class, "test-expect-body.xml", StandardCharsets.UTF_8 );

    // Setup the server side request/response interaction.
    interaction.expect()
        .method( "PUT" )
        .requestUrl( "http://test-host:42/test-path" )
        .contentType( "text/xml" )
        .characterEncoding( StandardCharsets.UTF_8.name() )
        .content( expect, StandardCharsets.UTF_8 );
    interaction.respond()
        .status( 200 );
    interactions.add( interaction );
    request.setMethod( "PUT" );
    request.setURI( "/test-path" );
    //request.setVersion( "HTTP/1.1" );
    request.setHeader( "Host", "test-host:42" );
    request.setHeader( "Content-Type", "text/xml; charset=UTF-8" );
    request.setContent( input );

    response = TestUtils.execute( server, request );

    // Test the results.
    assertThat( response.getStatus(), Is.is( 200 ) );
  }

  @Test
  public void testServiceRegistryFunctionsOnJsonRequestBody() throws Exception {
    Map<String,String> initParams = new HashMap<>();
    initParams.put( "request.body", "oozie-conf" );
    testSetup( "test-user", initParams );

    String input = TestUtils.getResourceString( ServiceRegistryFunctionsTest.class, "test-input-body.json", StandardCharsets.UTF_8 );
    String expect = TestUtils.getResourceString( ServiceRegistryFunctionsTest.class, "test-expect-body.json", StandardCharsets.UTF_8 );

    // Setup the server side request/response interaction.
    interaction.expect()
        .method( "PUT" )
        .requestUrl( "http://test-host:42/test-path" )
        .contentType( "application/json" )
        .characterEncoding( StandardCharsets.UTF_8.name() )
        .content( expect, StandardCharsets.UTF_8 );
    interaction.respond()
        .status( 200 );
    interactions.add( interaction );
    request.setMethod( "PUT" );
    request.setURI( "/test-path" );
    //request.setVersion( "HTTP/1.1" );
    request.setHeader( "Host", "test-host:42" );
    request.setHeader( "Content-Type", "application/json; charset=UTF-8" );
    request.setContent( input );

    response = TestUtils.execute( server, request );

    // Test the results.
    assertThat( response.getStatus(), Is.is( 200 ) );
  }

  private static class SetupFilter implements Filter {
    private Subject subject;

    SetupFilter( String userName ) {
      subject = new Subject();
      subject.getPrincipals().add( new BasicUserPrincipal( userName ) );
    }

    @Override
    public void init( FilterConfig filterConfig ) throws ServletException {
    }

    @Override
    public void doFilter( final ServletRequest request, final ServletResponse response, final FilterChain chain ) throws IOException, ServletException {
      HttpServletRequest httpRequest = ((HttpServletRequest)request);
      StringBuffer sourceUrl = httpRequest.getRequestURL();
      String queryString = httpRequest.getQueryString();
      if( queryString != null ) {
        sourceUrl.append( '?' );
        sourceUrl.append( queryString );
      }
      try {
        request.setAttribute(
            AbstractGatewayFilter.SOURCE_REQUEST_URL_ATTRIBUTE_NAME,
            Parser.parseLiteral( sourceUrl.toString() ) );
      } catch( URISyntaxException e ) {
        throw new ServletException( e );
      }
      try {
        Subject.doAs( subject, new PrivilegedExceptionAction<Void>() {
          @Override
          public Void run() throws Exception {
            chain.doFilter( request, response );
            return null;
          }
        } );
      } catch( PrivilegedActionException e ) {
        throw new ServletException( e );
      }
    }

    @Override
    public void destroy() {
    }
  }
}
