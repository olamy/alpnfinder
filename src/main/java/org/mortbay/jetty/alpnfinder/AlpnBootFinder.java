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
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.text.StrSubstitutor;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpProxy;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.start.BaseHome;
import org.eclipse.jetty.start.Module;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.ssl.SslContextFactory;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
        // url format to get raw mod file
        // https://github.com/eclipse/jetty.project/raw/jetty-9.4.x/jetty-alpn/jetty-alpn-server/src/main/config/modules/alpn-impl/alpn
        // -1.8.0_05.mod
        String url = request.modulesUrl + "-" + request.getJavaVersion() + ".mod";
        Path tmpModules = Files.createTempDirectory( "modules" );
        LOGGER.info( "creating directory: {}", tmpModules );
        // because Module class really need a directory called modules...
        File modules = new File( tmpModules.toFile(), "modules" );
        modules.mkdirs();
        Path modFile = Paths.get( modules.getPath(), "alpn.mod" );

        try (OutputStream outputStream = Files.newOutputStream( modFile ))
        {
            ContentResponse contentResponse = httpClient.newRequest( url ).send();
            int status = contentResponse.getStatus();
            if ( status != 200 )
            {
                throw new RuntimeException( "not 200 but " + status + " when trying to GET mod file from url:" + url );
            }
            outputStream.write( contentResponse.getContent() );
        }

        Module module = new Module( new BaseHome(), modFile );

//      [files]
//      maven://org.mortbay.jetty.alpn/alpn-boot/8.1.0.v20141016|lib/alpn/alpn-boot-8.1.0.v20141016.jar
//      or
//      http://central.maven.org/maven2/org/mortbay/jetty/alpn/alpn-boot/8.1.4.v20150727/alpn-boot-8.1.4.v20150727.jar|lib/alpn/alpn-boot-8.1.4.v20150727.jar
        List<String> files = module.getFiles();
        // we suppose it is the first one
        String first = files.get( 0 );

        String version =
            StringUtils.substringAfterLast( StringUtils.substringBeforeLast( first, ".jar" ), "alpn-boot-" );

        LOGGER.info( "found version {} from {}", version, first );
        return version;
    }

    public void download( Request request, String alpnVersion )
        throws Exception
    {

        // base http://repo.maven.apache.org/maven2
        String path = "/org/mortbay/jetty/alpn/alpn-boot/${alpnVersion}/alpn-boot-${alpnVersion}.jar";
        path = StrSubstitutor.replace( path, subStrMap( alpnVersion ) );
        Path targetFile = Paths.get( request.destinationFile );
        if ( Files.isDirectory( targetFile ) )
        {
            throw new IllegalArgumentException( "Target file must be a file and not a directory" );
        }
        Files.deleteIfExists( targetFile );
        if ( !targetFile.toFile().mkdirs() )
        {
            throw new IllegalArgumentException( "Cannot create directories for target file: " + targetFile );
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
        LOGGER.info( "all done alpnboot jar downloaded as {}! Enjoy HTTP/2", targetFile );
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
        public String destinationFile = "alpn-boot.jar";

        @Parameter( names = { "-ph", "--proxy-host" }, //
            description = "Proxy host to use if any" )
        public String proxyHost;

        @Parameter( names = { "-pp", "--proxy-port" }, //
            description = "Proxy port to use if any" )
        public int proxyPort;

        @Parameter( names = { "-mp", "--maven-repository" }, //
            description = "Maven repository to use (Default: https://repo.maven.apache.org/maven2)" )
        public String mavenRepo = "https://repo.maven.apache.org/maven2";

        @Parameter( names = { "-jv", "--java-version" }, //
            description = "Java version (Default: current one)" )
        public String javaVersion = System.getProperty( "java.version" );

        @Parameter( names = { "-mu", "--modules-url" }, //
            description = "Modules url to use (Default: https://github.com/eclipse/jetty.project/raw/jetty-9.4.x/jetty-alpn/jetty-alpn-server/src/main/config/modules/alpn-impl/alpn)" )
        // -1.8.0_05.mod
        public String modulesUrl =
            "https://github.com/eclipse/jetty.project/raw/jetty-9.4.x/jetty-alpn/jetty-alpn-server/src/main/config/modules/alpn-impl/alpn";

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

        public String getModulesUrl()
        {
            return modulesUrl;
        }

        public void setModulesUrl( String modulesUrl )
        {
            this.modulesUrl = modulesUrl;
        }
    }
}
