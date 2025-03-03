package io.quarkus.devservices.mariadb.deployment;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import org.jboss.logging.Logger;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.utility.DockerImageName;

import io.quarkus.datasource.common.runtime.DatabaseKind;
import io.quarkus.datasource.deployment.spi.DevServicesDatasourceProvider;
import io.quarkus.datasource.deployment.spi.DevServicesDatasourceProviderBuildItem;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.DevServicesSharedNetworkBuildItem;
import io.quarkus.devservices.common.ConfigureUtil;
import io.quarkus.devservices.common.ContainerShutdownCloseable;
import io.quarkus.runtime.LaunchMode;

public class MariaDBDevServicesProcessor {

    private static final Logger LOG = Logger.getLogger(MariaDBDevServicesProcessor.class);

    public static final Integer PORT = 3306;
    public static final String MY_CNF_CONFIG_OVERRIDE_PARAM_NAME = "TC_MY_CNF";

    @BuildStep
    DevServicesDatasourceProviderBuildItem setupMariaDB(
            List<DevServicesSharedNetworkBuildItem> devServicesSharedNetworkBuildItem) {
        return new DevServicesDatasourceProviderBuildItem(DatabaseKind.MARIADB, new DevServicesDatasourceProvider() {
            @Override
            public RunningDevServicesDatasource startDatabase(Optional<String> username, Optional<String> password,
                    Optional<String> datasourceName, Optional<String> imageName,
                    Map<String, String> containerProperties, Map<String, String> additionalJdbcUrlProperties,
                    OptionalInt fixedExposedPort, LaunchMode launchMode, Optional<Duration> startupTimeout) {
                QuarkusMariaDBContainer container = new QuarkusMariaDBContainer(imageName, fixedExposedPort,
                        !devServicesSharedNetworkBuildItem.isEmpty());
                startupTimeout.ifPresent(container::withStartupTimeout);
                container.withPassword(password.orElse("quarkus"))
                        .withUsername(username.orElse("quarkus"))
                        .withDatabaseName(datasourceName.orElse("default"))
                        .withReuse(true);

                if (containerProperties.containsKey(MY_CNF_CONFIG_OVERRIDE_PARAM_NAME)) {
                    container.withConfigurationOverride(containerProperties.get(MY_CNF_CONFIG_OVERRIDE_PARAM_NAME));
                }

                additionalJdbcUrlProperties.forEach(container::withUrlParam);
                container.start();

                LOG.info("Dev Services for MariaDB started.");

                return new RunningDevServicesDatasource(container.getContainerId(),
                        container.getEffectiveJdbcUrl(),
                        container.getUsername(),
                        container.getPassword(),
                        new ContainerShutdownCloseable(container, "MariaDB"));
            }
        });
    }

    private static class QuarkusMariaDBContainer extends MariaDBContainer {
        private final OptionalInt fixedExposedPort;
        private final boolean useSharedNetwork;

        private String hostName = null;

        public QuarkusMariaDBContainer(Optional<String> imageName, OptionalInt fixedExposedPort, boolean useSharedNetwork) {
            super(DockerImageName
                    .parse(imageName.orElseGet(() -> ConfigureUtil.getDefaultImageNameFor("mariadb")))
                    .asCompatibleSubstituteFor(DockerImageName.parse(MariaDBContainer.NAME)));
            this.fixedExposedPort = fixedExposedPort;
            this.useSharedNetwork = useSharedNetwork;
        }

        @Override
        protected void configure() {
            super.configure();

            if (useSharedNetwork) {
                hostName = ConfigureUtil.configureSharedNetwork(this, "mariadb");
                return;
            }

            if (fixedExposedPort.isPresent()) {
                addFixedExposedPort(fixedExposedPort.getAsInt(), PORT);
            } else {
                addExposedPort(PORT);
            }
        }

        // this is meant to be called by Quarkus code and is needed in order to not disrupt testcontainers
        // from being able to determine the status of the container (which it does by trying to acquire a connection)
        public String getEffectiveJdbcUrl() {
            if (useSharedNetwork) {
                String additionalUrlParams = constructUrlParameters("?", "&");
                return "jdbc:mariadb://" + hostName + ":" + PORT + "/" + getDatabaseName() + additionalUrlParams;
            } else {
                return super.getJdbcUrl();
            }
        }
    }
}
