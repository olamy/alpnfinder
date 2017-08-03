package org.mortbay.jetty.alpnfinder;

import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Unit test for simple AlpnBootFinder.
 */
public class AlpnBootFinderTest
{

    private Server server;

    private ServerConnector connector;

    Path tempFile;

    @Before
    public void startServer()
        throws Exception
    {
        // create properties mapping file for testing
        tempFile = Files.createTempFile( "mapping", "properties" );
        Properties properties = new Properties();
        properties.put( System.getProperty( "java.version" ), "1.0.0" );
        try (Writer writer = Files.newBufferedWriter( tempFile ))
        {
            properties.store( writer, "temporary Jetty test file" );
        }

        server = new Server();
        connector = new ServerConnector( server, new HttpConnectionFactory( new HttpConfiguration() ) );
        server.addConnector( connector );
        ServletContextHandler servletContextHandler = new ServletContextHandler();
        server.setHandler( servletContextHandler );

        servletContextHandler.addServlet( new ServletHolder( new TestServlet( tempFile ) ), "/*" );
        server.start();
    }

    @After
    public void stopJetty()
        throws Exception
    {
        if ( server != null )
        {
            server.stop();
        }
    }

    @Test
    public void download_mapping_properties()
        throws Exception
    {
        AlpnBootFinder.Request request = new AlpnBootFinder.Request();
        request.propertiesUrl = "http://localhost:" + connector.getLocalPort() + "/properties.file";
        try (AlpnBootFinder alpnBootFinder = new AlpnBootFinder().initialize( request ))
        {
            String version = alpnBootFinder.findAlpnVersion( request );
            Assert.assertEquals( "1.0.0", version );
        }
    }

    public class TestServlet
        extends HttpServlet
    {
        private Path propertiesFilePath;

        public TestServlet( Path propertiesFilePath )
        {
            this.propertiesFilePath = propertiesFilePath;
        }

        @Override
        protected void doGet( HttpServletRequest req, HttpServletResponse resp )
            throws ServletException, IOException
        {
            if ( req.getRequestURI().endsWith( "properties.file" ) )
            {
                resp.getOutputStream().write( Files.readAllBytes( propertiesFilePath ) );
            }
        }
    }

}
