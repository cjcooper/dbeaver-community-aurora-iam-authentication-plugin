package com.itproblems.dbeaver.aurora.iam;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuroraIamTokenGeneratorTest {

    @Test
    void regionFromAuroraClusterEndpoint() {
        assertEquals("us-east-1", AuroraIamTokenGenerator.regionFromHost("app.cluster-abc123xyz.us-east-1.rds.amazonaws.com"));
        assertEquals("us-west-2", AuroraIamTokenGenerator.regionFromHost("app.cluster-ro-abc123xyz.us-west-2.rds.amazonaws.com"));
        assertEquals("eu-central-1", AuroraIamTokenGenerator.regionFromHost("instance-1.abc123xyz.eu-central-1.rds.amazonaws.com"));
        assertEquals("us-gov-west-1", AuroraIamTokenGenerator.regionFromHost("db.abc.us-gov-west-1.rds.amazonaws.com"));
        assertEquals("cn-north-1", AuroraIamTokenGenerator.regionFromHost("db.abc.cn-north-1.rds.amazonaws.com.cn"));
        assertEquals("ap-southeast-2", AuroraIamTokenGenerator.regionFromHost(" APP.CLUSTER-ABC.AP-SOUTHEAST-2.RDS.AMAZONAWS.COM "));
    }

    @Test
    void noRegionFromOtherHosts() {
        assertNull(AuroraIamTokenGenerator.regionFromHost("localhost"));
        assertNull(AuroraIamTokenGenerator.regionFromHost("db.internal.example.com"));
        assertNull(AuroraIamTokenGenerator.regionFromHost(null));
    }

    @Test
    void explicitRegionWins() {
        assertEquals("eu-west-1", AuroraIamTokenGenerator.resolveRegion("app.cluster-x.us-east-1.rds.amazonaws.com", "", " eu-west-1 "));
    }

    @Test
    void tokenIsSignedLocallyWithStaticCredentials() throws Exception {
        System.setProperty("aws.accessKeyId", "AKIDEXAMPLE");
        System.setProperty("aws.secretAccessKey", "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY");
        try {
            // Default chain (empty profile) picks up the system properties; no network involved.
            String token = AuroraIamTokenGenerator.generateToken(
                "app.cluster-abc.us-east-1.rds.amazonaws.com", 5432, "iam_user", "", null);
            assertTrue(token.startsWith("app.cluster-abc.us-east-1.rds.amazonaws.com:5432/?"), token);
            assertTrue(token.contains("Action=connect"), token);
            assertTrue(token.contains("DBUser=iam_user"), token);
            assertTrue(token.contains("X-Amz-Credential=AKIDEXAMPLE%2F"), token);
            assertTrue(token.contains("%2Fus-east-1%2Frds-db%2Faws4_request"), token);
        } finally {
            System.clearProperty("aws.accessKeyId");
            System.clearProperty("aws.secretAccessKey");
        }
    }
}
