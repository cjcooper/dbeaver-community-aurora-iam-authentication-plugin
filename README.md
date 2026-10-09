# DBeaver Aurora IAM Authentication

A DBeaver Community Edition plugin that adds an **AWS IAM (Aurora / RDS)** authentication method to
PostgreSQL and MySQL/MariaDB connections. On every connect it signs a fresh 15-minute
[RDS IAM auth token](https://docs.aws.amazon.com/AmazonRDS/latest/AuroraUserGuide/UsingWithRDS.IAMDBAuth.html)
with your AWS credentials and uses it as the password. You never store or paste tokens.

Built against DBeaver CE 26.2.2 (Java 21+).

## Build and install

```sh
./gradlew build            # -> build/libs/com.itproblems.dbeaver.aurora.iam-1.0.0.jar
./gradlew installPlugin    # copies the jar into DBeaver's dropins/ folder
```

Restart DBeaver after installing. The build compiles against the jars of your local DBeaver install
(default `/Applications/DBeaver.app/Contents/Eclipse`). Point it elsewhere with
`-PdbeaverHome=/path/to/dbeaver` (the directory that contains `plugins/`).

If DBeaver doesn't pick the plugin up, start it once with `-clean` (add it to the top of `dbeaver.ini`, then remove it).

## Configure a connection

1. Create or edit a PostgreSQL or MySQL connection with the Aurora endpoint as **Host**.
2. In **Authentication**, choose **AWS IAM (Aurora / RDS)**.
3. Fill in:
   - **Username**: the database user that has IAM auth enabled (see below).
   - **AWS profile**: a profile from `~/.aws/config`. Leave it empty to use the default credentials chain.
     SSO profiles work; run `aws sso login --profile <name>` when the session expires, then reconnect.
   - **AWS region**: usually leave it empty. It is read from the `*.<region>.rds.amazonaws.com` hostname,
     then from the profile.
   - **Token hostname**: only needed if **Host** is a CNAME or local proxy rather than the RDS endpoint.
     SSH tunnels are handled automatically, because the token is signed for the original Host.

The plugin forces TLS, because IAM tokens are only accepted over SSL. It upgrades weaker settings to
`sslmode=require` (PostgreSQL), `sslMode=REQUIRED` (MySQL) or `sslMode=trust` (MariaDB driver). For
certificate verification, configure the connection's SSL tab with the
[RDS CA bundle](https://truststore.pki.rds.amazonaws.com/global/global-bundle.pem).

## AWS and database prerequisites

- IAM database authentication is enabled on the Aurora cluster.
- Your IAM identity has `rds-db:connect` on
  `arn:aws:rds-db:<region>:<account>:dbuser:<cluster-resource-id>/<db-user>`.
- The database user is IAM-enabled:
  - PostgreSQL: `CREATE USER app_user; GRANT rds_iam TO app_user;`
  - MySQL: `CREATE USER app_user IDENTIFIED WITH AWSAuthenticationPlugin AS 'RDS';`

## Layout

| Path | What |
|---|---|
| `plugin.xml` | Registers the auth model (`dataSourceAuth`) and its connection-page UI (`propertyConfigurator`) |
| `AuroraIamAuthModel` | Resolves host/port/user, gets a token, injects it as the JDBC password, forces SSL |
| `AuroraIamTokenGenerator` | AWS SDK v2: credentials per profile (cached), region resolution, `RdsUtilities` signing |
| `ui/AuroraIamAuthConfigurator` | SWT fields: username, profile, region, token hostname |

The AWS SDK jars are embedded under `lib/` in the bundle and listed on `Bundle-ClassPath`. Calls into the SDK
switch the thread context class loader to the bundle's loader, because the SDK finds its HTTP client and its
SSO/STS credential factories through it.
