//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.mortbay.jetty.alpnfinder;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import org.apache.commons.lang3.text.StrSubstitutor;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpProxy;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.ssl.SslContextFactory;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 *
 */
public class AlpnBootFinder
    implements Closeable
{

    private static final Logger LOGGER = Log.getLogger( AlpnBootFinder.class );

    private HttpClient httpClient;

    public static void main( String[] args )
        throws Exception
    {
        Request request = new Request();
        try
        {
            JCommander jCommander = new JCommander( request, args );
            if ( request.isHelp() )
            {
                jCommander.usage();
                System.exit( 0 );
            }
        }
        catch ( Exception e )
        {
            new JCommander( request ).usage();
            System.exit( 0 );
        }

        try (AlpnBootFinder alpnBootFinder = new AlpnBootFinder().initialize( request ))
        {
            String alpnVersion = alpnBootFinder.findAlpnVersion( request );
            alpnBootFinder.download( request, alpnVersion );
        }
        catch ( IllegalArgumentException e )
        {
            LOGGER.info( e.getMessage() );
            new JCommander( request ).usage();
        }
    }

    public AlpnBootFinder initialize( Request request )
        throws Exception
    {
        // well we have an ezy ssl trust?
        httpClient = new HttpClient( new SslContextFactory( true ) );
        if ( request.getProxyHost() != null )
        {
            httpClient.getProxyConfiguration() //
                .getProxies() //
                .add( new HttpProxy( request.getProxyHost(), request.getProxyPort() ) );
        }
        httpClient.start();
        return this;
    }

    public void close()
        throws IOException
    {
        try
        {
            httpClient.stop();
        }
        catch ( Exception e )
        {
            throw new IOException( e.getMessage(), e );
        }
    }

    public String findAlpnVersion( Request request )
        throws Exception
    {
        String url = request.propertiesUrl;
        ContentResponse contentResponse = httpClient.newRequest( url ).send();
        int status = contentResponse.getStatus();
        if ( status != 200 )
        {
            throw new RuntimeException( "not 200 but " + status + " when trying to GET mod file from url:" + url );
        }

        Properties properties = new Properties();
        try (StringReader reader = new StringReader( contentResponse.getContentAsString() ))
        {
            properties.load( reader );
        }
        return properties.getProperty( request.javaVersion );
    }

    public void download( Request request, String alpnVersion )
        throws Exception
    {

        // base http://repo.maven.apache.org/maven2
        String path = "/org/mortbay/jetty/alpn/alpn-boot/${alpnVersion}/alpn-boot-${alpnVersion}.jar";
        path = StrSubstitutor.replace( path, subStrMap( alpnVersion ) );
        Path targetFile = Paths.get( request.destinationFile );
        LOGGER.debug( "targetFile {}", targetFile );
        if ( Files.isDirectory( targetFile ) )
        {
            throw new IllegalArgumentException( "Target file must be a file and not a directory: " + targetFile );
        }
        Files.deleteIfExists( targetFile );
        if ( targetFile.toFile().getParentFile() != null )
        {
            if ( !targetFile.toFile().getParentFile().mkdirs() )
            {
                throw new IllegalArgumentException( "Cannot create directories for target file: " + targetFile );
            }
        }
        if ( !targetFile.toFile().createNewFile() )
        {
            throw new IllegalArgumentException( "Cannot create target file: " + targetFile );
        }
        try (OutputStream outputStream = Files.newOutputStream( targetFile ))
        {
            String url = request.mavenRepo + path;
            ContentResponse contentResponse = httpClient.newRequest( url ).send();
            int status = contentResponse.getStatus();
            if ( status != 200 )
            {
                throw new RuntimeException(
                    "not 200 but " + status + " when trying to GET alpn boot jar from url:" + url );
            }
            outputStream.write( contentResponse.getContent() );
        }
        LOGGER.info( "all done alpnboot {} jar downloaded as {}! Enjoy HTTP/2", alpnVersion, targetFile );
    }

    private Map<String, String> subStrMap( String alpnVersion )
    {
        Map<String, String> map = new HashMap<>( 1 );
        map.put( "alpnVersion", alpnVersion );
        return map;
    }

    public static class Request
    {
        @Parameter( names = { "-df", "--destination-file" }, //
            description = "Destination file to download the ALPN Boot jar (Default: alpn-boot.jar)" )
        String destinationFile = "alpn-boot.jar";

        @Parameter( names = { "-ph", "--proxy-host" }, //
            description = "Proxy host to use if any" )
        String proxyHost;

        @Parameter( names = { "-pp", "--proxy-port" }, //
            description = "Proxy port to use if any" )
        int proxyPort;

        @Parameter( names = { "-mp", "--maven-repository" }, //
            description = "Maven repository to use (Default: https://repo.maven.apache.org/maven2)" )
        String mavenRepo = "https://repo.maven.apache.org/maven2";

        @Parameter( names = { "-jv", "--java-version" }, //
            description = "Java version (Default: current one)" )
        String javaVersion = System.getProperty( "java.version" );

        String propertiesUrl = "https://jetty-project.github.io/jetty-alpn/version_mapping.properties";

        @Parameter( names = { "-h", "--help" }, description = "Display help", help = true )
        private boolean help;

        public String getDestinationFile()
        {
            return destinationFile;
        }

        public void setDestinationFile( String destinationFile )
        {
            this.destinationFile( destinationFile );
        }

        public Request destinationFile( String destinationFile )
        {
            this.destinationFile = destinationFile;
            return this;
        }

        public String getProxyHost()
        {
            return proxyHost;
        }

        public void setProxyHost( String proxyHost )
        {
            this.proxyHost( proxyHost );
        }

        public Request proxyHost( String proxyHost )
        {
            this.proxyHost = proxyHost;
            return this;
        }

        public int getProxyPort()
        {
            return proxyPort;
        }

        public void setProxyPort( int proxyPort )
        {
            this.proxyPort( proxyPort );
        }

        public Request proxyPort( int proxyPort )
        {
            this.proxyPort = proxyPort;
            return this;
        }

        public String getMavenRepo()
        {
            return mavenRepo;
        }

        public void setMavenRepo( String mavenRepo )
        {
            this.mavenRepo( mavenRepo );
        }

        public Request mavenRepo( String mavenRepo )
        {
            this.mavenRepo = mavenRepo;
            return this;
        }

        public String getJavaVersion()
        {
            return javaVersion;
        }

        public void setJavaVersion( String javaVersion )
        {
            this.javaVersion = javaVersion;
        }

        public boolean isHelp()
        {
            return help;
        }

        public void setHelp( boolean help )
        {
            this.help = help;
        }


    }
}
