package io.quarkus.devservices.db2.deployment;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import org.jboss.logging.Logger;
import org.testcontainers.containers.Db2Container;
import org.testcontainers.utility.DockerImageName;

import io.quarkus.datasource.common.runtime.DatabaseKind;
import io.quarkus.datasource.deployment.spi.DevServicesDatasourceProvider;
import io.quarkus.datasource.deployment.spi.DevServicesDatasourceProviderBuildItem;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.DevServicesSharedNetworkBuildItem;
import io.quarkus.devservices.common.ConfigureUtil;
import io.quarkus.devservices.common.ContainerShutdownCloseable;
import io.quarkus.runtime.LaunchMode;

public class DB2DevServicesProcessor {

    private static final Logger LOG = Logger.getLogger(DB2DevServicesProcessor.class);

    @BuildStep
    DevServicesDatasourceProviderBuildItem setupDB2(
            List<DevServicesSharedNetworkBuildItem> devServicesSharedNetworkBuildItem) {
        return new DevServicesDatasourceProviderBuildItem(DatabaseKind.DB2, new DevServicesDatasourceProvider() {
            @Override
            public RunningDevServicesDatasource startDatabase(Optional<String> username, Optional<String> password,
                    Optional<String> datasourceName, Optional<String> imageName,
                    Map<String, String> containerProperties, Map<String, String> additionalJdbcUrlProperties,
                    OptionalInt fixedExposedPort, LaunchMode launchMode, Optional<Duration> startupTimeout) {
                QuarkusDb2Container container = new QuarkusDb2Container(imageName, fixedExposedPort,
                        !devServicesSharedNetworkBuildItem.isEmpty());
                startupTimeout.ifPresent(container::withStartupTimeout);
                container.withPassword(password.orElse("quarkus"))
                        .withUsername(username.orElse("quarkus"))
                        .withDatabaseName(datasourceName.orElse("default"))
                        .withReuse(true);
                additionalJdbcUrlProperties.forEach(container::withUrlParam);
                container.start();

                LOG.info("Dev Services for IBM Db2 started.");

                return new RunningDevServicesDatasource(container.getContainerId(),
                        container.getEffectiveJdbcUrl(),
                        container.getUsername(),
                        container.getPassword(),
                        new ContainerShutdownCloseable(container, "IBM Db2"));
            }
        });
    }

    private static class QuarkusDb2Container extends Db2Container {
        private final OptionalInt fixedExposedPort;
        private final boolean useSharedNetwork;

        private String hostName = null;

        public QuarkusDb2Container(Optional<String> imageName, OptionalInt fixedExposedPort, boolean useSharedNetwork) {
            super(DockerImageName.parse(imageName.orElseGet(() -> ConfigureUtil.getDefaultImageNameFor("db2")))
                    .asCompatibleSubstituteFor(DockerImageName.parse("ibmcom/db2")));
            this.fixedExposedPort = fixedExposedPort;
            this.useSharedNetwork = useSharedNetwork;
        }

        @Override
        protected void configure() {
            super.configure();

            if (useSharedNetwork) {
                hostName = ConfigureUtil.configureSharedNetwork(this, "db2");
                return;
            }

            if (fixedExposedPort.isPresent()) {
                addFixedExposedPort(fixedExposedPort.getAsInt(), DB2_PORT);
            } else {
                addExposedPorts(DB2_PORT);
            }
        }

        // this is meant to be called by Quarkus code and is not strictly needed
        // in the DB2 case as testcontainers does not try to establish
        // a connection to determine if the container is ready, but we do it anyway to be consistent across
        // DB containers
        public String getEffectiveJdbcUrl() {
            if (useSharedNetwork) {
                // in this case we expose the URL using the network alias we created in 'configure'
                // and the container port since the application communicating with this container
                // won't be doing port mapping
                String additionalUrlParams = constructUrlParameters(":", ";", ";");
                return "jdbc:db2://" + hostName + ":" + DB2_PORT + "/" + getDatabaseName() + additionalUrlParams;
            } else {
                return super.getJdbcUrl();
            }
        }
    }
}
