package org.umlg.sqlg.test;

import org.apache.commons.configuration.Configuration;
import org.apache.commons.configuration.PropertiesConfiguration;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.umlg.sqlg.SqlgPlugin;
import org.umlg.sqlg.structure.SqlgGraph;
import org.umlg.sqlg.structure.ds.C3p0DataSourceFactory;

import javax.naming.Context;
import javax.naming.spi.InitialContextFactory;
import javax.naming.spi.NamingManager;
import javax.sql.DataSource;
import java.net.URL;
import java.util.ServiceLoader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * @author Lukas Krejci
 */
public class TestJNDIInitialization {

    private static final Logger logger = LoggerFactory.getLogger(TestJNDIInitialization.class);

    private static Configuration configuration;
    private static DataSource ds;

    @Rule
    public TestRule watcher = new TestWatcher() {
        protected void starting(Description description) {
            logger.info("Starting test: " + description.getClassName() + "." + description.getMethodName());
        }

        protected void finished(Description description) {
            logger.info("Finished test: " + description.getClassName() + "." + description.getMethodName());
        }
    };

    @BeforeClass
    public static void beforeClass() throws Exception {
        URL sqlProperties = Thread.currentThread().getContextClassLoader().getResource("sqlg.properties");
        configuration = new PropertiesConfiguration(sqlProperties);
        if (!configuration.containsKey("jdbc.url")) {
            throw new IllegalArgumentException(String.format("SqlGraph configuration requires that the %s be set", "jdbc.url"));
        }

        String url = configuration.getString("jdbc.url");
        //obtain the connection that we will later supply from JNDI
        SqlgPlugin p = findSqlgPlugin(url);
        Assert.assertNotNull(p);
        ds = new C3p0DataSourceFactory().setup(p.getDriverFor(url), configuration).getDatasource();
        //change the connection url to be a JNDI one
        configuration.setProperty("jdbc.url", "jndi:testConnection");

        //set up the initial context
        NamingManager.setInitialContextFactoryBuilder(environment -> {
            InitialContextFactory mockFactory = mock(InitialContextFactory.class);
            Context mockContext = mock(Context.class);
            when(mockFactory.getInitialContext(any())).thenReturn(mockContext);

            when(mockContext.lookup("testConnection")).thenReturn(ds);

            return mockFactory;
        });
    }

    @Test
    public void testLoadingDatasourceFromJndi() throws Exception {
        SqlgGraph g = SqlgGraph.open(configuration);
        assertNotNull(g.getSqlDialect());
        assertEquals(configuration.getString("jdbc.url"), g.getJdbcUrl());
        assertNotNull(g.getConnection());
    }

    private static SqlgPlugin findSqlgPlugin(String connectionUri) {
        for (SqlgPlugin p : ServiceLoader.load(SqlgPlugin.class, TestJNDIInitialization.class.getClassLoader())) {
            if (p.getDriverFor(connectionUri) != null) {
                return p;
            }
        }
        return null;
    }
}
