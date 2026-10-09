package com.itproblems.dbeaver.aurora.iam;

public final class AuroraIamConstants {

    public static final String AUTH_MODEL_ID = "aws_rds_iam";

    /** AWS profile from ~/.aws/config. Empty means the SDK default credentials chain. */
    public static final String PROP_PROFILE = "aws.iam.profile";
    /** AWS region. Empty means: parse it from the RDS hostname, then fall back to the profile's region. */
    public static final String PROP_REGION = "aws.iam.region";
    /** Hostname to sign the token for, when connecting through a CNAME or a local proxy instead of the RDS endpoint. */
    public static final String PROP_TOKEN_HOST = "aws.iam.tokenHost";

    private AuroraIamConstants() {
    }
}
