package com.adviser.osgi.hibernate;

import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;

import org.apache.felix.ipojo.annotations.Component;
import org.apache.felix.ipojo.annotations.Instantiate;
import org.apache.felix.ipojo.annotations.Invalidate;
import org.apache.felix.ipojo.annotations.Provides;
import org.apache.felix.ipojo.annotations.Validate;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.boot.registry.BootstrapServiceRegistryBuilder;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.boot.registry.selector.StrategyRegistrationProvider;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.cfg.Configuration;
import org.hibernate.engine.jdbc.connections.spi.ConnectionProvider;
import org.hibernate.engine.jdbc.dialect.spi.DialectFactory;
import org.hibernate.engine.spi.NamedSQLQueryDefinition;
import org.hibernate.integrator.spi.Integrator;
import org.hibernate.internal.util.ClassLoaderHelper;
import org.hibernate.jpa.HibernatePersistenceProvider;
import org.hibernate.metamodel.Metadata;
import org.hibernate.metamodel.MetadataSources;
import org.hibernate.metamodel.spi.TypeContributor;
import org.hibernate.osgi.OsgiJtaPlatform;
import org.hibernate.osgi.OsgiServiceUtil;
import org.hibernate.osgi.OsgiSessionFactoryService;
import org.hibernate.service.ServiceRegistry;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceRegistration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component(immediate = true)
@Instantiate
@Provides(specifications = { HibernateSessionFactoryCreator.class })
public class HibernateSessionFactoryService extends OsgiSessionFactoryService implements HibernateSessionFactoryCreator {

    private static final Logger LOG = LoggerFactory.getLogger(HibernateSessionFactoryService.class);

    private final OsgiClassLoader osgiClassLoader;
    private final OsgiJtaPlatform osgiJtaPlatform;
    private final BundleContext context;
    private final OsgiServiceUtil osgiServiceUtil;

    public HibernateSessionFactoryService(final BundleContext context) throws Exception {
        super(null, null, null);
        LOG.error("HALLO MARK");
        // build a ClassLoader that uses all the necessary OSGi bundles, and place it into
        // a well-known location so internals can access it
        this.osgiClassLoader = new OsgiClassLoader();
        this.osgiClassLoader.addBundle(FrameworkUtil.getBundle(Session.class));
        this.osgiClassLoader.addBundle(FrameworkUtil.getBundle(HibernatePersistenceProvider.class));

        this.osgiServiceUtil = new OsgiServiceUtil(context);
        this.osgiJtaPlatform = new OsgiJtaPlatform(this.osgiServiceUtil);
        this.context = context;
    }

    @Validate
    public void start() {
        LOG.info(getClass().getName() + "::Start");
        ClassLoaderHelper.overridenClassLoader = this.osgiClassLoader;
    }

    @Invalidate
    public void stop() {
        ClassLoaderHelper.overridenClassLoader = null;
        LOG.info(getClass().getName() + "::Stop");
    }

    /** {@inheritDoc} */
    public SessionFactory create(final Bundle requestingBundle, final String hibernateCfg,
            final ConnectionProvider connectionProvider, final DialectFactory dialectFactory,
            final Map<String, NamedSQLQueryDefinition> namedSQLQueries, final Properties configurationProperties) {

        this.osgiClassLoader.addBundle(requestingBundle);

        final Configuration configuration = new Configuration();
        configuration.getProperties().put(AvailableSettings.JTA_PLATFORM, this.osgiJtaPlatform);
        // allow to additional custom properties for Session creation
        if (configurationProperties != null) {
            for (Entry<Object, Object> e : configurationProperties.entrySet()){
                configuration.getProperties().put(e.getKey(), e.getValue());
            }
        }
        configuration.configure(hibernateCfg);

        final BootstrapServiceRegistryBuilder builder = new BootstrapServiceRegistryBuilder();
        builder.with(this.osgiClassLoader);
        // Are the following 2 really needed?
        builder.with(org.hibernate.cache.ehcache.EhCacheRegionFactory.class.getClassLoader());
        builder.with(net.sf.ehcache.statistics.sampled.SampledCacheStatistics.class.getClassLoader());
        builder.with(javassist.util.proxy.ProxyObject.class.getClassLoader());
        builder.with(org.hibernate.proxy.HibernateProxy.class.getClassLoader());

        final Integrator[] integrators = this.osgiServiceUtil.getServiceImpls(Integrator.class);
        for (final Integrator integrator : integrators) {
            builder.with(integrator);
        }

        final StrategyRegistrationProvider[] strategyRegistrationProviders = this.osgiServiceUtil.getServiceImpls(
                StrategyRegistrationProvider.class);
        for (final StrategyRegistrationProvider strategyRegistrationProvider : strategyRegistrationProviders) {
            builder.withStrategySelectors(strategyRegistrationProvider);
        }

        final TypeContributor[] typeContributors = this.osgiServiceUtil.getServiceImpls(TypeContributor.class);
        for (final TypeContributor typeContributor : typeContributors) {
            configuration.registerTypeContributor(typeContributor);
        }

        final StandardServiceRegistryBuilder srb = new StandardServiceRegistryBuilder(builder.build());
        srb.addService(ConnectionProvider.class, connectionProvider);
        srb.addService(DialectFactory.class, dialectFactory);
        final ServiceRegistry serviceRegistry = srb.applySettings(configuration.getProperties()).build();

        if (namedSQLQueries != null) {
            // get the namedQueries from Hibernate and store them in the provided map
            final MetadataSources metadataSources = new MetadataSources(serviceRegistry);
            metadataSources.addResource(hibernateCfg);
            final Metadata metadata = metadataSources.buildMetadata();
            for (final NamedSQLQueryDefinition definition : metadata.getNamedNativeQueryDefinitions()) {
                namedSQLQueries.put(definition.getName(), definition);
            }
        }
        return configuration.buildSessionFactory(serviceRegistry);
    }

    public void unregisterBundle(final Bundle requestingBundle) {
        this.osgiClassLoader.removeBundle(requestingBundle);
    }

    @SuppressWarnings("rawtypes")
    @Override
    public Object getService(final Bundle requestingBundle, final ServiceRegistration registration) {
        return null;
    }

    @SuppressWarnings("rawtypes")
    @Override
    public void ungetService(final Bundle requestingBundle, final ServiceRegistration registration, final Object service) {
        // do nothing
    }

}
