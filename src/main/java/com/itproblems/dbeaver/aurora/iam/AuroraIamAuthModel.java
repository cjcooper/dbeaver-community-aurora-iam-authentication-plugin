package com.itproblems.dbeaver.aurora.iam;

import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.DBConstants;
import org.jkiss.dbeaver.model.DBPDataSource;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.connection.DBPConnectionConfiguration;
import org.jkiss.dbeaver.model.exec.DBCException;
import org.jkiss.dbeaver.model.impl.auth.AuthModelDatabaseNative;
import org.jkiss.dbeaver.model.impl.auth.AuthModelDatabaseNativeCredentials;
import org.jkiss.dbeaver.model.net.DBWUtils;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.utils.CommonUtils;

import java.util.Properties;

/**
 * Username comes from the connection; the password is replaced on every connect with a fresh
 * 15-minute RDS IAM token signed with the configured AWS profile.
 */
public class AuroraIamAuthModel extends AuthModelDatabaseNative<AuthModelDatabaseNativeCredentials> {

    private static final Log log = Log.getLog(AuroraIamAuthModel.class);

    @Override
    public Object initAuthentication(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBPDataSource dataSource,
        @NotNull AuthModelDatabaseNativeCredentials credentials,
        @NotNull DBPConnectionConfiguration configuration,
        @NotNull Properties connectProps
    ) throws DBException {
        DBPDataSourceContainer container = dataSource.getContainer();
        // Use the original configuration: with an SSH tunnel the actual one points at localhost,
        // but the token must be signed for the real RDS endpoint.
        DBPConnectionConfiguration original = container.getConnectionConfiguration();
        DBWUtils.ConnectivityParameters cnnParams = DBWUtils.getConnectivityParameters(original, container.getDriver());

        String host = CommonUtils.isEmpty(original.getAuthProperty(AuroraIamConstants.PROP_TOKEN_HOST))
            ? cnnParams.hostName()
            : original.getAuthProperty(AuroraIamConstants.PROP_TOKEN_HOST).trim();
        String portString = CommonUtils.isEmpty(cnnParams.hostPort())
            ? container.getDriver().getDefaultPort()
            : cnnParams.hostPort();
        String user = credentials.getUserName();
        String profile = original.getAuthProperty(AuroraIamConstants.PROP_PROFILE);
        String region = original.getAuthProperty(AuroraIamConstants.PROP_REGION);

        if (CommonUtils.isEmpty(host)) {
            throw new DBCException("AWS IAM auth: connection has no host");
        }
        if (CommonUtils.isEmpty(user)) {
            throw new DBCException("AWS IAM auth: database user name is required");
        }
        int port = CommonUtils.toInt(portString, -1);
        if (port <= 0) {
            throw new DBCException("AWS IAM auth: invalid port '" + portString + "'");
        }

        monitor.subTask("Generate AWS IAM auth token");
        String token;
        try {
            token = AuroraIamTokenGenerator.generateToken(host, port, user, profile, region);
        } catch (Exception e) {
            throw new DBCException("Failed to generate AWS IAM auth token for " + user + "@" + host + ":" + port
                + " (profile '" + CommonUtils.notEmpty(profile) + "'): " + e.getMessage(), e);
        }
        log.debug("Generated AWS IAM auth token for " + user + "@" + host + ":" + port);

        super.initAuthentication(monitor, dataSource, credentials, configuration, connectProps);
        connectProps.put(DBConstants.DATA_SOURCE_PROPERTY_PASSWORD, token);
        requireSsl(container.getDriver().getDriverClassName(), connectProps);
        return credentials;
    }

    @Override
    public boolean isUserPasswordApplicable() {
        return false;
    }

    @Override
    protected boolean isUserPasswordNeeded(@NotNull DBPDataSourceContainer dataSourceContainer) {
        return false;
    }

    /**
     * IAM tokens are only accepted over TLS. Upgrade weaker settings to "encrypt without verifying";
     * configure the SSL tab with the RDS CA bundle for full verification.
     */
    static void requireSsl(String driverClass, Properties props) {
        if (driverClass == null) {
            return;
        }
        switch (driverClass) {
            case "org.postgresql.Driver" -> {
                String mode = props.getProperty("sslmode");
                if (mode == null || mode.equals("disable") || mode.equals("allow") || mode.equals("prefer")) {
                    props.setProperty("sslmode", "require");
                }
            }
            case "com.mysql.cj.jdbc.Driver" -> {
                String mode = props.getProperty("sslMode");
                if (mode == null || mode.equalsIgnoreCase("DISABLED") || mode.equalsIgnoreCase("PREFERRED")) {
                    props.setProperty("sslMode", "REQUIRED");
                }
            }
            case "org.mariadb.jdbc.Driver" -> {
                String mode = props.getProperty("sslMode");
                if (mode == null || mode.equalsIgnoreCase("disable")) {
                    props.setProperty("sslMode", "trust");
                }
            }
            default -> log.debug("AWS IAM auth: unknown driver " + driverClass + ", leaving SSL settings unchanged");
        }
    }
}
